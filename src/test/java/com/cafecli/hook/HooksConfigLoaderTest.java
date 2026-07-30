package com.cafecli.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hooks.json 配置加载 + 信任前的作用域 seam（seam 3，{@code McpConfigLoaderTest} 先例）：
 * 用户级加载、{@code ${VAR}} 展开、matcher 正则过滤、非法条目清晰报错、项目级解析但惰性（本 slice 不激活）。
 */
class HooksConfigLoaderTest {

    private static HookPayload pre(String tool, String root) {
        return HookPayload.forPreToolUse("s1", null, root, tool,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "Foo.java"));
    }

    private static Path exec(Path dir, String name, String body) throws Exception {
        Path s = dir.resolve(name);
        Files.writeString(s, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(s, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return s;
    }

    private static HooksConfigLoader loader(Path userCfg, Path projectCfg, Path projectDir) {
        return new HooksConfigLoader(userCfg, projectCfg, projectDir, msg -> { });
    }

    @Test
    void absentConfigsYieldEmpty(@TempDir Path dir) throws Exception {
        List<HooksConfigLoader.Registration> regs =
                loader(dir.resolve("nope-user.json"), dir.resolve("nope-proj.json"), dir).load();
        assertTrue(regs.isEmpty());
    }

    @Test
    void userLevelCommandHookIsLoadedAndWired(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "block.sh", "echo blocked 1>&2\nexit 2");
        Path user = dir.resolve("hooks.json");
        Files.writeString(user, """
                { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(script));

        List<HooksConfigLoader.Registration> regs = loader(user, dir.resolve("noproj.json"), dir).load();

        assertEquals(1, regs.size());
        assertEquals(HookEvent.PRE_TOOL_USE, regs.get(0).event());
        assertEquals(HookDecision.Outcome.BLOCK, regs.get(0).hook().evaluate(pre("write_file", dir.toString())).outcome());
    }

    @Test
    void varExpansionInCommand(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        System.setProperty("CAFECLI_TEST_HOOK_SCRIPT", script.toString());
        try {
            Path user = dir.resolve("hooks.json");
            Files.writeString(user, """
                    { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "${CAFECLI_TEST_HOOK_SCRIPT}" } ] } ] }
                    """);

            List<HooksConfigLoader.Registration> regs = loader(user, dir.resolve("noproj.json"), dir).load();

            // 展开成功则命令指向真实脚本，exit 2 阻断；展开失败会报错或跑不起来。
            assertEquals(HookDecision.Outcome.BLOCK,
                    regs.get(0).hook().evaluate(pre("write_file", dir.toString())).outcome());
        } finally {
            System.clearProperty("CAFECLI_TEST_HOOK_SCRIPT");
        }
    }

    @Test
    void matcherRegexFiltersByToolName(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        Path user = dir.resolve("hooks.json");
        Files.writeString(user, """
                { "PreToolUse": [ { "matcher": "edit_file", "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(script));

        HooksConfigLoader.Registration reg = loader(user, dir.resolve("noproj.json"), dir).load().get(0);

        assertEquals(HookDecision.Outcome.CONTINUE,
                reg.hook().evaluate(pre("write_file", dir.toString())).outcome(), "matcher 不命中不 spawn");
        assertEquals(HookDecision.Outcome.BLOCK,
                reg.hook().evaluate(pre("edit_file", dir.toString())).outcome(), "matcher 命中");
    }

    @Test
    void malformedEntryRejectedWithClearMessage(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("hooks.json");
        Files.writeString(user, """
                { "PreToolUse": [ { "hooks": [ { "type": "http", "command": "curl x" } ] } ] }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader(user, dir.resolve("noproj.json"), dir).load());
        assertTrue(ex.getMessage().contains("command"), ex.getMessage());
    }

    @Test
    void missingCommandRejected(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("hooks.json");
        Files.writeString(user, """
                { "PreToolUse": [ { "hooks": [ { "type": "command" } ] } ] }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> loader(user, dir.resolve("noproj.json"), dir).load());
    }

    @Test
    void projectLevelParsedButInert(@TempDir Path dir) throws Exception {
        Path script = exec(dir, "blk.sh", "exit 2");
        Path project = dir.resolve("proj-hooks.json");
        Files.writeString(project, """
                { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "%s" } ] } ] }
                """.formatted(script));

        // 用户级不存在、项目级存在 → 本 slice 项目级惰性，返回空（信任是 slice 04）。
        List<HooksConfigLoader.Registration> regs =
                loader(dir.resolve("no-user.json"), project, dir).load();

        assertTrue(regs.isEmpty(), "项目级 hook 本 slice 应被解析但不激活");
    }
}
