package com.cafecli.harness;

import static com.cafecli.harness.SessionTestSupport.run;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.permission.PermissionEvent;
import com.cafecli.harness.permission.AuditCapability;
import com.cafecli.policy.AuditLog;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.AgentLogJson;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.harness.workspace.WorkspaceCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionSessionTest {
    @TempDir Path root;
    private final List<AgentSession> sessions = new ArrayList<>();

    @AfterEach void closeSessions() {
        for (AgentSession session : sessions) session.close();
    }
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());

    @Test
    void threeApprovalsFollowSourceOrderAndTheEntireBatchWaitsForTheThirdAnswer() throws Exception {
        var requested = new LinkedBlockingQueue<String>();
        var answers = new LinkedBlockingQueue<ApprovalResult>();
        var permissions = new PermissionCapability((request, token) -> {
            requested.add(request.call().id());
            return answers.take();
        }, true);
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("first", "write_file", write("one", "1"))
                .toolCall("second", "write_file", write("two", "2"))
                .toolCall("third", "write_file", write("three", "3")).build(), FakeResponse.text("done")));
        AgentSession session = session(provider, permissions);
        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        session.subscribe(events::add);
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> run(session, "write three"));
        try {
            for (String id : List.of("first", "second", "third")) {
                assertEquals(id, requested.poll(2, TimeUnit.SECONDS));
                assertTrue(events.stream().noneMatch(AgentEvent.ToolStart.class::isInstance));
                assertFalse(Files.exists(root.resolve("one")));
                assertFalse(Files.exists(root.resolve("two")));
                assertFalse(Files.exists(root.resolve("three")));
                answers.add(ApprovalResult.approve());
            }
            assertInstanceOf(RunOutcome.Completed.class, run.get(3, TimeUnit.SECONDS));
            assertEquals("1", Files.readString(root.resolve("one")));
            assertEquals("2", Files.readString(root.resolve("two")));
            assertEquals("3", Files.readString(root.resolve("three")));
        } finally {
            answers.add(ApprovalResult.reject("cleanup"));
            session.cancel();
        }
    }

    @Test
    void aRefusalReachesTheNextModelRequestWithV1TextAndADeniedOutcome() {
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.reject("keep this file"), true);
        var decisions = new ArrayList<PermissionEvent>();
        permissions.subscribe(decisions::add);
        FakeProvider provider = writes("one");
        AgentSession session = session(provider, permissions);

        assertInstanceOf(RunOutcome.Completed.class, run(session, "write"));

        assertEquals(ToolResult.Status.DENIED, results(session).getFirst().status());
        assertEquals(List.of(new TextBlock("[HITL] 操作已被拒绝：keep this file")),
                results(session).getFirst().message().content());
        assertTrue(provider.requests().get(1).context().messages().contains(results(session).getFirst().message()));
        assertFalse(Files.exists(root.resolve("one")));
        assertInstanceOf(PermissionEvent.Requested.class, decisions.getFirst());
        assertEquals(ApprovalResult.Decision.REJECTED,
                ((PermissionEvent.Decided) decisions.getLast()).reply().decision());
    }

    @Test
    void cancellingAPendingApprovalDeniesEveryOutstandingCallAndEndsTheRunCancelled() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var permissions = new PermissionCapability((request, token) -> {
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            try (var ignored = token.onCancel(cancelled::countDown)) {
                entered.countDown();
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
                return ApprovalResult.approve(); // cancellation wins even if the adapter returns approval
            }
        }, true);
        AgentSession session = session(writes("one", "two", "three"), permissions);
        var decisions = new java.util.concurrent.CopyOnWriteArrayList<PermissionEvent>();
        permissions.subscribe(decisions::add);
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> run(session, "write"));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.cancel();
            assertInstanceOf(RunOutcome.Cancelled.class, run.get(2, TimeUnit.SECONDS));
            assertEquals(List.of(ToolResult.Status.DENIED, ToolResult.Status.DENIED, ToolResult.Status.DENIED),
                    results(session).stream().map(ToolResultEntry::status).toList());
            assertEquals(3, decisions.stream().filter(PermissionEvent.Decided.class::isInstance).count());
            assertFalse(Files.exists(root.resolve("one")));
            assertFalse(Files.exists(root.resolve("two")));
            assertFalse(Files.exists(root.resolve("three")));
        } finally { session.cancel(); }
    }

    @Test
    void auditRecordsExecutionAllowRefusalAndGuardDenialWithV1StableFields() throws Exception {
        var replies = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> replies.getAndIncrement() == 0
                ? ApprovalResult.approve() : request.call().id().equals("refused")
                ? ApprovalResult.reject("keep it") : ApprovalResult.approve(), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("allowed", "write_file", write("allowed", "yes"))
                .toolCall("refused", "write_file", write("refused", "no"))
                .toolCall("guarded", "write_file", write("../outside", "no")).build(), FakeResponse.text("done")));
        AgentSession session = session(provider, permissions, new AuditCapability(permissions, audit));

        run(session, "write");

        session.close();
        var entries = audit.readRecent(10);
        assertEquals(3, entries.size(), "approval and execution must not double-count allows");
        var allow = entries.stream().filter(e -> e.outcome().equals("allow")).findFirst().orElseThrow();
        assertEquals("write_file", allow.tool());
        assertEquals(write("allowed", "yes"), allow.args());
        assertEquals("none", allow.approver());
        assertNull(allow.reason());
        assertNull(allow.metadata());
        var denied = entries.stream().filter(e -> e.approver().equals("hitl")).findFirst().orElseThrow();
        assertEquals("deny", denied.outcome());
        assertEquals("write_file", denied.tool());
        assertEquals(write("refused", "no"), denied.args());
        assertEquals("keep it", denied.reason());
        assertNull(denied.metadata());
        var guarded = entries.stream().filter(e -> e.approver().equals("policy")).findFirst().orElseThrow();
        assertEquals("deny", guarded.outcome());
        assertEquals("write_file", guarded.tool());
        assertEquals(write("../outside", "no"), guarded.args());
        assertEquals("路径越界: ../outside 不在项目根 " + root.toRealPath() + " 之内", guarded.reason());
        assertNull(guarded.metadata());
        for (var entry : entries) {
            assertNotNull(java.time.Instant.parse(entry.timestamp()));
            assertTrue(entry.durationMs() >= 0);
        }
        assertFalse(Files.exists(root.resolve("refused")));
        assertFalse(Files.exists(root.getParent().resolve("outside")));
    }

    @Test
    void alwaysAllowIsRememberedByToolAcrossBatchesAndRunsButOtherToolsStillAsk() {
        var requested = new ArrayList<String>();
        var permissions = new PermissionCapability((request, token) -> {
            requested.add(request.call().name());
            return ApprovalResult.approveAll();
        }, true);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one", "write_file", write("one", "1"))
                .toolCall("two", "write_file", write("two", "2"))
                .toolCall("cmd", "execute_command", "{\"command\":\"exit 0\"}").build(),
                FakeResponse.text("done"),
                FakeResponse.builder().toolCall("three", "write_file", write("three", "3")).build(),
                FakeResponse.text("done")));
        AgentSession session = session(provider, permissions);

        run(session, "write");
        run(session, "write again");

        assertEquals(List.of("write_file", "execute_command"), requested);
        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK, ToolResult.Status.OK, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
    }

    @Test
    void alwaysAllowByMcpServerCoversItsOtherToolsButNeverAnotherServer() {
        var requested = new ArrayList<String>();
        var permissions = new PermissionCapability((request, token) -> {
            requested.add(request.call().name());
            return ApprovalResult.approveAllByServer();
        }, true);
        var executed = new java.util.concurrent.CopyOnWriteArrayList<String>();
        Capability mcp = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                for (String name : List.of("mcp__alpha__read", "mcp__alpha__write", "mcp__beta__read")) {
                    scope.tool(new LeafToolAdapter(new Tool(name, "fixture", new ObjectMapper().createObjectNode(),
                            input -> { executed.add(name); return ToolOutput.text(name); })));
                }
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one", "mcp__alpha__read", "{}")
                .toolCall("two", "mcp__alpha__write", "{}")
                .toolCall("three", "mcp__beta__read", "{}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, mcp, permissions);

        run(session, "use mcp");

        assertEquals(List.of("mcp__alpha__read", "mcp__beta__read"), requested);
        assertEquals(Set.of("mcp__alpha__read", "mcp__alpha__write", "mcp__beta__read"), Set.copyOf(executed));
    }

    @Test
    void disabledApprovalIsAutomaticAndReadOnlyCallsNeverAskWhenEnabled() throws Exception {
        Files.writeString(root.resolve("read"), "visible");
        var requested = new ArrayList<String>();
        var permissions = new PermissionCapability((request, token) -> {
            requested.add(request.call().id());
            return ApprovalResult.approve();
        }, false);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("auto", "write_file", write("auto", "yes")).build(), FakeResponse.text("done"),
                FakeResponse.builder().toolCall("read", "read_file", "{\"path\":\"read\"}")
                        .toolCall("asked", "write_file", write("asked", "yes")).build(), FakeResponse.text("done")));
        AgentSession session = session(provider, permissions);
        assertFalse(permissions.isEnabled());
        run(session, "automatic");
        assertTrue(requested.isEmpty());
        permissions.setEnabled(true);
        assertTrue(permissions.isEnabled());
        run(session, "enabled");
        assertEquals(List.of("asked"), requested);
        assertEquals("yes", Files.readString(root.resolve("auto")));
        assertEquals("yes", Files.readString(root.resolve("asked")));
    }

    @Test
    void skippedAndBlankRejectionsKeepV1TextAndAuditReasons() {
        var replies = new LinkedBlockingQueue<>(List.of(ApprovalResult.skip(), ApprovalResult.reject("  ")));
        var permissions = new PermissionCapability((request, token) -> replies.take(), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        var provider = writes("skip", "deny");
        AgentSession session = session(provider, permissions, new AuditCapability(permissions, audit));
        run(session, "no writes");
        session.close();
        assertEquals(List.of(new TextBlock("[HITL] 操作已被跳过")), results(session).get(0).message().content());
        assertEquals(List.of(new TextBlock("[HITL] 操作已被拒绝：用户拒绝了此操作")), results(session).get(1).message().content());
        assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.DENIED));
        assertEquals(List.of("用户跳过", "用户拒绝了此操作"), audit.readRecent(10).stream().map(AuditLog.AuditEntry::reason).toList());
        assertTrue(provider.requests().get(1).context().messages().containsAll(results(session).stream()
                .map(ToolResultEntry::message).toList()));
    }

    @Test
    void approvedEditsReachExecutionEventsCallbacksAndAuditWhileHistoryKeepsOriginalArguments() throws Exception {
        String original = write("original", "before");
        String modified = write("modified", "after");
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.modify(modified), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        var callsAfterApproval = new ArrayList<String>();
        Capability observer = new Capability() {
            @Override public void attachAgent(AgentScope agent) {
                agent.beforeToolCall((call, token) -> {
                    callsAfterApproval.add(call.arguments());
                    return ToolPreflight.allow(call);
                });
                agent.afterToolCall((call, result) -> {
                    callsAfterApproval.add(call.arguments());
                    return result;
                });
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("edit", "write_file", original).build(), FakeResponse.text("done")));
        AgentSession session = session(provider, permissions, observer, new AuditCapability(permissions, audit));
        var starts = new ArrayList<com.cafecli.ai.message.ToolCall>();
        Thread loop = Thread.currentThread();
        permissions.subscribe(event -> assertSame(loop, Thread.currentThread()));
        session.subscribe(event -> {
            assertSame(loop, Thread.currentThread());
            if (event instanceof AgentEvent.ToolStart start) starts.add(start.call());
        });

        run(session, "edit approval");
        session.close();

        assertEquals("after", Files.readString(root.resolve("modified")));
        assertFalse(Files.exists(root.resolve("original")));
        assertEquals(List.of(modified, modified), callsAfterApproval);
        assertEquals(List.of(new com.cafecli.ai.message.ToolCall("edit", "write_file", modified)), starts);
        assertEquals(modified, audit.readRecent(10).getFirst().args());
        assertEquals("allow", audit.readRecent(10).getFirst().outcome());
        AssistantMessage assistant = session.log().stream().filter(AssistantEntry.class::isInstance)
                .map(AssistantEntry.class::cast).map(AssistantEntry::message).filter(m -> !m.toolCalls().isEmpty()).findFirst().orElseThrow();
        assertEquals(original, assistant.toolCalls().getFirst().arguments());
        assertTrue(provider.requests().get(1).context().messages().contains(assistant));
        assertTrue(provider.requests().get(1).context().messages().contains(results(session).getFirst().message()));
        assertEquals("edit", results(session).getFirst().message().toolCallId());
        var codec = new AgentLogJson(Map.of());
        assertEquals(session.log(), codec.read(codec.write(session.log())));
    }

    @Test
    void guardsStillDenyAnApprovedEditToAnOutsidePathAndAuditTheEffectiveArguments() throws Exception {
        String modified = write("../" + root.getFileName() + "-victim", "escaped");
        Path victim = root.resolveSibling(root.getFileName() + "-victim");
        Files.writeString(victim, "keep these bytes");
        try {
            var permissions = new PermissionCapability((request, token) -> ApprovalResult.modify(modified), true);
            AuditLog audit = new AuditLog(root.resolve("audit"));
            AgentSession session = session(writes("inside"), permissions, new AuditCapability(permissions, audit));

            run(session, "write");
            session.close();

            assertEquals(ToolResult.Status.DENIED, results(session).getFirst().status());
            assertEquals("keep these bytes", Files.readString(victim));
            assertFalse(Files.exists(root.resolve("inside")));
            assertEquals(modified, audit.readRecent(10).getFirst().args());
            assertEquals("policy", audit.readRecent(10).getFirst().approver());
            assertEquals("deny", audit.readRecent(10).getFirst().outcome());
        } finally { Files.deleteIfExists(victim); }
    }

    @Test
    void disablingApprovalClearsAlwaysAllowGrantsBeforeItIsEnabledAgain() {
        var requested = new ArrayList<String>();
        var permissions = new PermissionCapability((request, token) -> {
            requested.add(request.call().id());
            return request.call().name().startsWith("mcp__")
                    ? ApprovalResult.approveAllByServer() : ApprovalResult.approveAll();
        }, true);
        Capability mcp = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new LeafToolAdapter(new Tool("mcp__alpha__read", "fixture", new ObjectMapper().createObjectNode(),
                        input -> ToolOutput.text("ok"))));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("first", "write_file", write("first", "1"))
                .toolCall("mcp-first", "mcp__alpha__read", "{}").build(), FakeResponse.text("done"),
                FakeResponse.builder().toolCall("auto", "write_file", write("auto", "2")).build(), FakeResponse.text("done"),
                FakeResponse.builder().toolCall("asked-again", "write_file", write("again", "3"))
                        .toolCall("mcp-again", "mcp__alpha__read", "{}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, mcp, permissions);
        run(session, "remember");
        permissions.setEnabled(false);
        run(session, "automatic");
        permissions.setEnabled(true);
        run(session, "ask again");
        assertEquals(List.of("first", "mcp-first", "asked-again", "mcp-again"), requested);
    }

    @Test
    void malformedRegisteredArgumentsAuditAnErrorButUnknownAndPreflightFailuresDoNot() {
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.approve(), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        Capability brokenPreflight = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> {
                    if (call.id().equals("preflight")) throw new IllegalStateException("before execution");
                    return ToolPreflight.allow(call);
                });
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("invalid", "write_file", "{oops")
                .toolCall("unknown", "mcp__absent__tool", "{oops")
                .toolCall("preflight", "write_file", write("no-execution", "no")).build(), FakeResponse.text("done")));
        AgentSession session = session(provider, permissions, brokenPreflight, new AuditCapability(permissions, audit));
        run(session, "failures");
        session.close();
        var entries = audit.readRecent(10);
        assertEquals(1, entries.size());
        var error = entries.getFirst();
        assertEquals("write_file", error.tool());
        assertEquals("{oops", error.args());
        assertEquals("error", error.outcome());
        assertEquals("none", error.approver());
        assertTrue(error.reason().contains("Unexpected character"), error.reason());
        assertNull(error.metadata());
        assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.ERROR));
        assertFalse(Files.exists(root.resolve("no-execution")));
    }

    @Test
    void executionBusinessErrorsStillAuditAllowButThrownExceptionsAuditError() {
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.approve(), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        Capability leaves = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new LeafToolAdapter(new Tool("mcp__fixture__business", "fixture", new ObjectMapper().createObjectNode(),
                        input -> ToolOutput.failure(ToolOutput.Status.ERROR, "business failure"))));
                scope.tool(new LeafToolAdapter(new Tool("mcp__fixture__throw", "fixture", new ObjectMapper().createObjectNode(),
                        input -> { throw new IllegalStateException("execution broke"); })));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("business", "mcp__fixture__business", "{}")
                .toolCall("throw", "mcp__fixture__throw", "{}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, leaves, permissions, new AuditCapability(permissions, audit));
        run(session, "fail");
        assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.ERROR));
        session.close();
        var entries = audit.readRecent(10);
        assertEquals(2, entries.size());
        var returned = entries.stream().filter(e -> e.tool().equals("mcp__fixture__business")).findFirst().orElseThrow();
        assertEquals("allow", returned.outcome());
        assertEquals("none", returned.approver());
        assertNull(returned.reason());
        var thrown = entries.stream().filter(e -> e.tool().equals("mcp__fixture__throw")).findFirst().orElseThrow();
        assertEquals("error", thrown.outcome());
        assertEquals("none", thrown.approver());
        assertEquals("execution broke", thrown.reason());
    }

    @Test
    void concurrentAgentsSerializeApprovalsAndCancellingAQueuedAgentLeavesTheActiveAgentAlone() throws Exception {
        var prompts = new LinkedBlockingQueue<String>();
        var release = new java.util.concurrent.CountDownLatch(1);
        var queued = new java.util.concurrent.CountDownLatch(1);
        var activeCount = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            assertEquals(1, activeCount.incrementAndGet(), "only one approval adapter invocation at a time");
            try {
                prompts.add(request.agentId());
                assertTrue(release.await(3, TimeUnit.SECONDS));
                assertFalse(token.isCancelled(), "another agent's cancellation must not reach this token");
                return ApprovalResult.approve();
            } finally { activeCount.decrementAndGet(); }
        }, true);
        Capability observer = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> {
                    if (scope.agentId().equals("queued")) queued.countDown();
                    return ToolPreflight.allow(call);
                });
            }
        };
        var provider = FakeProvider.responding(request -> request.context().messages().stream()
                .anyMatch(com.cafecli.ai.message.ToolResultMessage.class::isInstance)
                ? FakeResponse.text("done") : FakeResponse.builder().toolCall("same-id", "write_file", write(
                        ((TextBlock) ((com.cafecli.ai.message.UserMessage) request.context().messages().getFirst()).content().getFirst()).text(), "yes")).build());
        var capabilities = List.of(new WorkspaceCapability(root), observer, permissions);
        var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                root.resolve("user-prompts"), root.resolve("project-prompts"))), capabilities,
                new SessionEvents(), java.time.Duration.ofSeconds(90));
        var profile = AgentProfile.mainAgent(MODEL, capabilities, 5);
        var active = factory.create("active", profile);
        var waiting = factory.create("queued", profile);
        var activeToken = new com.cafecli.ai.CancellationToken();
        var waitingToken = new com.cafecli.ai.CancellationToken();
        var first = CompletableFuture.supplyAsync(() -> active.run(com.cafecli.ai.message.UserMessage.text("active-file"), activeToken));
        CompletableFuture<RunOutcome> second = null;
        try {
            assertEquals("active", prompts.poll(2, TimeUnit.SECONDS));
            second = CompletableFuture.supplyAsync(() -> waiting.run(com.cafecli.ai.message.UserMessage.text("queued-file"), waitingToken));
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            waitingToken.cancel();
            assertInstanceOf(RunOutcome.Cancelled.class, second.get(2, TimeUnit.SECONDS));
            assertEquals(ToolResult.Status.DENIED, waiting.log().stream().filter(ToolResultEntry.class::isInstance)
                    .map(ToolResultEntry.class::cast).findFirst().orElseThrow().status());
            assertTrue(prompts.isEmpty(), "queued cancellation must not open a second approval card");
            assertEquals(1, activeCount.get());
            assertFalse(first.isDone());
            release.countDown();
            assertInstanceOf(RunOutcome.Completed.class, first.get(2, TimeUnit.SECONDS));
            assertEquals("yes", Files.readString(root.resolve("active-file")));
            assertFalse(Files.exists(root.resolve("queued-file")));
        } finally {
            release.countDown();
            activeToken.cancel();
            waitingToken.cancel();
            first.get(3, TimeUnit.SECONDS);
            if (second != null) second.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void anEditedCallStillCarriesEffectiveArgumentsWhenALaterPreflightSuppliesItsResult() {
        String modified = write("modified", "after");
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.modify(modified), true);
        var completedCalls = new ArrayList<String>();
        Capability complete = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> ToolPreflight.complete(call, new ToolResult(
                        List.of(new TextBlock("supplied result")), null, ToolResult.Status.DENIED)));
                scope.afterToolCall((call, result) -> { completedCalls.add(call.arguments()); return result; });
            }
        };
        AgentSession session = session(writes("original"), permissions, complete);
        session.subscribe(event -> {
            if (event instanceof AgentEvent.ToolStart start) completedCalls.add(start.call().arguments());
        });
        run(session, "complete after edit");
        assertEquals(List.of(modified, modified), completedCalls);
        assertEquals(ToolResult.Status.DENIED, results(session).getFirst().status());
        assertFalse(Files.exists(root.resolve("modified")));
    }

    @Test
    void aSecondAgentsApprovalWaitsUntilTheFirstAdapterInvocationReturns() throws Exception {
        var prompts = new LinkedBlockingQueue<String>();
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondPreflight = new java.util.concurrent.CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            assertEquals(1, active.incrementAndGet(), "approval requests from agents must serialize");
            try {
                prompts.add(request.agentId());
                if (request.agentId().equals("first")) assertTrue(release.await(3, TimeUnit.SECONDS));
                return ApprovalResult.approve();
            } finally { active.decrementAndGet(); }
        }, true);
        Capability before = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> {
                    if (scope.agentId().equals("second")) secondPreflight.countDown();
                    return ToolPreflight.allow(call);
                });
            }
        };
        var provider = FakeProvider.responding(request -> request.context().messages().stream()
                .anyMatch(com.cafecli.ai.message.ToolResultMessage.class::isInstance)
                ? FakeResponse.text("done") : FakeResponse.builder().toolCall("shared-call-id", "write_file",
                        write(((TextBlock) ((com.cafecli.ai.message.UserMessage) request.context().messages().getFirst())
                                .content().getFirst()).text(), "yes")).build());
        AuditLog audit = new AuditLog(root.resolve("audit"));
        var auditCapability = new AuditCapability(permissions, audit);
        List<Capability> capabilities = List.of(new WorkspaceCapability(root), before, permissions, auditCapability);
        SessionEvents events = new SessionEvents();
        var registrar = new SessionContributions(events);
        capabilities.forEach(capability -> capability.attachSession(registrar));
        registrar.close();
        var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                root.resolve("user-prompts"), root.resolve("project-prompts"))), capabilities,
                events, java.time.Duration.ofSeconds(90));
        var profile = AgentProfile.mainAgent(MODEL, capabilities, 5);
        var first = factory.create("first", profile);
        var second = factory.create("second", profile);
        var firstToken = new com.cafecli.ai.CancellationToken();
        var secondToken = new com.cafecli.ai.CancellationToken();
        var firstRun = CompletableFuture.supplyAsync(() -> first.run(com.cafecli.ai.message.UserMessage.text("one"), firstToken));
        CompletableFuture<RunOutcome> secondRun = null;
        try {
            assertEquals("first", prompts.poll(2, TimeUnit.SECONDS));
            secondRun = CompletableFuture.supplyAsync(() -> second.run(com.cafecli.ai.message.UserMessage.text("two"), secondToken));
            assertTrue(secondPreflight.await(2, TimeUnit.SECONDS));
            assertNull(prompts.poll(200, TimeUnit.MILLISECONDS), "the active card must finish before the next card opens");
            release.countDown();
            assertEquals("second", prompts.poll(2, TimeUnit.SECONDS));
            assertInstanceOf(RunOutcome.Completed.class, firstRun.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RunOutcome.Completed.class, secondRun.get(2, TimeUnit.SECONDS));
            assertEquals("yes", Files.readString(root.resolve("one")));
            assertEquals("yes", Files.readString(root.resolve("two")));
            auditCapability.flush();
            assertEquals(Set.of(write("one", "yes"), write("two", "yes")),
                    Set.copyOf(audit.readRecent(10).stream().map(AuditLog.AuditEntry::args).toList()));
        } finally {
            release.countDown();
            firstToken.cancel();
            secondToken.cancel();
            firstRun.get(3, TimeUnit.SECONDS);
            if (secondRun != null) secondRun.get(3, TimeUnit.SECONDS);
            registrar.cleanup();
        }
    }

    @Test
    void aDisabledMutatingToolIsNotAuditedAsIfItExecuted() {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.edit_file.disabled", "true"),
                Map.of(), root.resolve("home"), root));
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.approve(), true);
        AuditLog audit = new AuditLog(root.resolve("audit"));
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("disabled", "edit_file", "{oops").build(), FakeResponse.text("done")));
        AgentSession session = AgentSession.builder().config(config).model(MODEL).provider(provider)
                .capabilities(List.of(new WorkspaceCapability(root, config.workspace()), permissions,
                        new AuditCapability(permissions, audit))).build();
        run(session, "disabled edit");
        session.close();
        assertEquals(ToolResult.Status.ERROR, results(session).getFirst().status());
        assertEquals(List.of(new TextBlock("未知工具: edit_file")), results(session).getFirst().message().content());
        assertTrue(audit.readRecent(10).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void anApprovalFromBeforeDisableCannotRestoreToolOrServerGrants(boolean server, boolean reenableBeforeReply) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            if (requests.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return server ? ApprovalResult.approveAllByServer() : ApprovalResult.approveAll();
            }
            return ApprovalResult.approve();
        }, true);
        Capability mcp = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                for (String name : List.of("mcp__alpha__first", "mcp__alpha__later")) {
                    scope.tool(new LeafToolAdapter(new Tool(name, "fixture", new ObjectMapper().createObjectNode(),
                            input -> ToolOutput.text("ok"))));
                }
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("first",
                        server ? "mcp__alpha__first" : "write_file", server ? "{}" : write("first", "1")).build(),
                FakeResponse.text("done"), FakeResponse.builder().toolCall("later",
                        server ? "mcp__alpha__later" : "write_file", server ? "{}" : write("later", "2")).build(),
                FakeResponse.text("done")));
        AgentSession session = session(provider, mcp, permissions);
        var run = CompletableFuture.supplyAsync(() -> run(session, "first"));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            permissions.setEnabled(false);
            if (reenableBeforeReply) permissions.setEnabled(true);
            release.countDown();
            assertInstanceOf(RunOutcome.Completed.class, run.get(2, TimeUnit.SECONDS));
            permissions.setEnabled(true);
            run(session, "later");
            assertEquals(2, requests.get(), "the old enabled period cannot grant future permission");
        } finally { release.countDown(); session.cancel(); run.get(3, TimeUnit.SECONDS); }
    }

    @Test
    void aBlockedAuditWriterCannotStallOtherApprovalsOrToolEndEvents() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AuditLog audit = new AuditLog(root.resolve("audit")) {
            @Override public void record(AuditEntry entry) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                super.record(entry);
            }
        };
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            if (approvals.incrementAndGet() == 1) return ApprovalResult.reject("keep first");
            assertTrue(entered.await(2, TimeUnit.SECONDS), "the audit write must actually be blocked");
            return ApprovalResult.approve();
        }, true);
        var auditCapability = new AuditCapability(permissions, audit);
        AgentSession session = session(writes("one", "two"), permissions, auditCapability);
        var ended = new java.util.concurrent.atomic.AtomicInteger();
        session.subscribe(event -> { if (event instanceof AgentEvent.ToolEnd) ended.incrementAndGet(); });
        var run = CompletableFuture.supplyAsync(() -> run(session, "write"));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertInstanceOf(RunOutcome.Completed.class, run.get(2, TimeUnit.SECONDS));
            assertEquals(2, approvals.get(), "another card must not wait for disk I/O");
            assertEquals(2, ended.get(), "ToolEnd delivery must not wait for disk I/O");
            assertEquals("two", Files.readString(root.resolve("two")));
        } finally { release.countDown(); session.cancel(); run.get(3, TimeUnit.SECONDS); }
    }

    @Test
    void aBlockedAuditWriterCannotDelayCancellationOfTheNextPendingApproval() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AuditLog audit = blockedAudit(writing, release);
        var pending = new java.util.concurrent.CountDownLatch(1);
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var permissions = new PermissionCapability((request, token) -> {
            if (approvals.incrementAndGet() == 1) return ApprovalResult.reject("first refused");
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            try (var ignored = token.onCancel(cancelled::countDown)) {
                pending.countDown();
                assertTrue(cancelled.await(3, TimeUnit.SECONDS));
                return ApprovalResult.approve();
            }
        }, true);
        AgentSession session = session(writes("one", "two"), permissions, new AuditCapability(permissions, audit));
        var run = CompletableFuture.supplyAsync(() -> run(session, "write"));
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            assertTrue(pending.await(2, TimeUnit.SECONDS));
            session.cancel();
            assertInstanceOf(RunOutcome.Cancelled.class, run.get(2, TimeUnit.SECONDS));
            assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.DENIED));
            assertEquals(2, results(session).size());
            assertTrue(audit.readRecent(10).isEmpty(), "disk remains blocked while cancellation completes");
        } finally { release.countDown(); session.cancel(); run.get(3, TimeUnit.SECONDS); session.close(); }
        assertEquals(2, audit.readRecent(10).size(), "close drains both denials after the run ends");
    }

    @Test
    void flushAndInterruptedSessionCloseDrainAllAcceptedAuditEntriesBeforeReturning() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AuditLog audit = blockedAudit(writing, release);
        var permissions = new PermissionCapability((request, token) -> ApprovalResult.reject("refused"), true);
        var auditCapability = new AuditCapability(permissions, audit);
        AgentSession session = session(writes("one", "two", "three"), permissions, auditCapability);
        assertInstanceOf(RunOutcome.Completed.class, run(session, "write"));
        assertTrue(writing.await(2, TimeUnit.SECONDS));
        java.time.Instant observedBeforeRelease = java.time.Instant.now();
        var flushing = CompletableFuture.runAsync(auditCapability::flush);
        var closing = CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().interrupt();
            try { session.close(); return Thread.currentThread().isInterrupted(); }
            finally { Thread.interrupted(); }
        });
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> flushing.get(100, TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        flushing.get(2, TimeUnit.SECONDS);
        assertTrue(closing.get(2, TimeUnit.SECONDS), "closing preserves the caller's interrupted status");
        assertEquals(3, audit.readRecent(10).size());
        assertTrue(audit.readRecent(10).stream().allMatch(entry ->
                !java.time.Instant.parse(entry.timestamp()).isAfter(observedBeforeRelease)),
                "audit timestamps belong to the observed decisions, not delayed disk writes");
        assertEquals(Set.of(write("one", "one"), write("two", "two"), write("three", "three")),
                Set.copyOf(audit.readRecent(10).stream().map(AuditLog.AuditEntry::args).toList()));
        session.close();
        auditCapability.close();
        auditCapability.flush();
        assertEquals(3, audit.readRecent(10).size(), "repeated close never duplicates or drops records");
        assertThrows(IllegalStateException.class, () -> run(session, "after close"));
    }

    private AuditLog blockedAudit(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) {
        return new AuditLog(root.resolve("audit")) {
            @Override public void record(AuditEntry entry) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                super.record(entry);
            }
        };
    }

    private FakeProvider writes(String... paths) {
        var batch = FakeResponse.builder();
        for (String path : paths) batch.toolCall(path, "write_file", write(path, path));
        return FakeProvider.scripted(List.of(batch.build(), FakeResponse.text("done")));
    }

    private static List<ToolResultEntry> results(AgentSession session) {
        return session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
    }

    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        var all = new ArrayList<Capability>();
        all.add(new WorkspaceCapability(root));
        all.addAll(List.of(capabilities));
        AgentSession session = AgentSession.builder().provider(provider).model(MODEL)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(all).build();
        sessions.add(session);
        return session;
    }

    private static String write(String path, String content) {
        return "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}";
    }
}
