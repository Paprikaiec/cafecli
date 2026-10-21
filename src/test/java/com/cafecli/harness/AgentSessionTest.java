package com.cafecli.harness;

import com.cafecli.ai.Context;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.AgentEvent.IterationEnd;
import com.cafecli.core.AgentEvent.IterationStart;
import com.cafecli.core.AgentEvent.MessageEnd;
import com.cafecli.core.AgentEvent.MessageStart;
import com.cafecli.core.AgentEvent.MessageUpdate;
import com.cafecli.core.AgentEvent.RunEnd;
import com.cafecli.core.AgentEvent.RunStart;
import com.cafecli.core.BudgetReason;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.HarnessConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 1 (PRD "Testing Decisions"): the v2 {@link AgentSession}, driven by the fake provider, against a temporary
 * user home and project. Assertions are on what the model was sent (the requests the fake recorded), the events
 * subscribers received, the agent log, and the typed outcome.
 */
class AgentSessionTest {

    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());

    @TempDir
    Path root;

    private Path home;
    private Path project;

    @BeforeEach
    void dirs() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void theRequestHoldsTheSystemPromptBuiltFromTheTemplatesAndTheUsersMessage() throws IOException {
        // A project override of the last template, so the prompt's end is known without restating the templates.
        Path handoff = Files.createDirectories(project.resolve(".cafecli/prompts")).resolve("handoff.md");
        Files.writeString(handoff, "## Handoff\n\nProject handoff override.\n");
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello")));

        session(provider).submit("What is 2 + 2?");

        assertEquals(1, provider.requests().size());
        Context request = provider.requests().get(0).context();
        assertEquals(List.of(UserMessage.text("What is 2 + 2?")), request.messages());
        String systemPrompt = request.systemPrompt();
        assertTrue(systemPrompt.startsWith("## Identity\n\n你是 Cafecli，一个面向代码库工作的智能编程 Agent。"),
                "base.md comes first");
        assertTrue(systemPrompt.contains("## Mode: ReAct Agent"), "the agent mode template");
        assertTrue(systemPrompt.contains("## Approval Mode\n\n危险工具可能触发 HITL 审批。"),
                "v1's default approval mode, suggest");
        assertTrue(systemPrompt.contains("## Runtime Context"), "the date and zone");
        assertTrue(systemPrompt.endsWith("## Handoff\n\nProject handoff override."), systemPrompt);
    }

    @Test
    void aPlainAnswerArrivesAsRunIterationAndMessageEventsAndCompletesWithTheAnswer() {
        AgentSession session = session(FakeProvider.scripted(List.of(FakeResponse.text("2 + 2 = 4"))));
        List<AgentEvent> events = new ArrayList<>();
        session.subscribe(events::add);

        RunOutcome outcome = session.submit("What is 2 + 2?");

        assertEquals("2 + 2 = 4", assertInstanceOf(RunOutcome.Completed.class, outcome).message().text());
        assertEquals(List.of(RunStart.class, IterationStart.class, MessageStart.class, MessageUpdate.class,
                MessageEnd.class, IterationEnd.class, RunEnd.class), typesCollapsingRepeats(events));
        assertEquals(outcome, assertInstanceOf(RunEnd.class, events.get(events.size() - 1)).outcome());
        assertEquals(Set.of("main"), events.stream().map(AgentEvent::agentId).collect(Collectors.toSet()));
    }

    @Test
    void aModelThatNeverStopsEndsAtTheConfiguredHardCapAsBudgetExhausted() {
        // Always another tool call; each id names the request it answers, so every call is distinct.
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.builder()
                .toolCall("call_" + request.context().messages().size(), "read_file", "{\"path\":\"a.txt\"}")
                .build());
        AgentSession session = session(provider, Map.of("cafecli.react.hard.max.iterations", "4"));

        RunOutcome outcome = session.submit("Read a.txt forever");

        assertEquals(new RunOutcome.BudgetExhausted(new BudgetReason.IterationCap(4)), outcome);
        assertEquals(4, provider.requests().size());
    }

    @Test
    void cancelStopsTheRunInFlightAndTheNextSubmissionRunsNormally() {
        // Tool calls until the user says thanks.
        FakeProvider provider = FakeProvider.responding(request ->
                request.context().messages().contains(UserMessage.text("Thanks"))
                        ? FakeResponse.text("fine")
                        : FakeResponse.builder()
                        .toolCall("call_" + request.context().messages().size(), "read_file", "{}")
                        .build());
        AgentSession session = session(provider);
        // Cancels from a subscriber, on the run's own thread, once the second iteration is over.
        session.subscribe(event -> {
            if (event instanceof IterationEnd end && end.iteration() == 2) {
                session.cancel();
            }
        });

        RunOutcome cancelled = session.submit("Read a.txt forever");
        int requestsOfTheCancelledRun = provider.requests().size();
        RunOutcome next = session.submit("Thanks");

        assertEquals(new RunOutcome.Cancelled(), cancelled);
        assertEquals(2, requestsOfTheCancelledRun);
        assertEquals("fine", assertInstanceOf(RunOutcome.Completed.class, next).message().text());
    }

    @Test
    void cancelWhileIdleDoesNothing() {
        AgentSession session = session(FakeProvider.scripted(List.of(FakeResponse.text("fine"))));

        session.cancel();

        assertInstanceOf(RunOutcome.Completed.class, session.submit("hello"));
    }

    @Test
    void awaitIdleReturnsOnceTheRunInFlightHasEndedAndASecondSubmitMeanwhileIsRefused() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> {
            requested.countDown();
            await(release);
            return FakeResponse.text("done");
        });
        AgentSession session = session(provider);
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> session.submit("hello"));
        assertTrue(requested.await(5, TimeUnit.SECONDS), "the run never made its request");

        CompletableFuture<Void> idle = CompletableFuture.runAsync(() -> {
            try {
                session.awaitIdle();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThrows(IllegalStateException.class, () -> session.submit("meanwhile"));
        assertThrows(TimeoutException.class, () -> idle.get(100, TimeUnit.MILLISECONDS));
        release.countDown();

        idle.get(5, TimeUnit.SECONDS);
        assertInstanceOf(RunOutcome.Completed.class, run.get(5, TimeUnit.SECONDS));
        session.awaitIdle();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the Capability SPI ----

    @Test
    void anEventListenerACapabilityRegistersHearsEveryEventOfTheSessionBeforeItsSubscribers() {
        List<String> heard = new ArrayList<>();
        Capability listening = new Capability() {
            @Override
            public void attachSession(SessionRegistrar session) {
                session.eventListener(event -> heard.add("capability " + event.getClass().getSimpleName()));
            }
        };
        AgentSession session = session(FakeProvider.scripted(List.of(FakeResponse.text("Hi"))), listening);
        List<AgentEvent> subscribed = new ArrayList<>();
        session.subscribe(event -> {
            subscribed.add(event);
            heard.add("subscriber " + event.getClass().getSimpleName());
        });

        session.submit("hello");

        List<String> eachEventCapabilityFirst = new ArrayList<>();
        for (AgentEvent event : subscribed) {
            eachEventCapabilityFirst.add("capability " + event.getClass().getSimpleName());
            eachEventCapabilityFirst.add("subscriber " + event.getClass().getSimpleName());
        }
        assertEquals(eachEventCapabilityFirst, heard);
        assertInstanceOf(RunStart.class, subscribed.get(0));
        assertInstanceOf(RunEnd.class, subscribed.get(subscribed.size() - 1));
    }

    @Test
    void aRunStartWriterACapabilityRegistersForTheMainAgentAppendsRightAfterTheUsersEntry() {
        Capability noting = new Capability() {
            @Override
            public void attachAgent(AgentScope agent) {
                agent.runStartWriter((input, cancellation) -> List.of(
                        new Note(agent.agentId() + " noted: " + ((TextBlock) input.content().get(0)).text())));
            }
        };
        AgentSession session = session(FakeProvider.scripted(List.of(FakeResponse.text("Hi"))), noting);

        RunOutcome outcome = session.submit("hello");

        AssistantMessage answer = assertInstanceOf(RunOutcome.Completed.class, outcome).message();
        assertEquals(List.of(new UserEntry(UserMessage.text("hello")), new Note("main noted: hello"),
                new AssistantEntry(answer)), session.log());
    }

    /** A custom kind of this test's own. */
    record Note(String text) implements CustomEntry {
    }

    /** The event types in order, with runs of the same type (the message updates) collapsed into one. */
    private static List<Class<?>> typesCollapsingRepeats(List<AgentEvent> events) {
        List<Class<?>> types = new ArrayList<>();
        for (AgentEvent event : events) {
            if (types.isEmpty() || types.get(types.size() - 1) != event.getClass()) {
                types.add(event.getClass());
            }
        }
        return types;
    }

    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        return session(provider, Map.of(), capabilities);
    }

    private AgentSession session(FakeProvider provider, Map<String, String> properties, Capability... capabilities) {
        HarnessConfig config = ConfigLoader.load(new ConfigSources(properties, Map.of(), home, project));
        return AgentSession.builder()
                .config(config)
                .provider(provider)
                .model(MODEL)
                .capabilities(List.of(capabilities))
                .build();
    }
}
