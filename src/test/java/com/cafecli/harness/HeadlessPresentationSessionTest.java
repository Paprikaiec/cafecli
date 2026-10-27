package com.cafecli.harness;

import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessPresentationSessionTest {
    @TempDir Path root;

    @Test void unicodeBlankAndControlOnlyReasoningDoNotAddAHeadingAndNonblankUnicodeBytesSurvive() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("\u2003").text("answer").build(),
                FakeResponse.builder().thinking("\u0000").text("answer").build(),
                FakeResponse.builder().thinking("\u2003real\u2003").text(" \u2003answer\u2003 ").build()));
        var presenter = new HeadlessRunPresenter();
        try (var session = session(provider, Map.of())) {
            session.subscribe(presenter);
            assertEquals("answer", presenter.format(outcome(session, "Unicode blank")));
            assertEquals("answer", presenter.format(outcome(session, "Control only")));
            assertEquals("🧠 思考过程:\n\u2003real\u2003\n\n\u2003answer\u2003", presenter.format(outcome(session, "Keep Unicode")));
        }
    }

    @Test void emptyTrimmedControlChunksPreserveTheExactV1SeparatorsBetweenRealIterations() {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().thinking("\u2003").toolCall("a", "missing", "{\"slot\":0}").build(),
                FakeResponse.builder().thinking("\u0000").toolCall("b", "missing", "{\"slot\":1}").build(),
                FakeResponse.builder().thinking(" before ").toolCall("c", "missing", "{\"slot\":2}").build(),
                FakeResponse.builder().thinking("\u0000").toolCall("d", "missing", "{\"slot\":3}").build(),
                FakeResponse.builder().thinking(" after ").text("answer").build()));
        var presenter = new HeadlessRunPresenter();
        try (var session = session(provider, Map.of())) {
            session.subscribe(presenter);
            assertEquals("🧠 思考过程:\nbefore\n\n\n\nafter\n\nanswer", presenter.format(outcome(session, "Complete the fixture")));
        }
    }

    @Test void reasoningOnlyWhitespaceAndClearKeepPresentationSeparateFromTheActualSource() {
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("  first  ").text("  ").build(),
                FakeResponse.text("  plain answer  "), FakeResponse.builder().thinking(" left ").text("interlude")
                        .thinking("right ").text(" done ").build()));
        var presenter = new HeadlessRunPresenter();
        try (var session = session(provider, Map.of())) {
            session.subscribe(presenter);
            assertEquals("🧠 思考过程:\nfirst", presenter.format(outcome(session, "one")));
            String source = session.messageKinds().json().write(session.log());
            assertTrue(source.contains("  first  ")); assertFalse(source.contains("🧠 思考过程:"));
            assertEquals("plain answer", presenter.format(outcome(session, "two")), "a new run resets reasoning");
            session.clear();
            assertEquals("🧠 思考过程:\nleft right\n\ninterlude done", presenter.format(outcome(session, "three")),
                    "thinking blocks join in source order within each completed response");
            assertEquals(2, session.log().size());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void failedAndBudgetExhaustedRunsNeverExposeEarlierOrPartialReasoningAsAnAnswer() {
        var completed = FakeResponse.builder().thinking("completed iteration reasoning").toolCall("t", "missing", "{}").build();
        for (boolean budget : List.of(true, false)) {
            var provider = FakeProvider.scripted(List.of(completed,
                    FakeResponse.builder().thinking("partial failed reasoning").text("partial failed text").fail(ErrorKind.FATAL, "fixture failure")));
            var presenter = new HeadlessRunPresenter();
            try (var session = session(provider, budget ? Map.of("cafecli.react.hard.max.iterations", "1") : Map.of())) {
                session.subscribe(presenter);
                RunOutcome outcome = outcome(session, "attempt");
                if (budget) assertInstanceOf(RunOutcome.BudgetExhausted.class, outcome); else assertInstanceOf(RunOutcome.Failed.class, outcome);
                assertEquals(budget ? "❌ 达到硬轮数上限（1），已强制收尾" : "❌ 调用 LLM 失败: fixture failure", presenter.format(outcome));
                assertFalse(session.messageKinds().json().write(session.log()).contains("partial failed"));
            }
        }
    }

    @Test void cancellingTheActualInFlightRequestDoesNotExposeCompletedOrAbortedReasoning() throws Exception {
        var entered = new CountDownLatch(1);
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("completed reasoning").toolCall("t", "missing", "{}").build(),
                FakeResponse.builder().thinking("aborted reasoning").text("partial").holdUntilCancelled(entered::countDown).build(),
                FakeResponse.text("fresh answer")));
        var presenter = new HeadlessRunPresenter();
        try (var session = session(provider, Map.of()); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            session.subscribe(presenter);
            var running = worker.submit(() -> outcome(session, "cancel"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); session.cancel();
                RunOutcome result = running.get(5, TimeUnit.SECONDS); assertInstanceOf(RunOutcome.Cancelled.class, result);
                assertEquals("⏹️ 已取消当前任务。", presenter.format(result));
                assertFalse(session.messageKinds().json().write(session.log()).contains("aborted reasoning"));
            } finally { session.cancel(); }
            assertEquals("fresh answer", presenter.format(outcome(session, "resume")));
        }
    }

    @Test void completedChildReasoningIsNotPartOfTheMainHeadlessTranscript() {
        var provider = FakeProvider.responding(request -> {
            if (request.context().messages().getFirst().toString().contains("private child input"))
                return FakeResponse.builder().thinking("private child reasoning").text("child answer").build();
            return request.context().messages().size() == 1 ? FakeResponse.builder()
                    .toolCall("child", "spawn_agent", "{\"prompt\":\"private child input\"}").build() : FakeResponse.text("main answer");
        });
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var model = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
        var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                root.resolve("user-prompts"), root.resolve("project-prompts"))), java.time.Duration.ofSeconds(90));
        var tasks = new com.cafecli.harness.tasks.SubAgentsCapability(factory, AgentProfile.mainAgent(model, List.of(), 10));
        var presenter = new HeadlessRunPresenter();
        var events = new java.util.concurrent.CopyOnWriteArrayList<com.cafecli.core.AgentEvent>();
        try (var session = AgentSession.builder().config(config).factory(factory).model(model).capabilities(List.of(tasks)).build()) {
            session.subscribe(presenter); session.subscribe(events::add);
            assertEquals("main answer", presenter.format(outcome(session, "delegate")));
            assertTrue(events.stream().anyMatch(event -> !event.agentId().equals("main") && event instanceof com.cafecli.core.AgentEvent.MessageEnd end
                    && end.message().content().toString().contains("private child reasoning")));
        }
    }

    private AgentSession session(FakeProvider provider, Map<String, String> properties) {
        var config = ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root));
        return AgentSession.builder().config(config).provider(provider)
                .model(new Model("fake", "fake", "fake", 128000, 8192, Set.of())).build();
    }
    private static RunOutcome outcome(AgentSession session, String input) {
        return assertInstanceOf(SubmitResult.Completed.class, session.submit(input)).outcome();
    }
}
