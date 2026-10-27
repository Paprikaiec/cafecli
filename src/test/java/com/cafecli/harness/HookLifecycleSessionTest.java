package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.harness.hooks.HooksCapability;
import com.cafecli.harness.hooks.HookContext;
import com.cafecli.harness.hooks.StopBlock;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.permission.AuditCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.hook.*;
import com.cafecli.policy.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class HookLifecycleSessionTest {
    @TempDir Path root;
    static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());

    @Test void configuredToolHooksReceiveOneSessionIdentityAcrossRunsAndFreshSessionsHaveDistinctIdentities() throws Exception {
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled", "true", "cafecli.hook.post_tool_use.enabled", "true",
                "cafecli.hook.user_prompt_submit.enabled", "true", "cafecli.hook.stop.enabled", "true"));
        Files.createDirectories(config.homes().user());
        var command = Map.of("hooks", List.of(Map.of("type", "command", "command",
                "cat >> observations.jsonl; printf '\\n' >> observations.jsonl")));
        var json = new ObjectMapper();
        Files.writeString(config.homes().user().resolve("hooks.json"), json.writeValueAsString(Map.of(
                "PreToolUse", List.of(command), "PostToolUse", List.of(command),
                "UserPromptSubmit", List.of(command), "Stop", List.of(command))));
        var script = List.of(FakeResponse.builder().toolCall("r1", "read_file", "{\"path\":\"missing\"}").build(),
                FakeResponse.text("first"), FakeResponse.builder().toolCall("r2", "read_file", "{\"path\":\"missing\"}").build(),
                FakeResponse.text("second"));
        for (int session = 0; session < 2; session++) {
            try (var opened = session(config, FakeProvider.scripted(script), new WorkspaceCapability(root),
                    new HooksCapability(config.hooks(), null, null, ignored -> {}))) {
                SessionTestSupport.run(opened, "first run");
                SessionTestSupport.run(opened, "second run");
            }
        }
        var observations = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(root.resolve("observations.jsonl"))) observations.add(json.readTree(line));
        assertEquals(16, observations.size());
        var toolIds = observations.stream().filter(node -> node.path("event").asText().endsWith("_TOOL_USE"))
                .map(node -> node.path("sessionId").asText()).toList();
        assertEquals(8, toolIds.size());
        assertTrue(toolIds.stream().allMatch(id -> id.matches(
                "session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
                "every Pre/Post payload needs a session-UUID identity");
        assertEquals(List.of(toolIds.getFirst(), toolIds.getFirst(), toolIds.getFirst(), toolIds.getFirst()), toolIds.subList(0, 4));
        assertEquals(List.of(toolIds.get(4), toolIds.get(4), toolIds.get(4), toolIds.get(4)), toolIds.subList(4, 8));
        assertNotEquals(toolIds.getFirst(), toolIds.get(4));
        assertTrue(observations.stream().filter(node -> !node.path("event").asText().endsWith("_TOOL_USE"))
                .allMatch(node -> !node.hasNonNull("sessionId")), "UPS and Stop retain v1's null identity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_prompt_submit", "pre_tool_use", "post_tool_use", "stop"})
    void cancellationTerminatesTheLocalShellHookWithoutAmbientRuntimeContext(String eventName) throws Exception {
        var config = config(Map.of("cafecli.hook." + eventName + ".enabled", "true"));
        HookEvent event = switch (eventName) {
            case "user_prompt_submit" -> HookEvent.USER_PROMPT_SUBMIT;
            case "pre_tool_use" -> HookEvent.PRE_TOOL_USE;
            case "post_tool_use" -> HookEvent.POST_TOOL_USE;
            default -> HookEvent.STOP;
        };
        Path pidFile = root.resolve("hook.pid");
        var shell = new ShellHook("blocking-fixture", null, "printf '%s' $$ > hook.pid; exec sleep 30",
                Duration.ofSeconds(30), null, null, new ObjectMapper());
        var provider = FakeProvider.scripted(event == HookEvent.STOP ? List.of(FakeResponse.text("done"))
                : List.of(FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"missing\"}").build(), FakeResponse.text("done")));
        var hooks = new HooksCapability(config.hooks(), List.of(new HooksCapability.Registration(event, shell)), null);
        try (var session = session(config, provider, new WorkspaceCapability(root), hooks)) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("input"));
            long pid = -1;
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (!Files.exists(pidFile) || Files.size(pidFile) == 0) Thread.sleep(5);
                });
                pid = Long.parseLong(Files.readString(pidFile));
                assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
                session.cancel();
                var completed = assertInstanceOf(SubmitResult.Completed.class, running.get(2, TimeUnit.SECONDS));
                assertInstanceOf(RunOutcome.Cancelled.class, completed.outcome());
                long hookPid = pid;
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    while (ProcessHandle.of(hookPid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(5);
                });
                if (event == HookEvent.USER_PROMPT_SUBMIT) assertTrue(provider.requests().isEmpty(), "UPS runs before Agent.run");
            } finally {
                session.cancel();
                if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                running.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void userPromptSubmitRewritesOrdinaryInputAndDoesNotRunOnSteering() {
        var config = config(Map.of("cafecli.hook.user_prompt_submit.enabled", "true"));
        var submits = new AtomicInteger();
        HookTenant submit = new HookTenant() {
            public String name() { return "submit-context"; }
            public HookDecision evaluate(HookPayload payload) {
                submits.incrementAndGet();
                return HookDecision.cont().withAdditionalContext("team rule");
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"missing\"}").build(),
                FakeResponse.text("done"), FakeResponse.text("next")));
        try (var session = session(config, provider, new WorkspaceCapability(root), new HooksCapability(config.hooks(),
                List.of(new HooksCapability.Registration(HookEvent.USER_PROMPT_SUBMIT, submit)), null))) {
            session.subscribe(event -> {
                if (event instanceof AgentEvent.ToolEnd) assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("steer"));
            });
            SessionTestSupport.run(session, "ordinary");
            assertEquals(1, submits.get());
            assertEquals("ordinary\n\n[Hook 上下文 / UserPromptSubmit]\nteam rule",
                    ((TextBlock) ((com.cafecli.ai.message.UserMessage) provider.requests().getFirst().context().messages().getFirst()).content().getFirst()).text());
            assertTrue(provider.requests().get(1).context().messages().toString().contains("[用户转向 / 回合中提交]\nsteer"));
            SessionTestSupport.run(session, "next ordinary");
            assertEquals(2, submits.get());
        }
    }

    @Test void finishSummaryUsesSourceOrderAndEffectiveApprovalArgumentsDespiteReverseCompletion() {
        var config = config(Map.of("cafecli.hook.stop.enabled", "true", "cafecli.hook.finish_gate.enabled", "true",
                "cafecli.hook.finish_gate.verify_regex", "effective-test"));
        var verifyFinished = new CountDownLatch(1);
        var completionOrder = new ArrayList<String>();
        Capability tools = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.tool(tool("write_file", input -> {
                    assertTrue(verifyFinished.await(2, TimeUnit.SECONDS));
                    return text("written");
                }));
                agent.tool(tool("execute_command", input -> text("命令执行完成 (exit code: 0)\n")));
            }
        };
        var permissions = new PermissionCapability((request, token) -> request.call().name().equals("execute_command")
                ? ApprovalResult.modify("{\"command\":\"effective-test\"}") : ApprovalResult.approve(), true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file", "{\"path\":\"note\"}")
                .toolCall("v", "execute_command", "{\"command\":\"not-verification\"}").build(), FakeResponse.text("done")));
        try (var session = session(config, provider, tools, new HooksCapability(config.hooks(), List.of(), null), permissions)) {
            session.subscribe(event -> {
                if (event instanceof AgentEvent.ToolEnd end) {
                    completionOrder.add(end.result().message().toolCallId());
                    if (end.result().message().toolCallId().equals("v")) verifyFinished.countDown();
                }
            });
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "write then verify"));
            assertEquals(List.of("v", "w"), completionOrder);
            assertEquals(2, provider.requests().size());
            assertFalse(session.log().stream().anyMatch(StopBlock.class::isInstance));
            assertTrue(provider.requests().get(1).context().messages().toString().contains("not-verification"), "assistant call stays original");
        } finally { verifyFinished.countDown(); }
    }

    @Test void aBlockedAuditWriterCannotStallHookBlocksOrHookAllowsAndCloseDrainsThem() throws Exception {
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled", "true"));
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AuditLog log = new AuditLog(root.resolve("audit")) {
            @Override public void record(AuditEntry entry) {
                writing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                super.record(entry);
            }
        };
        var permissions = new PermissionCapability((request, token) -> { fail("hook route must resolve permission"); return ApprovalResult.approve(); }, true);
        var audit = new AuditCapability(permissions, log);
        var calls = new AtomicInteger();
        HookTenant pre = new HookTenant() {
            public String name() { return "block-then-allow"; }
            public HookDecision evaluate(HookPayload payload) { return calls.incrementAndGet() == 1 ? HookDecision.block("first blocked") : HookDecision.allow(); }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("a", "write_file", "{\"path\":\"a\",\"content\":\"bad\"}")
                .toolCall("b", "write_file", "{\"path\":\"b\",\"content\":\"ok\"}").build(), FakeResponse.text("done")));
        var session = session(config, provider, new WorkspaceCapability(root), permissions, audit,
                new HooksCapability(config.hooks(), List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE, pre)), audit));
        var running = CompletableFuture.supplyAsync(() -> session.submit("input"));
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SubmitResult.Completed.class, running.get(2, TimeUnit.SECONDS));
            assertEquals("ok", Files.readString(root.resolve("b")));
            assertTrue(log.readRecent(10).isEmpty());
        } finally { release.countDown(); session.cancel(); running.get(3, TimeUnit.SECONDS); session.close(); }
        var entries = log.readRecent(10);
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(entry -> "hook_block".equals(entry.outcome()) && "PreToolUse/write_file".equals(entry.tool())
                && "first blocked".equals(entry.reason()) && "hook".equals(entry.approver()) && entry.args() == null));
        assertTrue(entries.stream().anyMatch(entry -> "allow".equals(entry.outcome()) && "hook".equals(entry.approver())
                && "hook allow（免 HITL 审批）".equals(entry.reason())));
    }

    private interface Execute { ToolResult apply(JsonNode input) throws Exception; }
    private static AgentTool tool(String name, Execute execute) {
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return "local fixture"; }
            public JsonNode parameters() { return new ObjectMapper().createObjectNode(); }
            public ToolResult execute(String id, JsonNode input, CancellationToken cancellation, Consumer<ToolResult> progress) throws Exception {
                return execute.apply(input);
            }
        };
    }
    private static ToolResult text(String text) { return new ToolResult(List.of(new TextBlock(text)), null, ToolResult.Status.OK); }
    private HarnessConfig config(Map<String, String> properties) {
        return ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root));
    }
    private AgentSession session(HarnessConfig config, FakeProvider provider, Capability... capabilities) {
        return AgentSession.builder().config(config).model(MODEL).provider(provider).capabilities(List.of(capabilities)).build();
    }
}
