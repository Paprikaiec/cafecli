package com.cafecli.harness;

import com.cafecli.ai.*;
import com.cafecli.ai.fake.*;
import com.cafecli.ai.message.*;
import com.cafecli.core.*;
import com.cafecli.core.log.*;
import com.cafecli.harness.config.*;
import com.cafecli.harness.tasks.SubAgentsCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.prompt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentsSessionTest {
    static final Model MAIN = new Model("main-model", "parent", "fake", 128000, 8192, Set.of());
    static final Model CHILD = new Model("child-model", "child", "fake", 32000, 4096, Set.of());
    @TempDir Path root;

    @Test void profilesChooseTheirOwnProviderPromptAndToolsAndParentReceivesOnlyFinalResult() {
        FakeProvider parent = FakeProvider.scripted(List.of(FakeResponse.text("previous parent answer"),
                spawn("private child instruction"), FakeResponse.text("parent final")));
        FakeProvider child = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("child reasoning")
                .toolCall("unknown", "read_file", "{\"path\":\"private.txt\"}").build(), FakeResponse.text("child final")));
        var config = config();
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var workspace = new WorkspaceCapability(config.projectRoot(), config.workspace());
        var profile = new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3, "Only the child prompt");
        var tasks = new SubAgentsCapability(factory, profile);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN)
                .capabilities(List.of(workspace, tasks)).build()) {
            session.subscribe(events::add);
            run(session, "parent secret");
            assertInstanceOf(RunOutcome.Completed.class, run(session, "delegate"));
            assertEquals(List.of(UserMessage.text("private child instruction")), child.requests().getFirst().context().messages());
            assertEquals(CHILD, child.requests().getFirst().model());
            assertEquals("Only the child prompt", child.requests().getFirst().context().systemPrompt());
            assertTrue(child.requests().stream().allMatch(request -> request.context().tools().isEmpty()));
            assertTrue(((ToolResultMessage) child.requests().get(1).context().messages().get(2)).isError());
            var parentResult = (ToolResultMessage) parent.requests().get(2).context().messages().get(4);
            assertEquals(List.of(new TextBlock("[task_1 · done]\n\nchild final")), parentResult.content());
            assertFalse(parent.requests().get(2).context().messages().toString().contains("child reasoning"));
            assertEquals(6, session.log().size());
            assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.MessageEnd end
                    && event.agentId().equals("task_1") && end.message().text().equals("child final")));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void cancellationWaitsForTheChildRunBeforeParentBecomesIdleAndRetainsItsResources() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tokenCancelled = new CountDownLatch(1);
        var childClosed = new CountDownLatch(1);
        var childFake = FakeProvider.responding(request -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return FakeResponse.text("late child");
        });
        Provider child = (model, context, options) -> {
            options.cancellation().onCancel(tokenCancelled::countDown);
            return childFake.stream(model, context, options);
        };
        var factory = factory(new RoutingProvider(Map.of("parent", FakeProvider.scripted(List.of(spawn("wait"))), "child", child)));
        Capability resource = new Capability() { @Override public void attachAgent(AgentScope scope) {
            scope.onClose(childClosed::countDown);
        }};
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(resource), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN)
                .capabilities(List.of(resource, tasks)).build()) {
            var run = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                session.cancel();
                assertTrue(tokenCancelled.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> run.get(150, TimeUnit.MILLISECONDS),
                        "parent must remain busy until the attached child actually stops");
            } finally { release.countDown(); }
            assertInstanceOf(RunOutcome.Cancelled.class, assertInstanceOf(SubmitResult.Completed.class,
                    run.get(5, TimeUnit.SECONDS)).outcome());
            assertEquals(1, childClosed.getCount(), "idle child resources survive cancellation until reaping or session close");
            assertTrue(session.takeFollowUpInputs().isEmpty());
        }
        assertTrue(childClosed.await(5, TimeUnit.SECONDS));
    }

    @Test void twoForegroundSpawnsOverlapAndKeepChildCapabilityStateIndependent() throws Exception {
        var both = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("a", "spawn_agent", "{\"prompt\":\"one\"}")
                .toolCall("b", "spawn_agent", "{\"prompt\":\"two\"}").build(), FakeResponse.text("parent")));
        var child = FakeProvider.responding(request -> {
            if (request.context().messages().size() == 1) {
                both.countDown(); awaitIgnoringInterrupts(release);
                return FakeResponse.builder().toolCall("count", "count", "{}").build();
            }
            assertEquals(List.of(new TextBlock("1")), ((ToolResultMessage) request.context().messages().get(2)).content());
            return FakeResponse.text(((TextBlock) ((UserMessage) request.context().messages().getFirst()).content().getFirst()).text());
        });
        Capability counter = new Capability() { @Override public void attachAgent(AgentScope scope) {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            scope.tool(new com.cafecli.harness.workspace.LeafToolAdapter(new com.cafecli.tool.Tool("count", "count",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    input -> com.cafecli.tool.ToolOutput.text(Integer.toString(count.incrementAndGet())))));
        }};
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(counter), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN)
                .capabilities(List.of(counter, tasks)).build()) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try { assertTrue(both.await(5, TimeUnit.SECONDS), "both child requests must arrive before either is released"); }
            finally { release.countDown(); }
            assertInstanceOf(RunOutcome.Completed.class, assertInstanceOf(SubmitResult.Completed.class,
                    running.get(5, TimeUnit.SECONDS)).outcome());
            var results = parent.requests().get(1).context().messages().stream().filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast).toList();
            assertEquals(List.of("a", "b"), results.stream().map(ToolResultMessage::toolCallId).toList());
            assertTrue(results.get(0).content().toString().contains("one"));
            assertTrue(results.get(1).content().toString().contains("two"));
            assertEquals(4, child.requests().size());
        }
    }

    @Test void childCancellationIsOneWayAndTheParentStillReceivesANormalReturnedToolResult() {
        var childFake = FakeProvider.scripted(List.of(FakeResponse.text("discarded")));
        Provider child = (model, context, options) -> {
            options.cancellation().cancel();
            return childFake.stream(model, context, options);
        };
        var parent = FakeProvider.scripted(List.of(spawn("cancel yourself"), FakeResponse.text("parent survives")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 2));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(tasks)).build()) {
            assertEquals("parent survives", assertInstanceOf(RunOutcome.Completed.class, run(session, "delegate")).message().text());
            assertEquals(ToolResult.Status.OK, session.log().stream().filter(ToolResultEntry.class::isInstance)
                    .map(ToolResultEntry.class::cast).findFirst().orElseThrow().status());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"emoji", "failure", "budget"})
    void taskPresentationPreservesLegacyBytesWhileOuterToolStatusStaysTyped(String scenario) {
        var child = switch (scenario) {
            case "emoji" -> FakeProvider.scripted(List.of(FakeResponse.text("  ❌ ordinary answer")));
            case "failure" -> FakeProvider.scripted(List.of(FakeResponse.builder().fail(ErrorKind.FATAL, "unavailable")));
            default -> FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("unknown", "unknown", "{}").build()));
        };
        var parent = FakeProvider.scripted(List.of(spawn("work"), FakeResponse.text("parent")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 1));
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(tasks)).build()) {
            session.subscribe(events::add);
            run(session, "delegate");
            var expected = switch (scenario) {
                case "emoji" -> "[task_1 · failed]\n\n  ❌ ordinary answer";
                case "failure" -> "[task_1 · failed]\n\n❌ 调用 LLM 失败: unavailable";
                default -> "[task_1 · failed]\n\n❌ 达到硬轮数上限（1），已强制收尾";
            };
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(List.of(new TextBlock(expected)), result.message().content());
            assertEquals(ToolResult.Status.OK, result.status());
            if (scenario.equals("emoji")) assertInstanceOf(RunOutcome.Completed.class, events.stream()
                    .filter(e -> e instanceof AgentEvent.RunEnd && e.agentId().equals("task_1"))
                    .map(AgentEvent.RunEnd.class::cast).findFirst().orElseThrow().outcome());
        }
    }

    @Test void failedChildAttachmentCleansAcquiredResourcesAndAnotherSpawnStillWorks() {
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var attached = new java.util.concurrent.atomic.AtomicInteger();
        Capability resource = new Capability() { @Override public void attachAgent(AgentScope scope) {
            if (!scope.agentId().equals("main")) {
                scope.onClose(closed::incrementAndGet);
                if (attached.incrementAndGet() == 1) throw new IllegalStateException("child attachment failed");
            }
        }};
        var parent = FakeProvider.scripted(List.of(spawn("first"), spawn("second"), FakeResponse.text("parent")));
        var child = FakeProvider.scripted(List.of(FakeResponse.text("child")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(resource), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(resource, tasks)).build()) {
            run(session, "delegate");
            var results = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
            assertEquals(List.of(new TextBlock("[task_1 · failed]\n\n子任务执行失败: child attachment failed")), results.getFirst().message().content());
            assertEquals(List.of(new TextBlock("[task_2 · done]\n\nchild")), results.getLast().message().content());
            assertEquals(1, closed.get(), "failed attachment closes immediately; successful child remains continuable");
            assertEquals(1, child.requests().size());
        }
        assertEquals(2, closed.get());
    }

    @Test void runFinallyAttemptsEveryParticipantAndSuppressesCleanupOntoTheOriginalFailure() {
        var primary = new IllegalArgumentException("subscriber failure");
        var cleanup = new IllegalStateException("cleanup failure");
        var last = new java.util.concurrent.atomic.AtomicInteger();
        Capability lifecycle = new Capability() { @Override public void attachAgent(AgentScope scope) {
            scope.runEnd(() -> { throw cleanup; });
            scope.runEnd(last::incrementAndGet);
        }};
        try (var session = AgentSession.builder().config(config()).provider(FakeProvider.scripted(List.of()))
                .model(MAIN).capabilities(List.of(lifecycle)).build()) {
            session.subscribe(event -> { if (event instanceof AgentEvent.RunStart) throw primary; });
            assertSame(primary, assertThrows(IllegalArgumentException.class, () -> session.submit("go")));
            assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
            assertEquals(1, last.get());
        }
    }

    @Test void lifecycleStartsBeforeAnEarlierRunStartWriterInvokesASpawnAsAuxiliaryWork() {
        Capability writer = new Capability() { @Override public void attachAgent(AgentScope scope) {
            scope.runStartWriter((input, token, invoker) -> {
                var result = invoker.invoke("spawn_agent", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("prompt", "child input"));
                return List.of(new com.cafecli.harness.context.Steering(((TextBlock) result.content().getFirst()).text()));
            });
        }};
        var parent = FakeProvider.scripted(List.of(FakeResponse.text("parent")));
        var child = FakeProvider.scripted(List.of(FakeResponse.text("child")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(writer, tasks)).build()) {
            run(session, "delegate");
            assertEquals(1, child.requests().size());
            assertTrue(parent.requests().getFirst().context().messages().toString().contains("[task_1 · done]\n\nchild"));
        }
    }

    @Test void childHooksKeepParentSessionIdentityAndIndependentLedgerAndFinishGate() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.hook.pre_tool_use.enabled", "true", "cafecli.hook.post_tool_use.enabled", "true",
                "cafecli.hook.stop.enabled", "true", "cafecli.hook.read_ledger.enabled", "true",
                "cafecli.hook.finish_gate.enabled", "true", "cafecli.hook.finish_gate.verify_regex", "true"),
                Map.of(), root.resolve("home"), root.resolve("project")));
        java.nio.file.Files.createDirectories(config.projectRoot());
        java.nio.file.Files.writeString(config.projectRoot().resolve("shared.txt"), "original");
        List<com.cafecli.hook.HookPayload> payloads = new CopyOnWriteArrayList<>();
        com.cafecli.hook.HookTenant observer = new com.cafecli.hook.HookTenant() {
            public String name() { return "observer"; }
            public com.cafecli.hook.HookDecision evaluate(com.cafecli.hook.HookPayload payload) {
                payloads.add(payload); return com.cafecli.hook.HookDecision.cont();
            }
        };
        var hooks = new com.cafecli.harness.hooks.HooksCapability(config.hooks(), List.of(
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.PRE_TOOL_USE, observer),
                new com.cafecli.harness.hooks.HooksCapability.Registration(com.cafecli.hook.HookEvent.POST_TOOL_USE, observer)), null);
        var workspace = new WorkspaceCapability(config.projectRoot(), config.workspace());
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("read", "read_file", "{\"path\":\"shared.txt\"}").build(),
                FakeResponse.builder().toolCall("blind", "spawn_agent", "{\"prompt\":\"blind\"}")
                        .toolCall("writer", "spawn_agent", "{\"prompt\":\"writer\"}").build(), FakeResponse.text("parent")));
        var child = FakeProvider.responding(request -> {
            var messages = request.context().messages();
            String prompt = ((TextBlock) ((UserMessage) messages.getFirst()).content().getFirst()).text();
            int tools = (int) messages.stream().filter(ToolResultMessage.class::isInstance).count();
            if (prompt.equals("blind")) {
                if (tools == 0) return FakeResponse.builder().toolCall("write", "write_file", "{\"path\":\"shared.txt\",\"content\":\"bad\"}").build();
                if (tools == 1) return FakeResponse.builder().toolCall("read", "read_file", "{\"path\":\"shared.txt\"}").build();
                return FakeResponse.text("blind blocked");
            }
            if (tools == 0) return FakeResponse.builder().toolCall("write", "write_file", "{\"path\":\"new.txt\",\"content\":\"written\"}").build();
            if (tools == 1 && messages.stream().noneMatch(m -> m.toString().contains("[收尾门 / Finish Gate]"))) return FakeResponse.text("premature");
            if (tools == 1) return FakeResponse.builder().toolCall("verify", "execute_command", "{\"command\":\"true\"}").build();
            return FakeResponse.text("verified child");
        });
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(workspace, hooks), 8));
        try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN).capabilities(List.of(workspace, hooks, tasks)).build()) {
            run(session, "delegate");
            assertEquals("original", java.nio.file.Files.readString(config.projectRoot().resolve("shared.txt")));
            assertEquals("written", java.nio.file.Files.readString(config.projectRoot().resolve("new.txt")));
            assertEquals(3, parent.requests().size(), "child write must not trigger the parent's Finish Gate");
            assertTrue(child.requests().stream().anyMatch(r -> r.context().messages().toString().contains("[Hook 拦截 / PreToolUse]")));
            assertTrue(child.requests().stream().anyMatch(r -> r.context().messages().toString().contains("[收尾门 / Finish Gate]")));
            assertEquals(1, payloads.stream().map(com.cafecli.hook.HookPayload::sessionId).distinct().count());
            assertTrue(payloads.getFirst().sessionId().startsWith("session-"));
            for (String task : List.of("task_1", "task_2")) {
                assertTrue(payloads.stream().anyMatch(p -> task.equals(p.taskId()) && p.event() == com.cafecli.hook.HookEvent.PRE_TOOL_USE));
            }
            assertTrue(payloads.stream().anyMatch(p -> p.taskId() != null && p.event() == com.cafecli.hook.HookEvent.POST_TOOL_USE));
        }
    }

    @Test void blankFinalAnswerNeverReturnsIntermediateToolNarration() {
        var parent = FakeProvider.scripted(List.of(spawn("work"), FakeResponse.text("parent")));
        var child = FakeProvider.scripted(List.of(FakeResponse.builder().text("reading...")
                .toolCall("read", "unknown", "{}").build(), FakeResponse.text("")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(tasks)).build()) {
            run(session, "delegate");
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(List.of(new TextBlock("[task_1 · done]\n\n")), result.message().content());
        }
    }

    @Test void aFactoryCannotBeReopenedAndRejectionDoesNotTouchTheLiveSessionsResources() {
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        Capability resource = new Capability() { @Override public void attachSession(SessionRegistrar session) {
            opened.incrementAndGet(); session.onClose(closed::incrementAndGet);
        }};
        var provider = FakeProvider.responding(request -> FakeResponse.text("usable"));
        var factory = factory(provider);
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(resource)).build()) {
            assertThrows(IllegalStateException.class, () -> AgentSession.builder().config(config()).factory(factory)
                    .model(MAIN).capabilities(List.of(resource)).build());
            assertEquals(1, opened.get(), "reject before another session hook changes shared capability resources");
            assertEquals(0, closed.get());
            assertEquals("usable", assertInstanceOf(RunOutcome.Completed.class, run(session, "still open")).message().text());
        }
        assertEquals(1, closed.get());
    }

    @Test void childPreparesImagesAndSkillsLocallyAndParentPendingBodiesSurviveUnchanged() throws Exception {
        var config = config();
        java.nio.file.Files.createDirectories(config.projectRoot());
        Path image = config.projectRoot().resolve("shot.png");
        java.nio.file.Files.write(image, new byte[]{1, 2, 3});
        for (String name : List.of("apricot", "berry")) {
            Path skill = config.homes().project().resolve("skills").resolve(name).resolve("SKILL.md");
            java.nio.file.Files.createDirectories(skill.getParent());
            java.nio.file.Files.writeString(skill, "---\nname: " + name + "\ndescription: Test " + name + "\n---\nUse " + name + " rules.\n");
        }
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                spawn("@image:shot.png"), FakeResponse.text("parent"), FakeResponse.text("used"), FakeResponse.text("later")));
        var child = FakeProvider.responding(request -> {
            if (request.context().messages().size() == 1) {
                try { java.nio.file.Files.delete(image); } catch (Exception e) { throw new RuntimeException(e); }
                return FakeResponse.builder().toolCall("load", "load_skill", "{\"name\":\"berry\"}").build();
            }
            assertEquals(new ImageBlock("AQID", "image/png"), ((UserMessage) request.context().messages().getFirst()).content().getLast());
            return FakeResponse.text("child");
        });
        var skills = new com.cafecli.harness.skills.SkillsCapability(config.skills());
        var images = new com.cafecli.harness.images.ImagesCapability(config.projectRoot());
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(skills, images), 3));
        try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN).capabilities(List.of(skills, images, tasks)).build()) {
            run(session, "load and delegate");
            assertEquals(new ImageBlock("AQID", "image/png"), ((UserMessage) child.requests().getFirst().context().messages().getFirst()).content().getLast());
            assertFalse(child.requests().getFirst().context().messages().toString().contains("apricot"));
            session.submit("apply it");
            assertEquals(UserMessage.text("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\napply it"),
                    parent.requests().get(3).context().messages().getLast());
            assertFalse(parent.requests().get(3).context().messages().toString().contains("Use berry rules."));
            var body = session.log().stream().filter(com.cafecli.harness.skills.SkillBody.class::isInstance).toList();
            assertEquals(1, body.size());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
            session.submit("later");
            assertEquals(UserMessage.text("later"), parent.requests().get(4).context().messages().getLast());
        }
    }

    @Test void anUnsupportedChildProviderFailsWithoutSendingItsModelToTheParentAdapter() {
        var parent = FakeProvider.scripted(List.of(spawn("work"), FakeResponse.text("parent")));
        var factory = factory(new RoutingProvider(Map.of("parent", parent)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(tasks)).build()) {
            run(session, "delegate");
            assertEquals(2, parent.requests().size());
            assertTrue(parent.requests().stream().allMatch(request -> request.model().provider().equals("parent")));
            var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(List.of(new TextBlock("[task_1 · failed]\n\n❌ 调用 LLM 失败: No adapter configured for provider: child")), result.message().content());
        }
    }

    @Test void anAbandonedSpawnWorkerCannotEnterTheNextRunWithItsOldToken() throws Exception {
        var oldEntered = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var oldReturned = new CountDownLatch(1);
        var newEntered = new CountDownLatch(1);
        var releaseNew = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Capability delay = new Capability() { @Override public void attachAgent(AgentScope scope) {
            scope.toolSnapshotStage(tools -> tools.stream().map(tool -> {
                if (!tool.name().equals("spawn_agent")) return tool;
                return new AgentTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return tool.parameters(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input, CancellationToken token,
                            java.util.function.Consumer<ToolResult> progress) throws Exception {
                        if (calls.incrementAndGet() != 1) return tool.execute(id, input, token, progress);
                        oldEntered.countDown(); awaitIgnoringInterrupts(releaseOld);
                        try { return tool.execute(id, input, token, progress); }
                        finally { oldReturned.countDown(); }
                    }
                };
            }).toList());
        }};
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("old", "spawn_agent", "{\"prompt\":\"old\"}").toolCall("other", "unknown", "{}").build(),
                FakeResponse.text("old parent"), spawn("new"), FakeResponse.text("new parent")));
        var child = FakeProvider.responding(request -> {
            newEntered.countDown(); awaitIgnoringInterrupts(releaseNew); return FakeResponse.text("new child");
        });
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(delay, tasks)).build()) {
            var first = new CompletableFuture<SubmitResult>();
            Thread owner = Thread.ofVirtual().start(() -> {
                try { first.complete(session.submit("first")); } catch (Throwable e) { first.completeExceptionally(e); }
            });
            try {
                assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
                owner.interrupt();
                assertInstanceOf(SubmitResult.Completed.class, first.get(5, TimeUnit.SECONDS));
                var next = CompletableFuture.supplyAsync(() -> session.submit("second"));
                try {
                    assertTrue(newEntered.await(5, TimeUnit.SECONDS));
                    releaseOld.countDown();
                    assertTrue(oldReturned.await(5, TimeUnit.SECONDS));
                    assertEquals(1, child.requests().size(), "old token must not attach to the current generation");
                    assertEquals(List.of(UserMessage.text("new")), child.requests().getFirst().context().messages());
                } finally { releaseNew.countDown(); }
                assertInstanceOf(SubmitResult.Completed.class, next.get(5, TimeUnit.SECONDS));
            } finally { releaseOld.countDown(); releaseNew.countDown(); }
        }
    }

    @Test void aThrowingChildCancellationListenerCannotPreventInterruptionAndCleanup() throws Exception {
        var entered = new CountDownLatch(1);
        var childClosed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancellationFailure = new IllegalStateException("child cancel listener failed");
        var parentFailure = new IllegalArgumentException("parent subscriber failed");
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("spawn", "spawn_agent", "{\"prompt\":\"wait\"}")
                .toolCall("signal", "signal", "{}").build()));
        Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), new AssistantStream.Source() {
            public boolean pull(AssistantStream.Emitter out) throws Exception {
                options.cancellation().onCancel(() -> { throw cancellationFailure; });
                entered.countDown();
                release.await();
                out.textDelta("released"); out.done(StopReason.END_TURN); return false;
            }
            public void close() { childClosed.countDown(); }
        });
        Capability signal = new Capability() { @Override public void attachAgent(AgentScope scope) {
            // A delegating capability may own its worker. Batch waiter interruption cannot stand in for
            // the parent lifecycle's obligation to stop and join its admitted foreground child.
            scope.toolSnapshotStage(tools -> tools.stream().map(tool -> {
                if (!tool.name().equals("spawn_agent")) return tool;
                return new AgentTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return tool.parameters(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input, CancellationToken token,
                            java.util.function.Consumer<ToolResult> progress) {
                        var result = new CompletableFuture<ToolResult>();
                        Thread.ofVirtual().start(() -> {
                            try { result.complete(tool.execute(id, input, token, progress)); }
                            catch (Throwable failure) { result.completeExceptionally(failure); }
                        });
                        return result.join();
                    }
                };
            }).toList());
            scope.tool(new AgentTool() {
                public String name() { return "signal"; }
                public String description() { return "signal"; }
                public com.fasterxml.jackson.databind.JsonNode parameters() { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }
                public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input, CancellationToken token,
                        java.util.function.Consumer<ToolResult> progress) throws Exception {
                    if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("child never entered");
                    return new ToolResult(List.of(), null, ToolResult.Status.OK);
                }
            });
        }};
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(signal, tasks)).build();
        session.subscribe(event -> {
            if (event instanceof AgentEvent.ToolEnd end && end.result().message().toolName().equals("signal")) throw parentFailure;
        });
        var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
        try {
            var thrown = assertThrows(ExecutionException.class, () -> running.get(5, TimeUnit.SECONDS));
            assertSame(parentFailure, thrown.getCause());
            assertTrue(childClosed.await(5, TimeUnit.SECONDS), "throwing cancellation must still interrupt the child");
            assertTrue(Arrays.asList(parentFailure.getSuppressed()).contains(cancellationFailure), "preserve the original failure and cleanup failure");
        } finally { release.countDown(); session.close(); }
    }

    @Test void anInterruptedSpawnWaiterMustJoinItsChildEvenWhenCancellationThrows() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var parentContinued = new CountDownLatch(1);
        var waiter = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var parent = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 1) return spawn("wait");
            parentContinued.countDown();
            return FakeResponse.text("parent conclusion");
        });
        Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), out -> {
            options.cancellation().onCancel(() -> {
                cancelled.countDown();
                throw new IllegalStateException("child cancel listener failed");
            });
            entered.countDown();
            awaitIgnoringInterrupts(release);
            out.done(StopReason.END_TURN);
            return false;
        });
        Capability capture = new Capability() { @Override public void attachAgent(AgentScope scope) {
            scope.toolSnapshotStage(tools -> tools.stream().map(tool -> {
                if (!tool.name().equals("spawn_agent")) return tool;
                return new AgentTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return tool.parameters(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input, CancellationToken token,
                            java.util.function.Consumer<ToolResult> progress) throws Exception {
                        waiter.set(Thread.currentThread());
                        return tool.execute(id, input, token, progress);
                    }
                };
            }).toList());
        }};
        var factory = factory(new RoutingProvider(Map.of("parent", parent, "child", child)));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(), 3));
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MAIN).capabilities(List.of(capture, tasks)).build()) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                waiter.get().interrupt();
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertFalse(parentContinued.await(150, TimeUnit.MILLISECONDS), "foreground result cannot publish while its child still executes");
                assertFalse(running.isDone());
            } finally { release.countDown(); }
            assertInstanceOf(SubmitResult.Completed.class, running.get(5, TimeUnit.SECONDS));
            assertEquals(2, parent.requests().size());
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private HarnessConfig config() {
        return ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root.resolve("project")));
    }
    private AgentFactory factory(Provider provider) {
        return new AgentFactory(provider, new PromptAssembler(new PromptRepository(root.resolve("user-prompts"),
                root.resolve("project-prompts"))), java.time.Duration.ofSeconds(90));
    }
    private static FakeResponse spawn(String prompt) {
        return FakeResponse.builder().toolCall("spawn", "spawn_agent", "{\"description\":\"child\",\"prompt\":\"" + prompt + "\"}").build();
    }
}
