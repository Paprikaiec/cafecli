package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.Usage;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.BudgetReason;
import com.cafecli.core.IterationDecision;
import com.cafecli.harness.context.BudgetCapability;
import com.cafecli.harness.context.BudgetExit;
import com.cafecli.harness.hooks.StopBlock;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BudgetSessionTest {
    @TempDir Path root;
    private static final Model MODEL = new Model("fake", "fake", "fake", 8000, 1000, Set.of());

    @Test void repeatedToolInvocationsExhaustTheRunBeforeTheHardCap() {
        var response = FakeResponse.builder().toolCall("call", "missing", "{\"path\":\"a\"}").build();
        var provider = FakeProvider.scripted(List.of(response, response, response, FakeResponse.text("should not run")));
        try (var session = session(provider, Map.of())) {
            var outcome = SessionTestSupport.run(session, "start");
            assertInstanceOf(RunOutcome.BudgetExhausted.class, outcome);
            assertEquals(new com.cafecli.harness.context.BudgetExit.Stagnation(3),
                    ((RunOutcome.BudgetExhausted)outcome).reason());
            assertEquals(3, provider.requests().size());
        }
    }

    @Test void tokenUsageAccumulatesAndStopsAfterTheWholeToolBatch() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 60, 30), call("two", "{}", 20, 0),
                FakeResponse.text("unexpected")));
        try (var session = session(provider, Map.of("cafecli.react.token.budget", "100"))) {
            var outcome = assertInstanceOf(RunOutcome.BudgetExhausted.class, SessionTestSupport.run(session, "start"));
            assertEquals(new BudgetExit.Tokens(100, 110), outcome.reason());
            assertEquals(2, provider.requests().size());
            assertEquals(5, session.log().size(), "both assistant calls and their results remain paired");
        }
    }

    @Test void finalAnswerCompletesEvenWhenItsUsageCrossesTheTokenBudget() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 60, 30),
                FakeResponse.builder().text("done").usage(new Usage(20, 20, 20)).build()));
        try (var session = session(provider, Map.of("cafecli.react.token.budget", "100"))) {
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "start"));
            assertEquals(2, provider.requests().size());
        }
    }

    @Test void stopContinuationHonorsBudgetAfterAllParticipantsAndKeepsItsEntries() {
        Capability stop = stopText();
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().text("done")
                .usage(new Usage(100, 0, 0)).build()));
        try (var session = session(provider, Map.of("cafecli.react.token.budget", "100"), stop)) {
            var outcome = assertInstanceOf(RunOutcome.BudgetExhausted.class, SessionTestSupport.run(session, "start"));
            assertEquals(new BudgetExit.Tokens(100, 100), outcome.reason());
            assertEquals(new StopBlock("verify"), session.log().getLast());
            assertEquals(1, provider.requests().size());
        }
    }

    @Test void textOnlyStopContinuationDoesNotEraseTheSourceToolSignatureWindow() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 0, 0), call("two", "{}", 0, 0),
                FakeResponse.text("not yet"), call("three", "{}", 0, 0), FakeResponse.text("unexpected")));
        try (var session = session(provider, Map.of(), stopText())) {
            var outcome = assertInstanceOf(RunOutcome.BudgetExhausted.class, SessionTestSupport.run(session, "start"));
            assertEquals(new BudgetExit.Stagnation(3), outcome.reason());
            assertEquals(4, provider.requests().size());
        }
    }

    @Test void stagnationWinsOverTokensAndTheHardCap() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 50, 0), call("two", "{}", 50, 0)));
        try (var session = session(provider, Map.of("cafecli.react.token.budget", "100",
                "cafecli.react.stagnation.window", "2", "cafecli.react.hard.max.iterations", "2"))) {
            assertEquals(new BudgetExit.Stagnation(2), assertInstanceOf(RunOutcome.BudgetExhausted.class,
                    SessionTestSupport.run(session, "start")).reason());
        }
    }

    @Test void differentRawArgumentsAndSourceOrderResetStagnationWhileIdsDoNotMatter() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 0, 0), call("two", "{ }", 0, 0),
                FakeResponse.builder().toolCall("a", "first", "{}").toolCall("b", "second", "{}").build(),
                FakeResponse.builder().toolCall("c", "second", "{}").toolCall("d", "first", "{}").build(),
                FakeResponse.text("done")));
        try (var session = session(provider, Map.of("cafecli.react.stagnation.window", "2"))) {
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "start"));
            assertEquals(5, provider.requests().size());
        }
    }

    @Test void runAndClearResetBudgetsAndTheDefaultIsNotTheModelWindow() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 100000, 0), FakeResponse.text("done"),
                call("two", "{}", 100000, 0), FakeResponse.text("done"),
                call("three", "{}", 100000, 0), FakeResponse.text("done")));
        try (var session = session(provider, Map.of("cafecli.react.stagnation.window", "2"))) {
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "first"));
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "second"));
            session.clear();
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "third"));
            assertEquals(6, provider.requests().size());
        }
    }

    @Test void tokenCounterIsRunLocalAndNegativeUsageCannotBuyMoreBudget() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 60, 30), FakeResponse.text("done"),
                call("two", "{}", 60, 30), call("three", "{ }", -100, 20)));
        try (var session = session(provider, Map.of("cafecli.react.token.budget", "100"))) {
            assertInstanceOf(RunOutcome.Completed.class, SessionTestSupport.run(session, "first"));
            assertEquals(new BudgetExit.Tokens(100, 110), assertInstanceOf(RunOutcome.BudgetExhausted.class,
                    SessionTestSupport.run(session, "second")).reason());
        }
    }

    @Test void coreRetainsItsHardCapWhenNoHarnessBudgetIsExhausted() {
        var provider = FakeProvider.scripted(List.of(call("one", "{}", 0, 0), call("two", "{ }", 0, 0)));
        try (var session = session(provider, Map.of("cafecli.react.hard.max.iterations", "2"))) {
            assertEquals(new BudgetReason.IterationCap(2), assertInstanceOf(RunOutcome.BudgetExhausted.class,
                    SessionTestSupport.run(session, "start")).reason());
        }
    }

    private static Capability stopText() {
        return new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(StopBlock.KIND);
                scope.iterationEnd((message, results) -> message.toolCalls().isEmpty()
                        ? IterationDecision.proceed(new StopBlock("verify")) : IterationDecision.proceed());
            }
        };
    }
    private static FakeResponse call(String id, String arguments, int input, int output) {
        return FakeResponse.builder().toolCall(id, "missing", arguments)
                .usage(new Usage(input, output, Math.max(0, input))).build();
    }

    private AgentSession session(FakeProvider provider, Map<String, String> properties, Capability... extra) {
        var config = ConfigLoader.load(new ConfigSources(properties, Map.of(), root.resolve("home"), root));
        List<Capability> capabilities = new ArrayList<>();
        capabilities.add(new BudgetCapability(config.budget())); capabilities.addAll(List.of(extra));
        return AgentSession.builder().provider(provider).model(MODEL).config(config).capabilities(capabilities).build();
    }
}
