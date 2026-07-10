package com.paicli.hook;

import com.paicli.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目 Hook 信任（seam 3 信任状态）：未钉住→请求确认（展示命令）、批准→钉住并激活、拒绝→跳过会话继续、
 * 已钉住→静默激活、内容变更→重新确认；钉住按项目隔离；headless 从不弹窗（未钉住静默跳过、已钉住运行）；
 * 授予/拒绝/headless 跳过写审计。
 */
class ProjectHookTrustTest {

    private static Path exec(Path dir, String name, String body) throws Exception {
        Path s = dir.resolve(name);
        Files.writeString(s, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(s, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return s;
    }

    private static Path projectConfig(Path dir, String command) throws Exception {
        Path cfg = dir.resolve("proj-hooks.json");
        Files.writeString(cfg, """
                { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(command));
        return cfg;
    }

    private static HookPayload pre(String tool, String root) {
        return HookPayload.forPreToolUse("s1", null, root, tool,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "Foo.java"));
    }

    private HooksConfigLoader loader(Path projectCfg, Path projectDir,
                                     HookTrustStore store, HookTrustConfirmer confirmer, AuditLog audit) {
        return new HooksConfigLoader(projectDir.resolve("no-user.json"), projectCfg, projectDir, msg -> { })
                .withProjectTrust(store, confirmer, audit);
    }

    @Test
    void unpinnedApprovedActivatesAndPins(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        Path cfg = projectConfig(dir, script.toString());
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"));

        AtomicBoolean shownCommands = new AtomicBoolean(false);
        HookTrustConfirmer approve = (projectDir, commands) -> {
            shownCommands.set(commands.stream().anyMatch(c -> c.contains("blk.sh")));
            return true;
        };
        List<HooksConfigLoader.Registration> regs = loader(cfg, dir, store, approve, audit).load();

        assertEquals(1, regs.size(), "批准后项目 hook 激活");
        assertTrue(shownCommands.get(), "确认时应展示实际命令");
        assertEquals(HookDecision.Outcome.BLOCK, regs.get(0).hook().evaluate(pre("write_file", dir.toString())).outcome());
        // 已钉住：第二次加载用抛异常的 confirmer 也不应被调用
        List<HooksConfigLoader.Registration> again = loader(cfg, dir, store,
                (p, c) -> { throw new AssertionError("已钉住不应再确认"); }, audit).load();
        assertEquals(1, again.size());
        assertTrue(audit.readRecent(20).stream().anyMatch(e -> AuditLog.OUTCOME_HOOK_TRUST.equals(e.outcome())));
    }

    @Test
    void unpinnedDeclinedSkipsButSessionContinues(@TempDir Path dir) throws Exception {
        Path cfg = projectConfig(dir, exec(dir, "blk.sh", "exit 2").toString());
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"));

        List<HooksConfigLoader.Registration> regs = loader(cfg, dir, store, (p, c) -> false, audit).load();

        assertTrue(regs.isEmpty(), "拒绝后项目 hook 不激活");
        assertTrue(audit.readRecent(20).stream()
                .anyMatch(e -> AuditLog.OUTCOME_HOOK_TRUST.equals(e.outcome()) && "declined".equals(e.reason())));
    }

    @Test
    void contentChangedReprompts(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        Path cfg = projectConfig(dir, script.toString());
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"));

        // 首次批准并钉住
        loader(cfg, dir, store, (p, c) -> true, audit).load();
        // 内容变更（换个命令）→ 指纹变 → 重新确认（这次拒绝）
        Path script2 = exec(dir, "blk2.sh", "exit 2");
        Files.writeString(cfg, """
                { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(script2));
        AtomicBoolean reprompted = new AtomicBoolean(false);
        List<HooksConfigLoader.Registration> regs = loader(cfg, dir, store, (p, c) -> {
            reprompted.set(true);
            return false;
        }, audit).load();

        assertTrue(reprompted.get(), "内容变更应重新确认");
        assertTrue(regs.isEmpty(), "重新确认被拒 → 不激活");
    }

    @Test
    void pinIsKeyedByProject(@TempDir Path dir) throws Exception {
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        store.pin("/project/a", "fp-a");
        assertTrue(store.isPinned("/project/a", "fp-a"));
        assertFalse(store.isPinned("/project/b", "fp-a"), "钉住 A 不应信任 B");
        assertFalse(store.isPinned("/project/a", "fp-different"), "指纹不同不算钉住");
    }

    @Test
    void headlessUnpinnedSkippedWithoutPrompt(@TempDir Path dir) throws Exception {
        Path cfg = projectConfig(dir, exec(dir, "blk.sh", "exit 2").toString());
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"));

        // headless：confirmer=null → 从不弹窗；未钉住静默跳过
        List<HooksConfigLoader.Registration> regs = loader(cfg, dir, store, null, audit).load();

        assertTrue(regs.isEmpty(), "headless 未钉住项目 hook 应静默跳过");
        assertTrue(audit.readRecent(20).stream()
                .anyMatch(e -> AuditLog.OUTCOME_HOOK_TRUST.equals(e.outcome()) && "skipped-headless".equals(e.reason())));
    }

    @Test
    void headlessPinnedRuns(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        Path cfg = projectConfig(dir, script.toString());
        HookTrustStore store = new HookTrustStore(dir.resolve("trust.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"));

        // 先在交互态批准并钉住
        loader(cfg, dir, store, (p, c) -> true, audit).load();
        // headless 下已钉住 → 运行
        List<HooksConfigLoader.Registration> regs = loader(cfg, dir, store, null, audit).load();

        assertEquals(1, regs.size(), "headless 已钉住项目 hook 应运行");
    }
}
