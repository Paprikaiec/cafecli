package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveLifecycleSessionTest {
    @TempDir Path root;

    @Test void closeCancelsAndAwaitsPreparationWithoutSubmittingItsInput() throws Exception {
        var started = new CountDownLatch(1); var cancelled = new CountDownLatch(1); var release = new CountDownLatch(1);
        var inbox = new AtomicReference<BoundaryInbox>();
        Capability preparation = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.onSubmit((input, token) -> {
                    try (var ignored = token.onCancel(cancelled::countDown)) {
                        started.countDown(); await(cancelled); await(release);
                        return Submission.proceed(input);
                    }
                });
            }
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        var provider = FakeProvider.responding(request -> FakeResponse.text("never"));
        var result = new AtomicReference<SubmitResult>();
        var failures = new AtomicReference<Throwable>();
        var closed = new CountDownLatch(1);
        try (var session = session(provider, preparation)) {
            Thread submitting = Thread.ofVirtual().start(() -> {
                try { result.set(session.submit("prepared input")); } catch (Throwable failure) { failures.set(failure); }
            });
            Thread closing = Thread.ofVirtual().unstarted(() -> {
                try { session.close(); } catch (Throwable failure) { failures.set(failure); }
                finally { closed.countDown(); }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "closing"));
                closing.start();
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertFalse(closed.await(50, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            submitting.join(5000); closing.join(5000);
            assertFalse(submitting.isAlive()); assertFalse(closing.isAlive()); assertNull(failures.get());
            assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class,
                    assertInstanceOf(SubmitResult.Completed.class, result.get()).outcome());
            session.awaitIdle();
            assertTrue(provider.requests().isEmpty());
        }
    }

    @Test void anOrdinaryCommandKeepsItsOwnerAndCancellationLeaseUntilItsHandlerReturns() throws Exception {
        var started = new CountDownLatch(1); var cancelled = new CountDownLatch(1); var release = new CountDownLatch(1);
        var result = new AtomicReference<ToolResult>();
        Capability command = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.command(new SessionCommand("/work", "work", (session, input) -> {
                    result.set(session.invokeCommandTool("work", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
                    await(release);
                    return "finished";
                }));
            }
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new AgentTool() {
                    public String name() { return "work"; }
                    public String description() { return "work"; }
                    public JsonNode parameters() { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }
                    public ToolResult execute(String id, JsonNode input, CancellationToken token, Consumer<ToolResult> progress) {
                        try (var ignored = token.onCancel(cancelled::countDown)) {
                            started.countDown(); await(cancelled);
                            return new ToolResult(List.of(), null, ToolResult.Status.CANCELLED);
                        }
                    }
                });
            }
        };
        var provider = FakeProvider.responding(request -> FakeResponse.text("never"));
        try (var session = session(provider, command)) {
            var error = new AtomicReference<Throwable>();
            Thread dispatch = Thread.ofVirtual().start(() -> {
                try { assertEquals("finished", session.dispatch("/work").orElseThrow()); }
                catch (Throwable failure) { error.set(failure); }
            });
            var idle = new CountDownLatch(1);
            Thread waiter = Thread.ofVirtual().unstarted(() -> {
                try { session.awaitIdle(); idle.countDown(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> session.submit("cannot steer a command"));
                assertThrows(IllegalStateException.class, () -> session.invokeCommandTool("work", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
                session.cancel(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); waiter.start();
                assertFalse(idle.await(50, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            dispatch.join(5000); waiter.join(5000);
            assertFalse(dispatch.isAlive()); assertFalse(waiter.isAlive()); assertNull(error.get());
            assertEquals(ToolResult.Status.CANCELLED, result.get().status());
            assertTrue(session.log().isEmpty()); assertTrue(provider.requests().isEmpty());
        }
    }

    @Test void closeAndAwaitIdleCoverIndependentControlAdmission() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var closed = new CountDownLatch(1);
        Capability control = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.command(SessionCommand.control("/control", "control", (session, input) -> {
                    assertThrows(IllegalStateException.class, session::clear);
                    assertThrows(IllegalStateException.class, () -> session.invokeCommandTool("missing", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
                    started.countDown(); await(release); return "done";
                }));
            }
        };
        var provider = FakeProvider.responding(request -> FakeResponse.text("unused"));
        try (var session = session(provider, control)) {
            var error = new AtomicReference<Throwable>();
            Thread dispatch = Thread.ofVirtual().start(() -> {
                try { session.dispatch("/control"); } catch (Throwable failure) { error.set(failure); }
            });
            Thread closing = Thread.ofVirtual().unstarted(() -> {
                try { session.close(); } catch (Throwable failure) { error.set(failure); } finally { closed.countDown(); }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS)); closing.start();
                assertFalse(closed.await(50, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            dispatch.join(5000); closing.join(5000);
            assertFalse(dispatch.isAlive()); assertFalse(closing.isAlive()); assertNull(error.get());
            assertTrue(session.log().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"blocked", "cancelled", "failed"})
    void anIdleTaskCompletionSurvivesPreparationThatDoesNotStartAUserRun(String exit) throws Exception {
        var preparing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pushed = new CountDownLatch(1);
        var inbox = new AtomicReference<BoundaryInbox>();
        Capability capability = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.onSubmit((input, token) -> {
                    preparing.countDown(); await(release);
                    if (exit.equals("failed")) throw new IllegalStateException("preparation failed");
                    return exit.equals("blocked") ? Submission.block("blocked") : Submission.proceed(input);
                });
            }
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        var provider = FakeProvider.responding(request -> FakeResponse.text("notification handled"));
        var inputResult = new AtomicReference<SubmitResult>();
        var inputFailure = new AtomicReference<Throwable>();
        try (var session = session(provider, capability)) {
            session.subscribeCompletion(completion -> pushed.countDown());
            Thread input = Thread.ofVirtual().start(() -> {
                try { inputResult.set(session.submit("unadmitted user")); }
                catch (Throwable failure) { inputFailure.set(failure); }
            });
            try {
                assertTrue(preparing.await(5, TimeUnit.SECONDS));
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "ready"));
                if (exit.equals("cancelled")) session.cancel();
                // The input stays gated while task completion delivery is admitted independently.
                assertThrows(IllegalStateException.class, () -> session.dispatch("/unregistered"));
            } finally { release.countDown(); }
            input.join(5000); assertFalse(input.isAlive());
            if (exit.equals("failed")) assertEquals("preparation failed", inputFailure.get().getMessage());
            else if (exit.equals("blocked")) assertInstanceOf(SubmitResult.Blocked.class, inputResult.get());
            else assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class,
                        assertInstanceOf(SubmitResult.Completed.class, inputResult.get()).outcome());
            assertTrue(pushed.await(5, TimeUnit.SECONDS), "finishing preparation must release an idle task notification");
            session.awaitIdle();
            assertEquals(1, provider.requests().size());
            assertEquals(com.cafecli.ai.message.UserMessage.text("<task-notification task_id=\"task_1\">\nready\n</task-notification>"),
                    provider.requests().getFirst().context().messages().getFirst());
            assertTrue(session.log().stream().noneMatch(com.cafecli.core.log.UserEntry.class::isInstance));
        }
    }

    private AgentSession session(FakeProvider provider, Capability capability) {
        return AgentSession.builder().interactive(true).provider(provider).model(InteractiveSessionTest.MODEL)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(capability)).build();
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
