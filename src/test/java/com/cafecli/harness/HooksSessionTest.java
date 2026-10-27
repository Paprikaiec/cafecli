package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.hooks.HooksCapability;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.hook.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HooksSessionTest {
    @TempDir Path root;
    static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CONTINUE,ALLOW,OK,0", "ALLOW,ASK,OK,1", "ASK,ALLOW,OK,1",
            "ALLOW,BLOCK,BLOCKED,0", "BLOCK,ASK,BLOCKED,0"})
    void mixedHookTenantsApplyTheStrictestDecisionThroughTheActualPermissionRoute(
            HookDecision.Outcome first,HookDecision.Outcome second,ToolResult.Status expected,int approvals) {
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled","true"));
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var permission = new PermissionCapability((request,token) -> { asked.incrementAndGet(); return ApprovalResult.approve(); },true);
        var hooks = new HooksCapability(config.hooks(),List.of(
                new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,tenant(new HookDecision(first,"policy feedback",null,null,null,null,false))),
                new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,tenant(new HookDecision(second,"policy feedback",null,null,null,null,false)))),null);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("write","write_file",write("note","ok")).build(),FakeResponse.text("done")));
        try (var session = session(config,provider,hooks,permission)) {
            session.submit("write");
            assertEquals(expected,results(session).getFirst().status());
            assertEquals(approvals,asked.get());
            assertEquals(expected==ToolResult.Status.OK,Files.exists(root.resolve("note")));
            if (expected==ToolResult.Status.BLOCKED)
                assertTrue(provider.requests().get(1).context().messages().toString().contains("policy feedback"));
        }
    }

    @Test void hookAllowCannotLiftTheCommandGuardOrProduceCommandSideEffects() {
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled","true"));
        var hooks = new HooksCapability(config.hooks(),List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,
                tenant(HookDecision.allow()))),null);
        var permission = new PermissionCapability((request,token) -> { fail("ALLOW skips only approval"); return ApprovalResult.approve(); },true);
        // The literal trips the guard, but remains harmless if this regression ever breaks.
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("command","execute_command",
                "{\"command\":\"printf sudo > should-not-exist\"}").build(),FakeResponse.text("done")));
        try (var session = session(config,provider,hooks,permission)) {
            session.submit("run");
            assertEquals(ToolResult.Status.DENIED,results(session).getFirst().status());
            assertFalse(Files.exists(root.resolve("should-not-exist")));
        }
    }

    @Test void hookAllowSkipsApprovalButCannotLiftThePathGuard() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var permissions = new PermissionCapability((request, token) -> { fail("ALLOW must skip approval"); return ApprovalResult.approve(); }, true);
        var hooks = new HooksCapability(config.hooks(), List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,
                tenant(HookDecision.allow()))), null);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file",
                "{\"path\":\"../victim\",\"content\":\"bad\"}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root), hooks, permissions)).build()) {
            SessionTestSupport.run(session, "write");
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(ToolResult.Status.DENIED, result.status());
            assertFalse(Files.exists(root.resolve("../victim")));
        }
    }
    @Test void hookBlockIsModelFeedbackInTheNextRequest() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file",
                "{\"path\":\"note\",\"content\":\"bad\"}").build(), FakeResponse.text("done")));
        var hooks = new HooksCapability(config.hooks(), List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,
                tenant(HookDecision.block("Read the policy first")))), null);
        try (var session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root), hooks)).build()) {
            SessionTestSupport.run(session, "write");
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(ToolResult.Status.BLOCKED, result.status());
            assertTrue(provider.requests().get(1).context().messages().toString().contains("[Hook 拦截 / PreToolUse]\nRead the policy first"));
            assertFalse(Files.exists(root.resolve("note")));
        }
    }
    @Test void finishGateBlocksUntilVerificationAndAppendsOneTypedStopBlock() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.stop.enabled", "true",
                "cafecli.hook.finish_gate.enabled", "true", "cafecli.hook.finish_gate.verify_regex", "true"),
                Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file",
                "{\"path\":\"note\",\"content\":\"ok\"}").build(), FakeResponse.text("premature"),
                FakeResponse.builder().toolCall("v", "execute_command", "{\"command\":\"true\"}").build(), FakeResponse.text("verified")));
        try (var session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root), new HooksCapability(config.hooks(), List.of(), null))).build()) {
            SessionTestSupport.run(session, "write");
            assertEquals(4, provider.requests().size());
            assertTrue(provider.requests().get(2).context().messages().toString().contains("[收尾门 / Finish Gate]"));
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.hooks.StopBlock.class::isInstance).count());
        }
    }
    @Test void ledgerBlocksBlindWrites() throws Exception {
        Files.writeString(root.resolve("note"), "original");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.hook.pre_tool_use.enabled", "true",
                "cafecli.hook.post_tool_use.enabled", "true", "cafecli.hook.read_ledger.enabled", "true"),
                Map.of(), root.resolve("home"), root));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file",
                "{\"path\":\"note\",\"content\":\"bad\"}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root), new HooksCapability(config.hooks(), List.of(), null))).build()) {
            SessionTestSupport.run(session, "write");
            assertEquals(ToolResult.Status.BLOCKED, session.log().stream().filter(ToolResultEntry.class::isInstance)
                    .map(ToolResultEntry.class::cast).findFirst().orElseThrow().status());
            assertEquals("original", Files.readString(root.resolve("note")));
        }
    }
    @Test void aValueEquivalentAskRouteForcesApprovalWithPolicyDisabled() {
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        Capability route = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.permissionRoute((call, token) -> new com.cafecli.harness.permission.PermissionRoute(
                        com.cafecli.harness.permission.PermissionRoute.Mode.ASK, null));
            }
        };
        var permissions = new PermissionCapability((request, token) -> {
            asked.incrementAndGet(); return ApprovalResult.approve();
        }, false);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("r", "read_file",
                "{\"path\":\"missing\"}").build(), FakeResponse.text("done")));
        try (var session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root), route, permissions)).build()) {
            SessionTestSupport.run(session, "read");
            assertEquals(1, asked.get());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"disabled-read", "disabled-write", "remembered-write"})
    void hookAskForcesApprovalBeyondTheOrdinaryPolicy(String scenario) {
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled", "yes"));
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        boolean remembered = scenario.startsWith("remembered");
        var permissions = new PermissionCapability((request, token) -> {
            assertEquals("Hook 要求本次调用审批（ASK）", request.sensitiveNotice());
            asked.incrementAndGet();
            return ApprovalResult.approveAll();
        }, remembered);
        String name = scenario.endsWith("read") ? "read_file" : "write_file";
        var first = FakeResponse.builder().toolCall("first", name, write("note", "ok")).build();
        var second = FakeResponse.builder().toolCall("second", name, write("note", "again")).build();
        var provider = FakeProvider.scripted(List.of(first, second, FakeResponse.text("done")));
        try (var session = session(config, provider, new HooksCapability(config.hooks(),
                List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE, tenant(HookDecision.ask()))), null), permissions)) {
            SessionTestSupport.run(session, "do it");
            assertEquals(2, asked.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"no-write", "verified", "block-cap", "nonzero-exit", "hard-cap"})
    void finishGateRetainsV1FinishCases(String scenario) {
        var properties = new HashMap<>(Map.of("cafecli.hook.stop.enabled", "true", "cafecli.hook.finish_gate.enabled", "true",
                "cafecli.hook.finish_gate.verify_regex", "true|exit", "cafecli.hook.finish_gate.block_cap", "1"));
        if (scenario.equals("hard-cap")) properties.put("cafecli.react.hard.max.iterations", "2");
        var config = config(properties);
        var responses = new ArrayList<FakeResponse>();
        if (!scenario.equals("no-write")) responses.add(FakeResponse.builder().toolCall("w", "write_file", write("note", "ok")).build());
        if (scenario.equals("verified") || scenario.equals("nonzero-exit")) responses.add(FakeResponse.builder()
                .toolCall("v", "execute_command", "{\"command\":\"" + (scenario.equals("verified") ? "true" : "exit 1") + "\"}").build());
        responses.add(FakeResponse.text("finish"));
        if (scenario.equals("block-cap") || scenario.equals("nonzero-exit")) responses.add(FakeResponse.text("capped"));
        var provider = FakeProvider.scripted(responses);
        try (var session = session(config, provider, new HooksCapability(config.hooks(), List.of(), null))) {
            var outcome = SessionTestSupport.run(session, "change");
            assertEquals(responses.size(), provider.requests().size());
            if (scenario.equals("hard-cap")) assertInstanceOf(com.cafecli.core.RunOutcome.BudgetExhausted.class, outcome);
            else assertInstanceOf(com.cafecli.core.RunOutcome.Completed.class, outcome);
            assertEquals(scenario.equals("no-write") || scenario.equals("verified") ? 0 : 1,
                    session.log().stream().filter(com.cafecli.harness.hooks.StopBlock.class::isInstance).count());
            if (scenario.equals("nonzero-exit")) assertEquals(ToolResult.Status.OK, results(session).getLast().status());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"guard", "edit-error", "approval", "hook"})
    void unsuccessfulWritesNeverTriggerTheFinishGate(String refusal) throws Exception {
        Files.writeString(root.resolve("note"), "original");
        var config = config(Map.of("cafecli.hook.stop.enabled", "true", "cafecli.hook.finish_gate.enabled", "true",
                "cafecli.hook.pre_tool_use.enabled", "true"));
        var registrations = refusal.equals("hook") ? List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE,
                tenant(HookDecision.block("not now")))) : List.<HooksCapability.Registration>of();
        var permissions = new PermissionCapability((request, token) -> refusal.equals("approval")
                ? ApprovalResult.reject("not now") : ApprovalResult.approve(), true);
        var response = refusal.equals("edit-error") ? FakeResponse.builder().toolCall("e", "edit_file",
                "{\"path\":\"note\",\"edits\":[{\"old_string\":\"absent\",\"new_string\":\"bad\"}]}").build()
                : FakeResponse.builder().toolCall("w", "write_file", write(refusal.equals("guard") ? "../victim" : "note", "bad")).build();
        var provider = FakeProvider.scripted(List.of(response, FakeResponse.text("done")));
        try (var session = session(config, provider, new HooksCapability(config.hooks(), registrations, null), permissions)) {
            SessionTestSupport.run(session, "write");
            assertEquals(2, provider.requests().size());
            assertFalse(session.log().stream().anyMatch(com.cafecli.harness.hooks.StopBlock.class::isInstance));
            assertEquals("original", Files.readString(root.resolve("note")));
            assertEquals(switch (refusal) { case "hook" -> ToolResult.Status.BLOCKED; case "edit-error" -> ToolResult.Status.ERROR;
                default -> ToolResult.Status.DENIED; }, results(session).getFirst().status());
        }
    }

    @Test void ledgerDetectsAStaleReadAndFreshAgentsHaveNoObservations() throws Exception {
        Files.writeString(root.resolve("note"), "original");
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled", "true", "cafecli.hook.post_tool_use.enabled", "true",
                "cafecli.hook.read_ledger.enabled", "true"));
        var hooks = new HooksCapability(config.hooks(), List.of(), null);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var provider = FakeProvider.responding(request -> switch (requests.incrementAndGet()) {
            case 1 -> FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"note\"}").build();
            case 2 -> {
                try { Files.writeString(root.resolve("note"), "concurrent"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                yield FakeResponse.builder().toolCall("w", "write_file", write("note", "stale")).build();
            }
            default -> FakeResponse.text("done");
        });
        try (var session = session(config, provider, hooks)) {
            SessionTestSupport.run(session, "read then write");
            assertEquals(ToolResult.Status.BLOCKED, results(session).getLast().status());
            assertTrue(provider.requests().get(2).context().messages().toString().contains("陈旧写"));
            assertEquals("concurrent", Files.readString(root.resolve("note")));
        }
        var fresh = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file", write("note", "bad")).build(), FakeResponse.text("done")));
        try (var session = session(config, fresh, hooks)) {
            SessionTestSupport.run(session, "fresh");
            assertEquals(ToolResult.Status.BLOCKED, results(session).getLast().status());
            assertTrue(fresh.requests().get(1).context().messages().toString().contains("但本回合还没读过它"));
        }
    }

    @Test void anAgentsLedgerSurvivesRunsAndSuccessfulWritesRefreshIt() throws Exception {
        Files.writeString(root.resolve("note"), "original");
        var config = config(Map.of("cafecli.hook.pre_tool_use.enabled", "true", "cafecli.hook.post_tool_use.enabled", "true",
                "cafecli.hook.read_ledger.enabled", "true"));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"note\"}").build(),
                FakeResponse.text("read"), FakeResponse.builder().toolCall("w", "write_file", write("note", "first")).build(),
                FakeResponse.builder().toolCall("w2", "write_file", write("note", "second")).build(), FakeResponse.text("written")));
        try (var session = session(config, provider, new HooksCapability(config.hooks(), List.of(), null))) {
            SessionTestSupport.run(session, "read");
            SessionTestSupport.run(session, "write twice");
            assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.OK));
            assertEquals("second", Files.readString(root.resolve("note")));
        }
    }

    @Test void postContextArrivesOnceThroughItsTypedInboxKind() {
        var config = config(Map.of("cafecli.hook.post_tool_use.enabled", "true"));
        var hook = tenant(HookDecision.cont().withAdditionalContext("  next instruction  "));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("w", "write_file", write("note", "ok")).build(),
                FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"note\"}").build(), FakeResponse.text("done")));
        try (var session = session(config, provider, new HooksCapability(config.hooks(),
                List.of(new HooksCapability.Registration(HookEvent.POST_TOOL_USE, hook)), null))) {
            SessionTestSupport.run(session, "write");
            assertEquals(2, session.log().stream().filter(com.cafecli.harness.hooks.HookContext.class::isInstance).count());
            assertEquals(1, provider.requests().get(1).context().messages().stream()
                    .filter(message -> message.toString().contains("[Hook 上下文 / PostToolUse]\nnext instruction")).count());
            assertEquals(2, provider.requests().get(2).context().messages().stream()
                    .filter(message -> message.toString().contains("[Hook 上下文 / PostToolUse]\nnext instruction")).count());
        }
    }

    @Test void disabledHooksLeaveRecordedRequestsIdentical() {
        var config = config(Map.of());
        List<FakeResponse> script = List.of(FakeResponse.builder().toolCall("r", "read_file", "{\"path\":\"missing\"}").build(), FakeResponse.text("done"));
        var without = FakeProvider.scripted(script);
        var with = FakeProvider.scripted(script);
        var bomb = new HookTenant() {
            public String name() { return "never"; }
            public HookDecision evaluate(HookPayload payload) { fail("disabled events cannot fire"); return HookDecision.cont(); }
        };
        var registrations = Arrays.stream(HookEvent.values()).map(event -> new HooksCapability.Registration(event, bomb)).toList();
        try (var plain = session(config, without); var hooked = session(config, with, new HooksCapability(config.hooks(), registrations, null))) {
            SessionTestSupport.run(plain, "same input");
            SessionTestSupport.run(hooked, "same input");
            assertEquals(without.requests().stream().map(com.cafecli.ai.fake.FakeProvider.Request::context).toList(),
                    with.requests().stream().map(com.cafecli.ai.fake.FakeProvider.Request::context).toList());
        }
    }

    private com.cafecli.harness.config.HarnessConfig config(Map<String, String> properties) {
        return ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root));
    }
    private AgentSession session(com.cafecli.harness.config.HarnessConfig config, FakeProvider provider, Capability... extras) {
        var capabilities = new ArrayList<Capability>();
        capabilities.add(new WorkspaceCapability(root));
        capabilities.addAll(List.of(extras));
        return AgentSession.builder().config(config).model(MODEL).provider(provider).capabilities(capabilities).build();
    }
    private static String write(String path, String content) { return "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}"; }
    private static List<ToolResultEntry> results(AgentSession session) {
        return session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
    }
    static HookTenant tenant(HookDecision decision) {
        return new HookTenant() {
            public String name() { return "fixture"; }
            public HookDecision evaluate(HookPayload payload) { return decision; }
        };
    }
}
