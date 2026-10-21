package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentTool;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.harness.context.Steering;
import com.fasterxml.jackson.databind.node.DoubleNode;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PRD seam 1: concurrent submit, real core tool batch, recorded requests and the agent log. */
class SteeringSessionTest {
    private static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8_192, Set.of());
    @TempDir Path root;

    @Test
    void steeringSubmittedDuringAToolBatchReachesTheNextRequest() throws Exception {
        CountDownLatch executing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Capability tools = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new AgentTool() {
                    @Override public String name() { return "work"; }
                    @Override public String description() { return "work"; }
                    @Override public JsonNode parameters() { return JsonNodeFactory.instance.objectNode(); }
                    @Override public ToolResult execute(String id, JsonNode input, CancellationToken token,
                                                        Consumer<ToolResult> progress) throws Exception {
                        executing.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return new ToolResult(List.of(new TextBlock(id)), null, ToolResult.Status.OK);
                    }
                });
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("one", "work", "{}").toolCall("two", "work", "{}").build(), FakeResponse.text("done")));
        try (AgentSession session = session(provider, tools)) {
            var run = CompletableFuture.supplyAsync(() -> session.submit("start"));
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS), "both tools must be executing before steering");
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("Use the other file"));
            } finally { release.countDown(); }
            assertInstanceOf(SubmitResult.Completed.class, run.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(), session.takeFollowUpInputs());
            assertEquals(List.of(new Steering("Use the other file")), session.log().stream()
                    .filter(Steering.class::isInstance).toList());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
            assertTrue(provider.requests().get(1).context().messages()
                    .contains(UserMessage.text("[用户转向 / 回合中提交]\nUse the other file")));
        }
    }

    @Test
    void cancelledPendingSteeringNeverLeaksIntoTheNextRunButOtherKindsSurvive() throws Exception {
        CountDownLatch executing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<BoundaryInbox> inbox = new AtomicReference<>();
        Capability context = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(MessageKind.tagged("notice", Notice.class, "[notice]", Notice::text));
                inbox.set(scope.inbox());
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(batchResponse(), FakeResponse.text("next")));
        try (AgentSession session = session(provider, blockedTools(executing, release), context)) {
            var run = CompletableFuture.supplyAsync(() -> session.submit("start"));
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("stale correction"));
                inbox.get().post(new Notice("durable notification"));
                session.cancel();
            } finally { release.countDown(); }
            assertInstanceOf(RunOutcome.Cancelled.class,
                    assertInstanceOf(SubmitResult.Completed.class, run.get(5, TimeUnit.SECONDS)).outcome());
            assertEquals(List.of(), session.takeFollowUpInputs());
            assertTrue(session.log().stream().noneMatch(Steering.class::isInstance));
            session.submit("new request");
            assertEquals(2, provider.requests().size());
            assertTrue(provider.requests().get(1).context().messages().contains(UserMessage.text("[notice]\ndurable notification")));
            assertTrue(provider.requests().get(1).context().messages().stream().noneMatch(message ->
                    message instanceof UserMessage user && user.content().toString().contains("stale correction")));
            assertTrue(session.log().stream().noneMatch(Steering.class::isInstance));
        }
    }

    @Test
    void cancellationAtTheNextBoundaryDiscardsSteeringBeforeItCanLand() throws Exception {
        CountDownLatch executing = new CountDownLatch(2);
        CountDownLatch releaseTools = new CountDownLatch(1);
        CountDownLatch boundary = new CountDownLatch(1);
        CountDownLatch releaseBoundary = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> request.context().messages().size() == 1
                ? batchResponse() : FakeResponse.text("done"));
        try (AgentSession session = session(provider, blockedTools(executing, releaseTools))) {
            AtomicInteger boundaries = new AtomicInteger();
            session.subscribe(event -> {
                if (event instanceof AgentEvent.IterationStart && boundaries.incrementAndGet() == 2) {
                    boundary.countDown();
                    await(releaseBoundary);
                }
            });
            var run = CompletableFuture.supplyAsync(() -> session.submit("start"));
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("cancelled at boundary"));
                releaseTools.countDown();
                assertTrue(boundary.await(5, TimeUnit.SECONDS));
                session.cancel();
            } finally { releaseTools.countDown(); releaseBoundary.countDown(); }
            assertInstanceOf(RunOutcome.Cancelled.class,
                    assertInstanceOf(SubmitResult.Completed.class, run.get(5, TimeUnit.SECONDS)).outcome());
            session.submit("fresh");
            assertTrue(session.log().stream().noneMatch(Steering.class::isInstance));
            for (var request : provider.requests()) {
                assertTrue(request.context().messages().stream().noneMatch(message -> message instanceof UserMessage user
                        && user.content().toString().contains("cancelled at boundary")));
            }
            assertEquals(List.of(), session.takeFollowUpInputs());
        }
    }

    @Test
    void hiddenKindIsLoggedLosslesslyButNeverSentToTheModel() {
        AtomicReference<BoundaryInbox> inbox = new AtomicReference<>();
        Capability hidden = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(MessageKind.hidden("hidden-note", HiddenNote.class));
                inbox.set(scope.inbox());
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        try (AgentSession session = session(provider, hidden)) {
            HiddenNote entry = new HiddenNote("UI secret", DoubleNode.valueOf(1.25));
            inbox.get().post(entry);
            session.submit("hello");
            assertEquals(entry, session.log().get(1));
            assertEquals(List.of(UserMessage.text("hello")), provider.requests().getFirst().context().messages());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
            assertThrows(IllegalArgumentException.class, () -> inbox.get().post(new Notice("unattached kind")));
        }
    }

    @Test
    void inputAcceptedAtTheTerminalBoundaryIsHandedBackOnceAsOrdinaryInput() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.text("done"));
        try (AgentSession session = session(provider)) {
            AtomicInteger ends = new AtomicInteger();
            session.subscribe(event -> {
                if (event instanceof AgentEvent.RunEnd && ends.getAndIncrement() == 0) {
                    terminal.countDown();
                    await(release);
                }
            });
            var run = CompletableFuture.supplyAsync(() -> session.submit("first"));
            try {
                assertTrue(terminal.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("second"));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("third"));
            } finally { release.countDown(); }
            assertInstanceOf(SubmitResult.Completed.class, run.get(5, TimeUnit.SECONDS));
            assertEquals(1, provider.requests().size(), "the synchronous facade must not start follow-up runs");
            assertTrue(session.log().stream().noneMatch(Steering.class::isInstance));
            List<String> handedBack = session.takeFollowUpInputs();
            assertEquals(List.of("second", "third"), handedBack);
            assertEquals(List.of(), session.takeFollowUpInputs());
            // The caller explicitly resubmits. The promoted message is an ordinary user entry, with no steering tag.
            assertInstanceOf(SubmitResult.Completed.class, session.submit(handedBack.getFirst()));
            assertEquals(new UserEntry(UserMessage.text("second")), session.log().get(2));
            assertEquals(List.of(UserMessage.text("first"),
                    ((com.cafecli.core.log.AssistantEntry) session.log().get(1)).message(), UserMessage.text("second")),
                    provider.requests().get(1).context().messages());
        }
    }

    @Test
    void callbackFailureStillHandsBackUndeliveredInputAndCannotLeakItIntoAnotherRun() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.text("done"));
        try (AgentSession session = session(provider)) {
            AtomicInteger messages = new AtomicInteger();
            session.subscribe(event -> {
                if (event instanceof AgentEvent.MessageEnd && messages.getAndIncrement() == 0) {
                    terminal.countDown();
                    await(release);
                    throw new IllegalStateException("subscriber failed");
                }
            });
            var run = CompletableFuture.supplyAsync(() -> session.submit("first"));
            try {
                assertTrue(terminal.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("resubmit me"));
            } finally { release.countDown(); }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(5, TimeUnit.SECONDS));
            assertEquals("subscriber failed", failure.getCause().getMessage());
            assertEquals(List.of("resubmit me"), session.takeFollowUpInputs());
            session.submit("another run");
            assertEquals(List.of(UserMessage.text("first"), UserMessage.text("another run")),
                    provider.requests().get(1).context().messages());
        }
    }

    @Test
    void cancellingAfterSteeringLandsPreservesItInTheLogAndLaterRequests() throws Exception {
        CountDownLatch executing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FakeProvider provider = FakeProvider.responding(request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return batchResponse();
            if (call == 2) { secondRequest.countDown(); await(answer); }
            return FakeResponse.text("done");
        });
        try (AgentSession session = session(provider, blockedTools(executing, release))) {
            var run = CompletableFuture.supplyAsync(() -> session.submit("start"));
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                session.submit("landed instruction");
                release.countDown();
                assertTrue(secondRequest.await(5, TimeUnit.SECONDS));
                session.cancel();
            } finally { release.countDown(); answer.countDown(); }
            assertInstanceOf(RunOutcome.Cancelled.class,
                    assertInstanceOf(SubmitResult.Completed.class, run.get(5, TimeUnit.SECONDS)).outcome());
            assertEquals(List.of(), session.takeFollowUpInputs());
            session.submit("continue");
            assertTrue(provider.requests().get(2).context().messages()
                    .contains(UserMessage.text("[用户转向 / 回合中提交]\nlanded instruction")));
            assertEquals(1, session.log().stream().filter(Steering.class::isInstance).count());
        }
    }

    private static Capability blockedTools(CountDownLatch executing, CountDownLatch release) {
        return new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.tool(new AgentTool() {
                    @Override public String name() { return "work"; }
                    @Override public String description() { return "work"; }
                    @Override public JsonNode parameters() { return JsonNodeFactory.instance.objectNode(); }
                    @Override public ToolResult execute(String id, JsonNode input, CancellationToken token,
                                                        Consumer<ToolResult> progress) throws Exception {
                        executing.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return new ToolResult(List.of(new TextBlock(id)), null, ToolResult.Status.OK);
                    }
                });
            }
        };
    }

    private static FakeResponse batchResponse() {
        return FakeResponse.builder().toolCall("one", "work", "{}").toolCall("two", "work", "{}").build();
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    record Notice(String text) implements CustomEntry { }
    record HiddenNote(String text, JsonNode data) implements CustomEntry { }

    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        return AgentSession.builder().provider(provider).model(MODEL)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(capabilities)).build();
    }
}
