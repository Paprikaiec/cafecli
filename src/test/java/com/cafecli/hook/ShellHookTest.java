package com.cafecli.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.runtime.CancellationContext;
import com.cafecli.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shell 租户执行器的单元 seam（seam 2）：只用临时脚本，绝不跑真实构建工具。
 * exit 0 / exit 2+stderr / JSON stdout 超集 / 非 0 非 2 告警不阻断 / 超时 fail-open / 取消杀进程 /
 * matcher 命中前不 spawn / payload 到 stdin + CAFECLI_PROJECT_DIR。
 */
class ShellHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HookPayload pre(String tool, String root) {
        return HookPayload.forPreToolUse("s1", null, root,
                tool, MAPPER.createObjectNode().put("path", "Foo.java"));
    }

    private static Path script(Path dir, String name, String body) throws Exception {
        Path s = dir.resolve(name);
        Files.writeString(s, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(s, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return s;
    }

    private static ShellHook hook(String command, Duration timeout) {
        return new ShellHook("test", null, command, timeout, null, msg -> { }, MAPPER);
    }

    @Test
    void exitZeroIsContinue(@TempDir Path dir) throws Exception {
        Path s = script(dir, "ok.sh", "exit 0");
        HookDecision d = hook(s.toString(), Duration.ofSeconds(5)).evaluate(pre("write_file", dir.toString()));
        assertEquals(HookDecision.Outcome.CONTINUE, d.outcome());
    }

    @Test
    void exitTwoBlocksWithStderrAsReason(@TempDir Path dir) throws Exception {
        Path s = script(dir, "block.sh", "echo 'secret detected' 1>&2\nexit 2");
        HookDecision d = hook(s.toString(), Duration.ofSeconds(5)).evaluate(pre("write_file", dir.toString()));
        assertEquals(HookDecision.Outcome.BLOCK, d.outcome());
        assertTrue(d.reason().contains("secret detected"), d.reason());
    }

    @Test
    void jsonStdoutSupersetDecodesToDecision(@TempDir Path dir) throws Exception {
        Path s = script(dir, "json.sh",
                "echo '{\"decision\":\"ask\",\"additionalContext\":\"needs review\"}'\nexit 0");
        HookDecision d = hook(s.toString(), Duration.ofSeconds(5)).evaluate(pre("write_file", dir.toString()));
        assertEquals(HookDecision.Outcome.ASK, d.outcome());
        assertEquals("needs review", d.additionalContext());
    }

    @Test
    void otherExitCodeWarnsButDoesNotBlock(@TempDir Path dir) throws Exception {
        Path s = script(dir, "boom.sh", "echo 'broken' 1>&2\nexit 7");
        HookDecision d = hook(s.toString(), Duration.ofSeconds(5)).evaluate(pre("write_file", dir.toString()));
        assertEquals(HookDecision.Outcome.CONTINUE, d.outcome());
    }

    @Test
    void timeoutFailsOpen(@TempDir Path dir) throws Exception {
        Path s = script(dir, "slow.sh", "sleep 10\nexit 2");
        long start = System.currentTimeMillis();
        HookDecision d = hook(s.toString(), Duration.ofMillis(300)).evaluate(pre("write_file", dir.toString()));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(HookDecision.Outcome.CONTINUE, d.outcome(), "超时应 fail-open 为 CONTINUE");
        assertTrue(elapsed < 5000, "应在超时后很快返回，实际 " + elapsed + "ms");
    }

    @Test
    void matcherMissDoesNotSpawn(@TempDir Path dir) throws Exception {
        // 脚本若被执行会 exit 2 阻断；matcher 只认 edit_file，写 write_file 不该 spawn。
        Path s = script(dir, "block.sh", "exit 2");
        ShellHook h = new ShellHook("test", Pattern.compile("edit_file"),
                s.toString(), Duration.ofSeconds(5), null, msg -> { }, MAPPER);
        HookDecision d = h.evaluate(pre("write_file", dir.toString()));
        assertEquals(HookDecision.Outcome.CONTINUE, d.outcome(), "matcher miss 不应 spawn（否则会被 exit 2 阻断）");
    }

    @Test
    void payloadArrivesOnStdinAndProjectDirEnvSet(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("captured.txt");
        // 脚本把 stdin 与 CAFECLI_PROJECT_DIR 落盘，供断言 payload 形状与环境变量。
        Path s = script(dir, "capture.sh",
                "cat > '" + out + "'\necho \"DIR=$CAFECLI_PROJECT_DIR\" >> '" + out + "'\nexit 0");
        hook(s.toString(), Duration.ofSeconds(5)).evaluate(pre("write_file", dir.toString()));

        String captured = Files.readString(out);
        assertTrue(captured.contains("\"event\":\"PRE_TOOL_USE\""), captured);
        assertTrue(captured.contains("\"toolName\":\"write_file\""), captured);
        assertTrue(captured.contains("DIR=" + dir), captured);
    }

    @Test
    void cancellationKillsProcessAndFailsOpen(@TempDir Path dir) throws Exception {
        Path s = script(dir, "slow.sh", "sleep 10\nexit 2");
        CancellationToken token = CancellationContext.startRun();
        try {
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                token.cancel();
            });
            canceller.start();
            long start = System.currentTimeMillis();
            HookDecision d = hook(s.toString(), Duration.ofSeconds(30)).evaluate(pre("write_file", dir.toString()));
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(HookDecision.Outcome.CONTINUE, d.outcome(), "取消应 fail-open");
            assertTrue(elapsed < 5000, "取消后应尽快返回: " + elapsed + "ms");
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void statusMessageEmittedThroughSinkBeforeRun(@TempDir Path dir) throws Exception {
        Path s = script(dir, "ok.sh", "exit 0");
        List<String> emitted = new java.util.ArrayList<>();
        ShellHook h = new ShellHook("linter", null, s.toString(),
                Duration.ofSeconds(5), "running linter…", emitted::add, MAPPER);
        h.evaluate(pre("write_file", dir.toString()));
        assertEquals(List.of("running linter…"), emitted);
    }
}
