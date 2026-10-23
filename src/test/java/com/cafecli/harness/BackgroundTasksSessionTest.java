package com.cafecli.harness;

import com.cafecli.ai.*;
import com.cafecli.ai.fake.*;
import com.cafecli.ai.message.*;
import com.cafecli.core.*;
import com.cafecli.core.log.*;
import com.cafecli.harness.config.*;
import com.cafecli.harness.tasks.*;
import com.cafecli.prompt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundTasksSessionTest {
    static final Model MAIN = new Model("main", "parent", "fake", 128000, 8192, Set.of());
    static final Model CHILD = new Model("child", "child", "fake", 32000, 4096, Set.of());
    @TempDir Path root;

    @Test void backgroundReturnsBeforeChildFinishesAndCompletionEntersTheNextBoundary() throws Exception {
        var childEntered = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var parentContinued = new CountDownLatch(1);
        var releaseParent = new CountDownLatch(1);
        var childThread = new AtomicReference<Thread>();
        var calls = new AtomicInteger();
        var parent = FakeProvider.responding(request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return spawn("private work", true);
            if (call == 2) {
                parentContinued.countDown(); await(releaseParent);
                return FakeResponse.builder().toolCall("tick", "unknown", "{}").build();
            }
            assertTrue(request.context().messages().contains(UserMessage.text(
                    "<task-notification task_id=\"task_1\">\n[task_1 · done]\n\nchild result\n</task-notification>")));
            return FakeResponse.text("parent result");
        });
        var child = FakeProvider.responding(request -> {
            childThread.set(Thread.currentThread()); childEntered.countDown(); await(releaseChild);
            return FakeResponse.text("child result");
        });
        try (var session = session(parent, child, true, 120000, 8, 50, List.of())) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try {
                assertTrue(childEntered.await(5, TimeUnit.SECONDS));
                assertTrue(parentContinued.await(1, TimeUnit.SECONDS), "background must return while child remains running");
                assertFalse(running.isDone());
                releaseChild.countDown();
                childThread.get().join(5000);
                assertFalse(childThread.get().isAlive());
                releaseParent.countDown();
                assertInstanceOf(SubmitResult.Completed.class, running.get(5, TimeUnit.SECONDS));
                assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
                assertEquals(3, parent.requests().size());
                assertTrue(parent.requests().get(1).context().messages().toString().contains("已在后台启动"));
            } finally { releaseChild.countDown(); releaseParent.countDown(); }
        }
    }

    @Test void idleTasksContinueWithHistoryAndBoundedOutputUntilReaped() {
        var parent = FakeProvider.scripted(List.of(spawn("first instruction", false), FakeResponse.text("one"),
                tool("send_message", "{\"to\":\"task_1\",\"message\":\"follow up\"}"), FakeResponse.text("two"),
                tool("task_output", "{\"task_id\":\"task_1\"}"), FakeResponse.text("three"),
                spawn("replacement", false), FakeResponse.text("four"),
                tool("send_message", "{\"to\":\"task_1\",\"message\":\"too late\"}"), FakeResponse.text("five"),
                tool("task_output", "{\"task_id\":\"task_1\"}"), FakeResponse.text("six")));
        var child = FakeProvider.scripted(List.of(FakeResponse.text("first answer"), FakeResponse.text("second answer"),
                FakeResponse.text("replacement answer")));
        var closed = new AtomicInteger();
        Capability resource = new Capability() { public void attachAgent(AgentScope scope) {
            if (!scope.agentId().equals("main")) scope.onClose(closed::incrementAndGet);
        }};
        try (var session = session(parent, child, false, 120000, 1, 3, List.of(resource))) {
            run(session, "spawn");
            assertEquals(0, closed.get(), "idle task resources stay live for continuation");
            run(session, "continue");
            assertEquals(2, child.requests().size(), "send_message must run the retained child again");
            assertEquals(UserMessage.text("first instruction"), child.requests().get(1).context().messages().getFirst());
            assertEquals(UserMessage.text("follow up"), child.requests().get(1).context().messages().getLast());
            assertEquals("first answer", ((AssistantMessage) child.requests().get(1).context().messages().get(1)).text());
            run(session, "inspect");
            assertEquals("[task_1 · 完整轨迹]\nassistant: first answer\nuser: follow up\nassistant: second answer", lastToolText(session));
            run(session, "replace");
            assertEquals(1, closed.get(), "reaping closes the idle agent and owned resources");
            run(session, "continue expired");
            assertEquals("任务 task_1 已过期或不存在，请用 spawn_agent 重开。", lastToolText(session));
            run(session, "inspect expired");
            assertEquals("任务 task_1 已过期或不存在，请用 spawn_agent 重开。", lastToolText(session));
            assertEquals(3, child.requests().size());
        }
        assertEquals(2, closed.get());
    }

    @Test void parentCancellationStopsAttachedAndPreservesDetachedUntilExplicitCancel() throws Exception {
        var attachedEntered = new CountDownLatch(1);
        var detachedEntered = new CountDownLatch(1);
        var attachedCancelled = new CountDownLatch(1);
        var detachedCancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), out -> {
            boolean attached = ((UserMessage) context.messages().getFirst()).content().toString().contains("attached");
            options.cancellation().onCancel(() -> { if (attached) attachedCancelled.countDown(); else detachedCancelled.countDown(); });
            (attached ? attachedEntered : detachedEntered).countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            out.done(StopReason.END_TURN); return false;
        });
        var parent = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("detached", "spawn_agent", "{\"prompt\":\"background\",\"background\":true}")
                .toolCall("attached", "spawn_agent", "{\"prompt\":\"attached\"}").build()));
        try (var session = session(parent, child, true, 120000, 8, 50, List.of())) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try {
                assertTrue(attachedEntered.await(5, TimeUnit.SECONDS));
                assertTrue(detachedEntered.await(5, TimeUnit.SECONDS));
                session.cancel();
                assertInstanceOf(RunOutcome.Cancelled.class, assertInstanceOf(SubmitResult.Completed.class,
                        running.get(5, TimeUnit.SECONDS)).outcome());
                assertEquals(0, attachedCancelled.getCount());
                assertEquals(1, detachedCancelled.getCount(), "parent cancellation must preserve Detached work");
                String listing = session.dispatch("/task list").orElseThrow();
                String detachedId = listing.lines().filter(line -> line.contains("[运行中]")).findFirst().orElseThrow().trim().split(" +")[0];
                assertEquals("已请求取消任务 " + detachedId + "（在回合边界停止）。", session.dispatch("/task cancel " + detachedId).orElseThrow());
                assertTrue(detachedCancelled.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void interactiveSlowForegroundDetachesButHeadlessForegroundAndBackgroundStaySynchronous() throws Exception {
        for (boolean interactive : List.of(true, false)) for (boolean requestedBackground : List.of(false, true)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), out -> {
                options.cancellation().onCancel(() -> cancelled.set(true));
                entered.countDown(); release.await(); out.textDelta("finished"); out.done(StopReason.END_TURN); return false;
            });
            var parent = FakeProvider.scripted(List.of(spawn("slow", requestedBackground), FakeResponse.text("parent")));
            try (var session = session(parent, child, interactive, 25, 8, 50, List.of())) {
                var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    if (interactive) {
                        assertInstanceOf(SubmitResult.Completed.class, running.get(1, TimeUnit.SECONDS), "interactive foreground must auto-background");
                        assertTrue(lastToolText(session).contains(requestedBackground ? "已在后台启动" : "25ms 已自动转入后台"));
                        session.cancel(); assertFalse(cancelled.get());
                    } else assertThrows(TimeoutException.class, () -> running.get(100, TimeUnit.MILLISECONDS), "headless always waits");
                } finally { release.countDown(); }
                assertInstanceOf(SubmitResult.Completed.class, running.get(5, TimeUnit.SECONDS));
                if (!interactive) assertEquals("[task_1 · done]\n\nfinished", lastToolText(session));
            }
        }
    }

    @Test void admissionQueuesExcessTasksAndCancellingAQueuedTaskNeverStartsItsAgent() throws Exception {
        var entered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var child = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 2) secondEntered.countDown();
            entered.countDown(); await(release); return FakeResponse.text("finished");
        });
        var parent = FakeProvider.scripted(List.of(spawn("first", true), spawn("second", true), FakeResponse.text("parent")));
        try (var session = session(parent, child, new TaskConfig(true, 120000, 1, 5, 8, 50), List.of())) {
            run(session, "queue two");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS), "admission must bound simultaneously running agents");
            assertEquals(1, calls.get());
            assertEquals("已请求取消任务 task_2（在回合边界停止）。", session.dispatch("/task cancel task_2").orElseThrow());
            release.countDown();
        } finally { release.countDown(); }
        assertEquals(1, calls.get(), "cancelled admission wait must not create or run a child");
    }

    @Test void clearRoutesCompletionToTheInstalledMainAndFailedClearKeepsTheOldInbox() throws Exception {
        for (boolean fail : List.of(false, true)) {
            var entered = new CountDownLatch(1);
            var releaseChild = new CountDownLatch(1);
            var attaching = new CountDownLatch(1);
            var releaseAttach = new CountDownLatch(1);
            var worker = new AtomicReference<Thread>();
            var attachments = new AtomicInteger();
            Capability late = new Capability() { public void attachAgent(AgentScope scope) {
                if (!scope.agentId().equals("main") || attachments.incrementAndGet() != 2) return;
                attaching.countDown(); await(releaseAttach);
                if (fail) throw new IllegalArgumentException("replacement failed");
            }};
            var parent = FakeProvider.scripted(List.of(spawn("background", true), FakeResponse.text("first"), FakeResponse.text("after clear")));
            var child = FakeProvider.responding(request -> {
                worker.set(Thread.currentThread()); entered.countDown(); await(releaseChild); return FakeResponse.text("completed across clear");
            });
            try (var session = session(parent, child, new TaskConfig(true, 120000, 8, 5, 8, 50), List.of(), List.of(late))) {
                run(session, "spawn"); assertTrue(entered.await(5, TimeUnit.SECONDS));
                var clearing = CompletableFuture.runAsync(session::clear);
                try {
                    assertTrue(attaching.await(5, TimeUnit.SECONDS));
                    releaseChild.countDown(); worker.get().join(5000); assertFalse(worker.get().isAlive());
                    releaseAttach.countDown();
                    if (fail) assertInstanceOf(IllegalArgumentException.class,
                            assertThrows(ExecutionException.class, () -> clearing.get(5, TimeUnit.SECONDS)).getCause());
                    else clearing.get(5, TimeUnit.SECONDS);
                    run(session, "next");
                    var messages = parent.requests().getLast().context().messages();
                    assertEquals(1, messages.stream().filter(message -> message instanceof UserMessage user
                            && user.content().toString().contains("<task-notification task_id=\"task_1\">\n[task_1 · done]\n\ncompleted across clear\n</task-notification>")).count(),
                            "completion must survive provisional replacement without duplicate delivery");
                } finally { releaseChild.countDown(); releaseAttach.countDown(); }
            }
        }
    }

    @Test void runEndEventDoesNotPublishIdleUntilChildFinallyFinishes() throws Exception {
        var inFinally = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        Capability lifecycle = new Capability() { public void attachAgent(AgentScope scope) {
            if (!scope.agentId().equals("main")) scope.runEnd(() -> {
                worker.set(Thread.currentThread()); inFinally.countDown(); await(releaseFinally);
            });
        }};
        var parent = FakeProvider.scripted(List.of(spawn("work", true), FakeResponse.text("parent"),
                tool("task_output", "{\"task_id\":\"task_1\"}"), FakeResponse.text("inspected"),
                tool("send_message", "{\"to\":\"task_1\",\"message\":\"continue\"}"), FakeResponse.text("still running"),
                FakeResponse.text("notified")));
        var child = FakeProvider.scripted(List.of(FakeResponse.text("child")));
        try (var session = session(parent, child, true, 120000, 8, 50, List.of(lifecycle))) {
            try {
                run(session, "spawn"); assertTrue(inFinally.await(5, TimeUnit.SECONDS));
                assertTrue(session.dispatch("/task").orElseThrow().contains("[运行中]"));
                run(session, "inspect");
                assertEquals("任务 task_1 已过期或不存在，请用 spawn_agent 重开。", lastToolText(session));
                run(session, "continue");
                assertEquals("任务 task_1 仍在运行，请等待完成后再用 send_message 续派。", lastToolText(session));
                assertFalse(parent.requests().getLast().context().messages().toString().contains("<task-notification"));
            } finally { releaseFinally.countDown(); }
            worker.get().join(5000); assertFalse(worker.get().isAlive());
            run(session, "next");
            assertEquals(1, session.log().stream().filter(TaskNotification.class::isInstance).count());
        }
    }

    @Test void foregroundNestedTasksReleaseTheParentsPermitAndDepthIsStillRejected() throws Exception {
        for (int depth : List.of(2, 1)) {
            var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root.resolve("project")));
            var parent = FakeProvider.scripted(List.of(spawn("nested", false), FakeResponse.text("parent")));
            var child = FakeProvider.responding(request -> {
                String input = ((TextBlock) ((UserMessage) request.context().messages().getFirst()).content().getFirst()).text();
                if (input.equals("leaf")) return FakeResponse.text("leaf answer");
                if (request.context().messages().size() == 1) return spawn("leaf", false);
                String result = ((ToolResultMessage) request.context().messages().getLast()).content().toString();
                assertTrue(result.contains(depth == 2 ? "leaf answer" : "派生深度 2 超过上限 1"));
                return FakeResponse.text("nested answer");
            });
            var factory = new AgentFactory(new RoutingProvider(Map.of("parent", parent, "child", child)),
                    new PromptAssembler(new PromptRepository(root.resolve("up"), root.resolve("pp"))), java.time.Duration.ofSeconds(90));
            var taskRef = new AtomicReference<SubAgentsCapability>();
            Capability nested = new Capability() { public void attachAgent(AgentScope scope) {
                if (!scope.agentId().equals("main")) taskRef.get().attachAgent(scope);
            }};
            var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(nested), 10),
                    new TaskConfig(false, 120000, 1, depth, 8, 50));
            taskRef.set(tasks);
            try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN).capabilities(List.of(nested, tasks)).build()) {
                var running = CompletableFuture.supplyAsync(() -> session.submit("nested work"));
                try {
                    assertInstanceOf(SubmitResult.Completed.class, running.get(1, TimeUnit.SECONDS), "a parent waiting for its child cannot consume the only permit");
                    assertEquals("[task_1 · done]\n\nnested answer", lastToolText(session));
                } finally { session.cancel(); running.get(5, TimeUnit.SECONDS); }
            }
        }
    }

    @Test void taskCommandsCanInspectAndCancelDetachedWorkWhileTheParentKeepsRunning() throws Exception {
        var childEntered = new CountDownLatch(1);
        var parentEntered = new CountDownLatch(1);
        var childCancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var parent = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 1) return spawn("background", true);
            parentEntered.countDown(); await(release); return FakeResponse.text("parent finished");
        });
        Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), out -> {
            options.cancellation().onCancel(childCancelled::countDown);
            childEntered.countDown();
            try { releaseChild.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            out.done(StopReason.END_TURN); return false;
        });
        try (var session = session(parent, child, true, 120000, 8, 50, List.of())) {
            var running = CompletableFuture.supplyAsync(() -> session.submit("delegate"));
            try {
                assertTrue(parentEntered.await(5, TimeUnit.SECONDS)); assertTrue(childEntered.await(5, TimeUnit.SECONDS));
                assertTrue(session.dispatch("/task list").orElseThrow().contains("task_1  [运行中]"));
                assertEquals("已请求取消任务 task_1（在回合边界停止）。", session.dispatch("/task cancel task_1").orElseThrow());
                assertTrue(childCancelled.await(5, TimeUnit.SECONDS));
                assertFalse(running.isDone(), "task control must leave the parent running");
            } finally { release.countDown(); releaseChild.countDown(); }
            assertInstanceOf(SubmitResult.Completed.class, running.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void sessionCloseDrainsAdmittedControlsAndControlsCannotBorrowTheMainAgent() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resourcesClosed = new AtomicBoolean();
        Capability commands = new Capability() { public void attachSession(SessionRegistrar registrar) {
            registrar.onClose(() -> resourcesClosed.set(true));
            registrar.command(SessionCommand.control("/control", "Independent control", (session, input) -> {
                assertThrows(IllegalStateException.class, () -> session.invokeCommandTool("unknown", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
                assertThrows(IllegalStateException.class, session::clear);
                assertThrows(IllegalStateException.class, session::close);
                entered.countDown(); await(release);
                throw new IllegalArgumentException("control failed");
            }));
        }};
        var provider = FakeProvider.responding(request -> FakeResponse.text("unused"));
        var session = session(provider, provider, TaskConfig.DEFAULT, List.of(), List.of(commands));
        var control = CompletableFuture.supplyAsync(() -> session.dispatch("/control"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = CompletableFuture.runAsync(session::close);
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertFalse(resourcesClosed.get(), "session resources must outlive every admitted control handler");
            release.countDown();
            assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                    () -> control.get(5, TimeUnit.SECONDS)).getCause());
            closing.get(5, TimeUnit.SECONDS);
            assertTrue(resourcesClosed.get());
        } finally { release.countDown(); session.close(); }
    }

    @Test void sessionShutdownStopsAndClosesEveryDetachedChildDespiteFailingListeners() throws Exception {
        var entered = new CountDownLatch(2);
        var closed = new AtomicInteger();
        var streamsClosed = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var cancelFailure = new IllegalStateException("cancel listener failed");
        var resourceFailure = new IllegalArgumentException("resource close failed");
        Provider child = (model, context, options) -> AssistantStream.of(model, options.cancellation(), new AssistantStream.Source() {
            public boolean pull(AssistantStream.Emitter out) throws Exception {
                options.cancellation().onCancel(() -> { throw cancelFailure; });
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.done(StopReason.END_TURN); return false;
            }
            public void close() { streamsClosed.countDown(); }
        });
        Capability resources = new Capability() { public void attachAgent(AgentScope scope) {
            if (!scope.agentId().equals("main")) {
                scope.onClose(closed::incrementAndGet);
                scope.onClose(() -> { throw resourceFailure; });
            }
        }};
        var parent = FakeProvider.scripted(List.of(spawn("one", true), spawn("two", true), FakeResponse.text("parent")));
        var session = session(parent, child, true, 120000, 8, 50, List.of(resources));
        try {
            run(session, "delegate"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            RuntimeException failure = assertThrows(RuntimeException.class, session::close);
            assertSame(cancelFailure, failure);
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(resourceFailure));
            assertEquals(0, streamsClosed.getCount());
            assertEquals(2, closed.get(), "all child-owned resources must be attempted despite earlier failures");
        } finally { release.countDown(); session.close(); }
    }

    @Test void taskOutputUsesTheRetainedAgentsPreparedSkillPresentation() throws Exception {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root.resolve("project")));
        Path skill = config.homes().project().resolve("skills/apricot/SKILL.md");
        java.nio.file.Files.createDirectories(skill.getParent());
        java.nio.file.Files.writeString(skill, "---\nname: apricot\ndescription: Test\n---\nUse apricot rules.\n");
        var skills = new com.cafecli.harness.skills.SkillsCapability(config.skills());
        var parent = FakeProvider.scripted(List.of(spawn("load a skill", false), FakeResponse.text("one"),
                tool("send_message", "{\"to\":\"task_1\",\"message\":\"next\"}"), FakeResponse.text("two"),
                tool("task_output", "{\"task_id\":\"task_1\"}"), FakeResponse.text("three")));
        var child = FakeProvider.scripted(List.of(tool("load_skill", "{\"name\":\"apricot\"}"),
                FakeResponse.text("loaded"), FakeResponse.text("continued")));
        try (var session = session(parent, child, false, 120000, 8, 50, List.of(skills))) {
            run(session, "spawn"); run(session, "continue"); run(session, "inspect");
            assertTrue(lastToolText(session).contains("user: ## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\nnext"));
            assertTrue(lastToolText(session).contains("  ⚙ load_skill {\"name\":\"apricot\"}"));
            assertFalse(lastToolText(session).contains("SkillBody["));
            assertEquals(UserMessage.text("## 已加载 Skill：apricot\nUse apricot rules.\n\n---\n\n用户输入：\nnext"),
                    child.requests().getLast().context().messages().getLast());
        }
    }

    @Test void autoBackgroundDuringReaperCleanupStillDeliversTheCompletedResult() throws Exception {
        var evicting = new CountDownLatch(1);
        var releaseEviction = new CountDownLatch(1);
        var secondWorker = new AtomicReference<Thread>();
        Capability resources = new Capability() { public void attachAgent(AgentScope scope) {
            if (scope.agentId().equals("task_1")) scope.onClose(() -> {
                evicting.countDown(); await(releaseEviction);
            });
        }};
        var parent = FakeProvider.scripted(List.of(spawn("first", false), FakeResponse.text("one"),
                spawn("second", false), FakeResponse.text("two"), FakeResponse.text("notified")));
        var child = FakeProvider.responding(request -> {
            if (((UserMessage) request.context().messages().getFirst()).content().toString().contains("second")) secondWorker.set(Thread.currentThread());
            return FakeResponse.text("child result");
        });
        try (var session = session(parent, child, true, 100, 1, 50, List.of(resources))) {
            run(session, "first");
            var second = CompletableFuture.supplyAsync(() -> session.submit("second"));
            try {
                assertTrue(evicting.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.Completed.class, second.get(2, TimeUnit.SECONDS));
                assertTrue(lastToolText(session).contains("已自动转入后台"));
            } finally { releaseEviction.countDown(); }
            secondWorker.get().join(5000); assertFalse(secondWorker.get().isAlive());
            run(session, "collect completion");
            assertEquals(1, session.log().stream().filter(TaskNotification.class::isInstance).map(TaskNotification.class::cast)
                    .filter(entry -> entry.taskId().equals("task_2")).count(), "timeout during reaping must not lose completion");
        }
    }

    @Test void activeContinuationInspectionIncludesItsCurrentUserAndCommittedToolBatch() throws Exception {
        var continuing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var child = FakeProvider.responding(request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return FakeResponse.text("initial answer");
            if (call == 2) return tool("unknown", "{\"marker\":\"current trace\"}");
            continuing.countDown(); await(release); return FakeResponse.text("continued answer");
        });
        var parent = FakeProvider.scripted(List.of(spawn("first", false), FakeResponse.text("one"),
                tool("send_message", "{\"to\":\"task_1\",\"message\":\"follow up\"}"), FakeResponse.text("two")));
        try (var session = session(parent, child, false, 120000, 8, 50, List.of())) {
            run(session, "spawn");
            var running = CompletableFuture.supplyAsync(() -> session.submit("continue"));
            try {
                assertTrue(continuing.await(5, TimeUnit.SECONDS));
                String output = session.dispatch("/task task_1").orElseThrow();
                assertTrue(output.contains("user: follow up"), "active continuation must expose its current user input");
                assertTrue(output.contains("  ⚙ unknown {\"marker\":\"current trace\"}"));
                assertTrue(output.contains("tool: "));
            } finally { release.countDown(); }
            assertInstanceOf(SubmitResult.Completed.class, running.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void parallelNestedWaitersShareOneRestoredPermitAndCancellationLeavesAdmissionUsable() throws Exception {
        class Round {
            final CountDownLatch firstEntered = new CountDownLatch(1);
            final CountDownLatch secondDispatched = new CountDownLatch(1);
            final CountDownLatch blocked = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final boolean pause;
            Round(boolean pause) { this.pause = pause; }
        }
        var round = new AtomicReference<Round>();
        var parent = FakeProvider.responding(request -> request.context().messages().getLast() instanceof UserMessage
                ? spawn("parallel", false) : FakeResponse.text("parent answer"));
        var child = FakeProvider.responding(request -> {
            String input = ((TextBlock) ((UserMessage) request.context().messages().getFirst()).content().getFirst()).text();
            if (input.equals("first leaf")) {
                Round current = round.get(); current.firstEntered.countDown(); await(current.secondDispatched);
                if (current.pause) { current.blocked.countDown(); await(current.release); }
                return FakeResponse.text("first answer");
            }
            if (input.equals("second leaf")) return FakeResponse.text("second answer");
            if (request.context().messages().size() == 1) return FakeResponse.builder()
                    .toolCall("a", "spawn_agent", "{\"prompt\":\"first leaf\"}")
                    .toolCall("b", "spawn_agent", "{\"prompt\":\"second leaf\"}").build();
            return FakeResponse.text("parallel answer");
        });
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root.resolve("project")));
        var factory = new AgentFactory(new RoutingProvider(Map.of("parent", parent, "child", child)),
                new PromptAssembler(new PromptRepository(root.resolve("up"), root.resolve("pp"))), java.time.Duration.ofSeconds(90));
        var taskRef = new AtomicReference<SubAgentsCapability>();
        Capability nested = new Capability() { public void attachAgent(AgentScope scope) {
            if (scope.agentId().equals("main")) return;
            taskRef.get().attachAgent(scope);
            scope.toolSnapshotStage(tools -> tools.stream().map(tool -> {
                if (!tool.name().equals("spawn_agent")) return tool;
                return new AgentTool() {
                    public String name() { return tool.name(); }
                    public String description() { return tool.description(); }
                    public com.fasterxml.jackson.databind.JsonNode parameters() { return tool.parameters(); }
                    public ToolResult execute(String id, com.fasterxml.jackson.databind.JsonNode input, CancellationToken token,
                            java.util.function.Consumer<ToolResult> progress) throws Exception {
                        if (input.path("prompt").asText().equals("second leaf")) {
                            Round current = round.get(); current.secondDispatched.countDown(); await(current.firstEntered);
                        }
                        return tool.execute(id, input, token, progress);
                    }
                };
            }).toList());
        }};
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, List.of(nested), 10),
                new TaskConfig(false, 120000, 1, 3, 8, 50));
        taskRef.set(tasks);
        try (var session = AgentSession.builder().config(config).factory(factory).model(MAIN).capabilities(List.of(nested, tasks)).build()) {
            var repeated = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 40; i++) {
                    round.set(new Round(false));
                    assertInstanceOf(RunOutcome.Completed.class, run(session, "parallel " + i));
                    assertTrue(lastToolText(session).endsWith("parallel answer"));
                }
            });
            try { repeated.get(10, TimeUnit.SECONDS); }
            finally { session.cancel(); repeated.get(5, TimeUnit.SECONDS); }
            var blocked = new Round(true); round.set(blocked);
            var cancelling = CompletableFuture.supplyAsync(() -> session.submit("cancel nested workers"));
            try {
                assertTrue(blocked.blocked.await(5, TimeUnit.SECONDS));
                session.cancel();
                assertInstanceOf(RunOutcome.Cancelled.class, assertInstanceOf(SubmitResult.Completed.class,
                        cancelling.get(5, TimeUnit.SECONDS)).outcome());
            } finally { blocked.release.countDown(); }
            round.set(new Round(false));
            assertInstanceOf(RunOutcome.Completed.class, run(session, "admission still usable"));
            assertTrue(lastToolText(session).endsWith("parallel answer"));
        }
    }

    private static String lastToolText(AgentSession session) {
        return session.log().reversed().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                .map(entry -> ((TextBlock) entry.message().content().getFirst()).text()).findFirst().orElseThrow();
    }
    private static FakeResponse tool(String name, String args) {
        return FakeResponse.builder().toolCall("call", name, args).build();
    }

    private AgentSession session(Provider parent, Provider child, boolean interactive, long autoMillis,
                                 int idle, int mirror, List<Capability> childCapabilities) {
        return session(parent, child, new TaskConfig(interactive, autoMillis, 8, 5, idle, mirror), childCapabilities);
    }
    private AgentSession session(Provider parent, Provider child, TaskConfig settings, List<Capability> childCapabilities) {
        return session(parent, child, settings, childCapabilities, List.of());
    }
    private AgentSession session(Provider parent, Provider child, TaskConfig settings, List<Capability> childCapabilities, List<Capability> afterTasks) {
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(),
                root.resolve("home"), root.resolve("project")));
        var factory = new AgentFactory(new RoutingProvider(Map.of("parent", parent, "child", child)),
                new PromptAssembler(new PromptRepository(root.resolve("user-prompts"), root.resolve("project-prompts"))),
                java.time.Duration.ofSeconds(90));
        var tasks = new SubAgentsCapability(factory, new AgentProfile(CHILD, PromptMode.AGENT, childCapabilities, 20),
                settings);
        var capabilities = new ArrayList<>(childCapabilities); capabilities.add(tasks); capabilities.addAll(afterTasks);
        return AgentSession.builder().config(config).factory(factory).model(MAIN).capabilities(capabilities).build();
    }
    private static FakeResponse spawn(String prompt, boolean background) {
        return FakeResponse.builder().toolCall("spawn", "spawn_agent", "{\"description\":\"work\",\"prompt\":\"" + prompt
                + "\",\"background\":" + background + "}").build();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
