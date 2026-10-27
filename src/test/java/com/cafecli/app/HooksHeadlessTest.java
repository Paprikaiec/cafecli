package com.cafecli.app;

import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HooksHeadlessTest {
    @TempDir Path root;
    @Test void userPromptSubmitBlockAbortsHeadlessWithV1StderrAndExitCode() throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".cafecli"));
        Files.writeString(home.resolve(".cafecli/hooks.json"), "{\"UserPromptSubmit\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"printf 'keep secrets out' >&2; exit 2\"}]}]}");
        Files.writeString(root.resolve("issue.md"), "secret");
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"text\":\"must not run\"}]}");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"run", "--file", "issue.md"}, new ConfigSources(Map.of(
                "cafecli.provider", "fake", "cafecli.fake.script", "script.json", "cafecli.hook.user_prompt_submit.enabled", "true"),
                Map.of(), home, root), new PrintStream(out), new PrintStream(err));
        assertEquals(1, exit);
        assertEquals("", out.toString());
        assertEquals("❌ 运行失败: UserPromptSubmit hook 拦截，run 中止: keep secrets out\n", err.toString());
    }

    @Test void configuredHooksUseExplicitInterpolationSourcesInV1Precedence() throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".cafecli"));
        Files.writeString(root.resolve(".env"), "A=project\nB=project\nC=project\n");
        Files.writeString(home.resolve(".env"), "A=home\nB=home\nC=home\nD=home\n");
        hookFile(home.resolve(".cafecli/hooks.json"), "printf '%s' '${A}|${B}|${C}|${D}|${HOME}|${PROJECT_DIR}' >&2; exit 2");
        var sources = sources(home, Map.of("A", "property", "B", "property"), Map.of("A", "environment"));
        try (var session = Composition.headlessSession(com.cafecli.harness.config.ConfigLoader.load(sources))) {
            var blocked = assertInstanceOf(com.cafecli.harness.SubmitResult.Blocked.class, session.submit("input"));
            assertEquals("environment|property|project|home|" + home + "|" + root, blocked.reason());
            assertTrue(session.log().isEmpty());
        }
    }

    @Test void projectTrustIsConfirmedOnceAndHeadlessRequiresTheExactConfigFingerprint() throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".cafecli"));
        Files.createDirectories(root.resolve(".cafecli"));
        Path hooks = root.resolve(".cafecli/hooks.json");
        hookFile(hooks, "printf project-block >&2; exit 2");
        var config = com.cafecli.harness.config.ConfigLoader.load(sources(home, Map.of(), Map.of()));
        // Headless never confirms an unpinned project.
        try (var session = Composition.headlessSession(config)) {
            assertInstanceOf(com.cafecli.harness.SubmitResult.Completed.class, session.submit("untrusted"));
        }
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = com.cafecli.harness.permission.PermissionCapability.headless();
        var log = new com.cafecli.policy.AuditLog(config.auditDirectory());
        var audit = new com.cafecli.harness.permission.AuditCapability(permissions, log);
        var capability = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), audit, (project, commands) -> {
            approvals.incrementAndGet();
            assertEquals(root, project);
            assertEquals(List.of("printf project-block >&2; exit 2"), commands);
            return true;
        }, ignored -> {});
        try (var session = com.cafecli.harness.AgentSession.builder().config(config).model(Composition.FAKE_MODEL)
                .provider(com.cafecli.ai.fake.FakeProvider.scripted(List.of(com.cafecli.ai.fake.FakeResponse.text("unused"))))
                .capabilities(List.of(permissions, audit, capability)).build()) {
            assertInstanceOf(com.cafecli.harness.SubmitResult.Blocked.class, session.submit("approve trust"));
        }
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(hooks)));
        assertTrue(new com.cafecli.hook.HookTrustStore(home.resolve(".cafecli/hook-trust.json")).isPinned(root.toString(), digest));
        try (var session = Composition.headlessSession(config)) {
            assertEquals("project-block", assertInstanceOf(com.cafecli.harness.SubmitResult.Blocked.class, session.submit("pinned")).reason());
        }
        Files.writeString(hooks, Files.readString(hooks) + " ");
        try (var session = Composition.headlessSession(config)) {
            assertInstanceOf(com.cafecli.harness.SubmitResult.Completed.class, session.submit("changed bytes"));
        }
        assertEquals(1, approvals.get());
        var entries = log.readRecent(30);
        assertTrue(entries.stream().anyMatch(entry -> "hook_trust".equals(entry.outcome()) && "granted".equals(entry.reason())));
        assertEquals(2, entries.stream().filter(entry -> "hook_trust".equals(entry.outcome()) && "skipped-headless".equals(entry.reason())).count());
    }

    @Test void headlessAskAndAllowDoNotIntroduceInteractiveApprovalOrHookAllowAudit() throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".cafecli"));
        Files.writeString(home.resolve(".cafecli/hooks.json"), "{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"printf '{\\\"decision\\\":\\\"ask\\\"}'\"}]}]}");
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"toolCalls\":[{\"id\":\"w\",\"name\":\"write_file\",\"arguments\":\"{\\\"path\\\":\\\"note\\\",\\\"content\\\":\\\"ok\\\"}\"}]},{\"text\":\"done\"}]}");
        var properties = new HashMap<>(Map.of("cafecli.provider", "fake", "cafecli.fake.script", "script.json", "cafecli.hook.pre_tool_use.enabled", "true"));
        var config = com.cafecli.harness.config.ConfigLoader.load(new ConfigSources(properties, Map.of(), home, root));
        try (var session = Composition.headlessSession(config)) {
            assertInstanceOf(com.cafecli.harness.SubmitResult.Completed.class, session.submit("write"));
            assertEquals("ok", Files.readString(root.resolve("note")));
        }
        Files.writeString(home.resolve(".cafecli/hooks.json"), Files.readString(home.resolve(".cafecli/hooks.json")).replace("ask", "allow"));
        try (var session = Composition.headlessSession(config)) { session.submit("write again"); }
        assertEquals(2, new com.cafecli.policy.AuditLog(config.auditDirectory()).readRecent(20).size());
        assertTrue(new com.cafecli.policy.AuditLog(config.auditDirectory()).readRecent(20).stream()
                .noneMatch(entry -> "hook".equals(entry.approver())));
    }

    @Test void mcpMentionsExpandBeforeUserPromptSubmitInTheActualComposition() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path home = Files.createDirectories(root.resolve("home/.cafecli")).getParent();
        Files.createDirectories(root.resolve(".cafecli"));
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"text\":\"must not run\"}]}");
        hookFile(home.resolve(".cafecli/hooks.json"), "input=$(cat); case \"$input\" in *RESOURCE_BODY*) printf expanded-resource >&2; exit 2;; esac");
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    try {
                        var body = json.readTree(request.getBody().readUtf8());
                        if (!body.has("id")) return new okhttp3.mockwebserver.MockResponse().setResponseCode(202);
                        Object result = switch (body.path("method").asText()) {
                            case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("resources", Map.of()));
                            case "tools/list" -> Map.of("tools", List.of());
                            case "resources/list" -> Map.of("resources", List.of(Map.of("uri", "file://guide", "name", "Guide")));
                            case "resources/read" -> Map.of("contents", List.of(Map.of("uri", "file://guide", "text", "RESOURCE_BODY")));
                            default -> Map.of();
                        };
                        return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                                .setBody(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", body.path("id").asLong(), "result", result)));
                    } catch (Exception e) { return new okhttp3.mockwebserver.MockResponse().setResponseCode(500); }
                }
            });
            server.start();
            Files.writeString(root.resolve(".cafecli/mcp.json"), json.writeValueAsString(Map.of("mcpServers",
                    Map.of("demo", Map.of("url", server.url("/mcp").toString())))));
            var config = com.cafecli.harness.config.ConfigLoader.load(new ConfigSources(Map.of(
                    "cafecli.provider", "fake", "cafecli.fake.script", "script.json", "cafecli.hook.user_prompt_submit.enabled", "true"),
                    Map.of(), home, root));
            try (var session = Composition.headlessSession(config)) {
                assertEquals("expanded-resource", assertInstanceOf(com.cafecli.harness.SubmitResult.Blocked.class,
                        session.submit("Read @demo:file://guide")).reason());
                assertTrue(session.log().isEmpty());
            }
        }
    }

    private ConfigSources sources(Path home, Map<String, String> additions, Map<String, String> environment) throws Exception {
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"text\":\"done\"}]}");
        var properties = new HashMap<>(Map.of("cafecli.provider", "fake", "cafecli.fake.script", "script.json",
                "cafecli.hook.user_prompt_submit.enabled", "true"));
        properties.putAll(additions);
        return new ConfigSources(properties, environment, home, root);
    }
    private static void hookFile(Path path, String command) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(path, json.writeValueAsString(Map.of("UserPromptSubmit", List.of(Map.of("hooks", List.of(
                Map.of("type", "command", "command", command)))))));
    }
}
