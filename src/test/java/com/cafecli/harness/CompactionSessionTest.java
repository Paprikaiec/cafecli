package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.ErrorKind;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.UserEntry;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.context.Summary;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.core.log.CustomEntry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.LogEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.context.CompactionCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompactionSessionTest {
    @TempDir Path root;
    private static final Model MODEL = new Model("small", "fake", "fake", 8000, 1000, Set.of());

    @Test void boundarySummaryReplacesCoveredHistoryWithoutChangingItsBytes() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("old answer"),
                FakeResponse.text("two"), FakeResponse.text("three"),
                FakeResponse.text("remember the goal"), FakeResponse.text("four")));
        try (var session = session(provider)) {
            List<AgentEvent> events = new ArrayList<>();
            session.subscribe(events::add);
            session.submit("a".repeat(32000));
            session.submit("second");
            session.submit("third");
            List<LogEntry> source = session.log();
            String bytes = session.messageKinds().json().write(source);
            session.submit("fourth");
            assertEquals(List.of(UserMessage.text("[已压缩的历史对话摘要]\nremember the goal"),
                    UserMessage.text("second"), ((com.cafecli.core.log.AssistantEntry)source.get(3)).message(),
                    UserMessage.text("third"), ((com.cafecli.core.log.AssistantEntry)source.get(5)).message(),
                    UserMessage.text("fourth")), provider.requests().getLast().context().messages());
            assertEquals(bytes, session.messageKinds().json().write(session.log().subList(0, source.size())));
            assertTrue(events.stream().anyMatch(AgentEvent.Compaction.class::isInstance));
        }
    }

    @Test void overflowCompactsAndRetriesExactlyOnceWithoutAnotherIteration() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("old goal"), FakeResponse.text("done")));
        try (var session = session(provider)) {
            seed(session);
            List<AgentEvent> events = new ArrayList<>(); session.subscribe(events::add);
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "four"));
            assertEquals(6, provider.requests().size());
            assertEquals(1, events.stream().filter(AgentEvent.IterationStart.class::isInstance).count());
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nold goal"),
                    provider.requests().getLast().context().messages().getFirst());
            assertEquals(new Summary(0, 2, "old goal"), session.log().get(7));
        }
    }

    @Test void secondOverflowEndsWithTypedFailureAndNeverRetriesAgain() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("old goal"), overflow()));
        try (var session = session(provider)) {
            seed(session);
            assertEquals(ErrorKind.CONTEXT_OVERFLOW, assertInstanceOf(RunOutcome.Failed.class,
                    SessionTestSupport.run(session, "four")).kind());
            assertEquals(6, provider.requests().size());
            assertEquals(8, session.log().size(), "neither failed partial answer enters the log");
        }
    }

    @Test void overflowWithNoRecoverableRangeDoesNotRetry() {
        var provider = FakeProvider.scripted(List.of(overflow()));
        try (var session = session(provider)) {
            assertInstanceOf(RunOutcome.Failed.class, SessionTestSupport.run(session, "first"));
            assertEquals(1, provider.requests().size());
            assertEquals(List.of(new UserEntry(UserMessage.text("first"))), session.log());
        }
    }

    @Test void emptyAndFailedSummariesNeverClaimCoverageOrRetry() {
        for (var summary : List.of(FakeResponse.text("  "), overflow(),
                FakeResponse.builder().toolCall("bad", "unexpected", "{}").build())) {
            var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                    FakeResponse.text("three"), overflow(), summary));
            try (var session = session(provider)) {
                seed(session);
                var source = session.log();
                List<AgentEvent> events = new ArrayList<>(); session.subscribe(events::add);
                assertInstanceOf(RunOutcome.Failed.class, SessionTestSupport.run(session, "four"));
                assertEquals(5, provider.requests().size());
                assertEquals(source, session.log().subList(0, source.size()));
                assertTrue(session.log().stream().noneMatch(Summary.class::isInstance));
                assertTrue(events.stream().noneMatch(AgentEvent.Compaction.class::isInstance));
            }
        }
    }

    @Test void repeatedCompactionReplacesPriorSummaryAndKeepsTheNewerToolRoundPaired() {
        var toolRound = FakeResponse.builder().toolCall("read", "missing", "{\"path\":\"a\"}").build();
        var provider = FakeProvider.scripted(List.of(toolRound, FakeResponse.text("one"),
                FakeResponse.text("two"), FakeResponse.text("three"), overflow(), FakeResponse.text("first summary"),
                FakeResponse.text("four"), overflow(), FakeResponse.text("second summary"), FakeResponse.text("five")));
        try (var session = session(provider)) {
            seed(session);
            var source = session.log();
            session.submit("four");
            session.submit("five");
            var request = provider.requests().getLast().context().messages();
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nsecond summary"), request.getFirst());
            assertEquals(UserMessage.text("three"), request.get(1));
            assertEquals(2, session.log().stream().filter(Summary.class::isInstance).count());
            assertEquals(source, session.log().subList(0, source.size()));
            assertTrue(session.log().get(1) instanceof AssistantEntry);
            assertTrue(session.log().get(2) instanceof ToolResultEntry);
            var summarizerInput = provider.requests().get(8).context().messages().toString();
            assertTrue(summarizerInput.contains("first summary"));
            assertTrue(summarizerInput.contains("two"));
            assertFalse(summarizerInput.contains("TOOL_CALL"), "the already summarized tool round stays summarized");
        }
    }

    @Test void projectionStagesComposeInRegistrationOrderWithoutRewritingTheLog() {
        Capability stages = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                for (String suffix : List.of(" first", " second")) scope.projectionStage(log -> log.stream()
                        .map(entry -> entry instanceof UserEntry user
                                ? (LogEntry)new UserEntry(UserMessage.text(((com.cafecli.ai.message.TextBlock)
                                        user.message().content().getFirst()).text() + suffix)) : entry).toList());
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("answer")));
        try (var session = session(provider, MODEL, stages)) {
            session.submit("original");
            assertEquals(List.of(UserMessage.text("original first second")),
                    provider.requests().getFirst().context().messages());
            assertEquals(new UserEntry(UserMessage.text("original")), session.log().getFirst());
        }
    }

    @Test void actualAgentModelDeterminesThresholdAndUnattachedAgentsDoNotCompact() {
        var large = new Model("large", "fake", "fake", 128000, 1000, Set.of());
        for (boolean attached : List.of(true, false)) {
            var provider = FakeProvider.responding(request -> FakeResponse.text("answer"));
            try (var session = attached ? session(provider, large, new CompactionCapability(provider))
                    : session(provider, MODEL)) {
                session.submit("a".repeat(32000)); seed(session);
                assertEquals(4, provider.requests().size());
                assertTrue(session.log().stream().noneMatch(Summary.class::isInstance));
            }
        }
    }

    @Test void cancelledSummaryPreservesSourceAndTheNextRunCanContinue() throws Exception {
        CountDownLatch summarizing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        var provider = FakeProvider.responding(request -> {
            int n = requests.incrementAndGet();
            if (n == 4) return overflow();
            if (n == 5) {
                summarizing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
            return FakeResponse.text("answer");
        });
        try (var session = session(provider)) {
            seed(session); var source = session.log();
            var run = CompletableFuture.supplyAsync(() -> SessionTestSupport.run(session, "four"));
            try { assertTrue(summarizing.await(5, TimeUnit.SECONDS)); session.cancel(); }
            finally { release.countDown(); }
            assertInstanceOf(RunOutcome.Cancelled.class, run.get(5, TimeUnit.SECONDS));
            assertEquals(source, session.log().subList(0, source.size()));
            assertTrue(session.log().stream().noneMatch(Summary.class::isInstance));
            assertEquals(5, provider.requests().size());
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "five"));
        }
    }

    @Test void summaryProviderFailureDoesNotPoisonLaterRuns() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var provider = FakeProvider.responding(request -> {
            int n = requests.incrementAndGet();
            if (n == 4) return overflow();
            if (n == 5) throw new IllegalStateException("summary callback failed");
            return FakeResponse.text("answer");
        });
        try (var session = session(provider)) {
            seed(session); var source = session.log();
            assertInstanceOf(RunOutcome.Failed.class, SessionTestSupport.run(session, "four"));
            session.awaitIdle();
            assertEquals(source, session.log().subList(0, source.size()));
            assertTrue(session.log().stream().noneMatch(Summary.class::isInstance));
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "five"));
        }
    }

    @Test void cachedSystemPromptCountsTowardTheThresholdWithoutRereadingItsTemplate() throws Exception {
        var override = java.nio.file.Files.createDirectories(root.resolve(".cafecli/prompts")).resolve("handoff.md");
        java.nio.file.Files.writeString(override, "s".repeat(32000));
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), FakeResponse.text("summary"), FakeResponse.text("four")));
        try (var session = session(provider)) {
            seed(session);
            java.nio.file.Files.writeString(override, "new template must not be loaded implicitly");
            session.submit("four");
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nsummary"),
                    provider.requests().getLast().context().messages().getFirst());
            assertEquals(provider.requests().getFirst().context().systemPrompt(),
                    provider.requests().getLast().context().systemPrompt());
        }
    }

    @Test void compactionSubscriberFailurePropagatesWithoutClaimingCoverage() throws Exception {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("summary"), FakeResponse.text("later")));
        try (var session = session(provider)) {
            seed(session); var source = session.log();
            var subscription = session.subscribe(event -> {
                if (event instanceof AgentEvent.Compaction) throw new IllegalStateException("compaction listener failed");
            });
            assertEquals("compaction listener failed", assertThrows(IllegalStateException.class,
                    () -> session.submit("four")).getMessage());
            subscription.close(); session.awaitIdle();
            assertEquals(source, session.log().subList(0, source.size()));
            assertTrue(session.log().stream().noneMatch(Summary.class::isInstance));
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "later"));
        }
    }

    @Test void clearDiscardsSummaryRangesAndStartsWithAFreshSourceLog() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("summary"), FakeResponse.text("four"),
                FakeResponse.text("fresh")));
        try (var session = session(provider)) {
            seed(session); session.submit("four"); session.clear(); session.submit("fresh");
            assertEquals(List.of(UserMessage.text("fresh")), provider.requests().getLast().context().messages());
            assertEquals(2, session.log().size());
        }
    }

    @Test void summaryInputHonorsCustomVisibilityAndTheRetainedToolRoundStaysPaired() {
        Capability notes = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(MessageKind.tagged("visible-note", VisibleNote.class, "[note]", VisibleNote::text));
                scope.messageKind(MessageKind.hidden("hidden-note", HiddenNote.class));
                scope.runStartWriter((input, token) -> List.of(new VisibleNote("visible project goal"),
                        new HiddenNote("hidden UI secret")));
            }
        };
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("old", "missing", "{\"path\":\"old\"}").build(), FakeResponse.text("one"),
                FakeResponse.builder().toolCall("retained", "missing", "{\"path\":\"retained\"}").build(), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("summary"), FakeResponse.text("four")));
        try (var session = session(provider, MODEL, notes, new CompactionCapability(provider))) {
            seed(session); var source = session.log();
            session.submit("four");
            var summaryInput = provider.requests().get(6).context().messages().toString();
            assertTrue(summaryInput.contains("visible project goal"));
            assertTrue(summaryInput.contains("TOOL_CALL missing: {path".replace("{path", "{\"path\"")));
            assertFalse(summaryInput.contains("hidden UI secret"));
            var request = provider.requests().getLast().context().messages();
            var call = request.stream().filter(com.cafecli.ai.message.AssistantMessage.class::isInstance)
                    .map(com.cafecli.ai.message.AssistantMessage.class::cast)
                    .flatMap(message -> message.toolCalls().stream()).toList();
            var results = request.stream().filter(com.cafecli.ai.message.ToolResultMessage.class::isInstance)
                    .map(com.cafecli.ai.message.ToolResultMessage.class::cast).toList();
            assertEquals(List.of("retained"), call.stream().map(com.cafecli.ai.message.ToolCall::id).toList());
            assertEquals(List.of("retained"), results.stream().map(com.cafecli.ai.message.ToolResultMessage::toolCallId).toList());
            assertEquals(source, session.log().subList(0, source.size()));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }
    @Test void earlierFilteringStageCannotChangeTheSourceRangeOrDropARetainedRound() {
        Capability filter = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(MessageKind.hidden("hidden-note", HiddenNote.class));
                scope.runStartWriter((input, token) -> List.of(new HiddenNote("UI only")));
                scope.projectionStage(log -> log.stream().filter(entry -> !(entry instanceof HiddenNote)).toList());
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("one"),
                FakeResponse.builder().toolCall("retained", "missing", "{}").build(), FakeResponse.text("two"),
                FakeResponse.text("three"), overflow(), FakeResponse.text("summary"), FakeResponse.text("four")));
        try (var session = session(provider, MODEL, filter, new CompactionCapability(provider))) {
            seed(session); var source = session.log();
            String bytes = session.messageKinds().json().write(source);
            session.submit("four");
            var request = provider.requests().getLast().context().messages();
            assertEquals(UserMessage.text("[已压缩的历史对话摘要]\nsummary"), request.getFirst());
            assertEquals(UserMessage.text("two"), request.get(1));
            var assistant = assertInstanceOf(com.cafecli.ai.message.AssistantMessage.class, request.get(2));
            var result = assertInstanceOf(com.cafecli.ai.message.ToolResultMessage.class, request.get(3));
            assertEquals("retained", assistant.toolCalls().getFirst().id());
            assertEquals("retained", result.toolCallId());
            assertEquals(UserMessage.text("three"), request.get(5));
            assertEquals(UserMessage.text("four"), request.get(7));
            assertEquals(bytes, session.messageKinds().json().write(session.log().subList(0, source.size())));
        }
    }

    @Test void sourceNormalizationPrecedesViewStagesAndBothPhasesKeepRegistrationOrder() {
        Capability first = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.projectionStage(log -> appendToUsers(log, " view-first"));
                scope.sourceProjectionStage(log -> appendToUsers(log, " source-first"));
            }
        };
        Capability second = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.sourceProjectionStage(log -> appendToUsers(log, " source-second"));
                scope.projectionStage(log -> appendToUsers(log, " view-second"));
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("answer")));
        try (var session = session(provider, MODEL, first, second)) {
            session.submit("original");
            assertEquals(List.of(UserMessage.text("original source-first source-second view-first view-second")),
                    provider.requests().getFirst().context().messages());
            assertEquals(new UserEntry(UserMessage.text("original")), session.log().getFirst());
        }
    }
    private static List<LogEntry> appendToUsers(List<LogEntry> log, String suffix) {
        return log.stream().map(entry -> entry instanceof UserEntry user
                ? (LogEntry)new UserEntry(UserMessage.text(((com.cafecli.ai.message.TextBlock)
                        user.message().content().getFirst()).text() + suffix)) : entry).toList();
    }

    record VisibleNote(String text) implements CustomEntry { }
    record HiddenNote(String text) implements CustomEntry { }

    private static FakeResponse overflow() {
        return FakeResponse.builder().text("partial must not survive").fail(ErrorKind.CONTEXT_OVERFLOW, "too big");
    }
    private static void seed(AgentSession session) {
        session.submit("one"); session.submit("two"); session.submit("three");
    }

    private AgentSession session(FakeProvider provider) {
        return session(provider, MODEL, new CompactionCapability(provider));
    }
    private AgentSession session(FakeProvider provider, Model model, Capability... capabilities) {
        return AgentSession.builder().provider(provider).model(model)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(capabilities)).build();
    }
}
