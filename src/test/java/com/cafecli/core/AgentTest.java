package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.Model;
import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.ToolDefinition;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent.IterationEnd;
import com.cafecli.core.AgentEvent.IterationStart;
import com.cafecli.core.AgentEvent.MessageEnd;
import com.cafecli.core.AgentEvent.MessageStart;
import com.cafecli.core.AgentEvent.MessageUpdate;
import com.cafecli.core.AgentEvent.RunEnd;
import com.cafecli.core.AgentEvent.RunStart;
import com.cafecli.core.AgentEvent.ToolEnd;
import com.cafecli.core.AgentEvent.ToolStart;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The core seam: an {@link Agent} driven by the fake provider. Assertions are on what the model was sent (the requests
 * the fake recorded), the events subscribers received, the agent log, and the run outcome.
 */
class AgentTest {

    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());

    @Test
    void theRequestHoldsTheSystemPromptAndTheUsersMessage() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello")));
        Agent agent = agent(provider).systemPrompt("You are Cafecli.").build();

        agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(1, provider.requests().size());
        Context request = provider.requests().get(0).context();
        assertEquals("You are Cafecli.", request.systemPrompt());
        assertEquals(List.of(UserMessage.text("hi")), request.messages());
        assertEquals(List.of(), request.tools());
    }

    @Test
    void aPlainAnswerArrivesAsRunIterationAndMessageEventsInOrder() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello, world")));
        Agent agent = agent(provider).build();
        List<AgentEvent> events = recordEvents(agent);

        agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(List.of(RunStart.class, IterationStart.class, MessageStart.class, MessageUpdate.class,
                MessageEnd.class, IterationEnd.class, RunEnd.class), typesCollapsingRepeats(events));
        StringBuilder streamed = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof MessageUpdate update && update.event() instanceof StreamEvent.TextDelta delta) {
                assertEquals(0, delta.index());
                streamed.append(delta.delta());
            }
        }
        assertEquals("Hello, world", streamed.toString());
    }

    @Test
    void eachToolCallIsBracketedByToolStartAndToolEndBetweenTheMessageAndTheEndOfItsIteration() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build(),
                FakeResponse.text("done")));
        Agent agent = agent(provider).build();
        List<AgentEvent> events = recordEvents(agent);

        agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of(RunStart.class,
                IterationStart.class, MessageStart.class, MessageUpdate.class, MessageEnd.class,
                ToolStart.class, ToolEnd.class, IterationEnd.class,
                IterationStart.class, MessageStart.class, MessageUpdate.class, MessageEnd.class, IterationEnd.class,
                RunEnd.class), typesCollapsingRepeats(events));
        assertEquals(new ToolCall("call_1", "read_file", "{}"), only(events, ToolStart.class).call());
        assertEquals(new ToolResultEntry(unknownTool("call_1")), only(events, ToolEnd.class).result());
    }

    @Test
    void aProviderRetryReachesSubscribersAsARetryEventNotAsAMessageUpdate() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().retry(1, 3, "429 rate limited").text("ok").build()));
        Agent agent = agent(provider).build();
        List<AgentEvent> events = recordEvents(agent);

        agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(new StreamEvent.Retry(1, 3, "429 rate limited"),
                only(events, AgentEvent.Retry.class).attempt());
        assertEquals(List.of(), events.stream()
                .filter(event -> event instanceof MessageUpdate update && update.event() instanceof StreamEvent.Retry)
                .toList());
    }

    @Test
    void aPlainAnswerCompletesWithTheAnswerAndTheLogHoldsTheExchange() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello")));
        Agent agent = agent(provider).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        AssistantMessage answer = assertInstanceOf(RunOutcome.Completed.class, outcome).message();
        assertEquals("Hello", answer.text());
        assertEquals(List.of(new UserEntry(UserMessage.text("hi")), new AssistantEntry(answer)), agent.log());
    }

    @Test
    void aModelThatNeverStopsCallingToolsEndsAtTheHardCapWithItsUnknownToolErrorsInTheLog() {
        // Each call id is unique: it is named after the number of messages in the request.
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.builder()
                .toolCall("call_" + request.context().messages().size(), "read_file", "{\"path\":\"a.txt\"}")
                .build());
        Agent agent = agent(provider).maxIterations(3).build();

        RunOutcome outcome = agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(new RunOutcome.BudgetExhausted(new BudgetReason.IterationCap(3)), outcome);
        assertEquals(3, provider.requests().size());
        assertEquals(List.of(unknownTool("call_1"), unknownTool("call_3"), unknownTool("call_5")),
                toolResults(agent.log()));
    }

    @Test
    void cancellingMidStreamEndsTheRunCancelledAndLeavesALogALaterRunContinuesFromValidly() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Let me read it.")
                        .toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}").build(),
                FakeResponse.text("Sure.")));
        Agent agent = agent(provider).build();
        CancellationToken cancellation = new CancellationToken();
        // Cancel while the tool call's arguments stream: what was streamed so far ends in a truncated tool call.
        agent.subscribe(event -> {
            if (event instanceof MessageUpdate update && update.event() instanceof StreamEvent.ToolCallDelta) {
                cancellation.cancel();
            }
        });

        RunOutcome outcome = agent.run(UserMessage.text("read a.txt"), cancellation);
        RunOutcome next = agent.run(UserMessage.text("never mind"), new CancellationToken());

        assertEquals(new RunOutcome.Cancelled(), outcome);
        assertInstanceOf(RunOutcome.Completed.class, next);
        // Nothing of the cancelled answer is sent back: no tool call without a result, no half message.
        assertEquals(List.of(UserMessage.text("read a.txt"), UserMessage.text("never mind")),
                provider.requests().get(1).context().messages());
    }

    @Test
    void aCancelThatLandsAfterTheAnswerFinishedStreamingKeepsNothingOfItAndRunsNoTools() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build()));
        Agent agent = agent(provider).build();
        CancellationToken cancellation = new CancellationToken();
        List<AgentEvent> events = recordEvents(agent);
        agent.subscribe(event -> {
            if (event instanceof MessageEnd end && end.message().stopReason() == StopReason.TOOL_USE) {
                cancellation.cancel();
            }
        });

        RunOutcome outcome = agent.run(UserMessage.text("go"), cancellation);

        assertEquals(new RunOutcome.Cancelled(), outcome);
        assertEquals(List.of(new UserEntry(UserMessage.text("go"))), agent.log());
        assertEquals(List.of(), events.stream().filter(ToolStart.class::isInstance).toList());
    }

    @Test
    void aFatalErrorEndsTheRunFailedWithThatKindAndLogsNothingOfTheHalfAnswer() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Half an ans").fail(ErrorKind.FATAL, "400 bad request")));
        Agent agent = agent(provider).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(ErrorKind.FATAL, assertInstanceOf(RunOutcome.Failed.class, outcome).kind());
        assertEquals(List.of(new UserEntry(UserMessage.text("hi"))), agent.log());
    }

    @Test
    void eachRequestIsTheLogThroughTransformContextThenConvertToLlmAndProjectingNeverWritesTheLog() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("ok")));
        Agent agent = agent(provider).callbacks(new ShowingNotes() {
            @Override
            public List<LogEntry> transformContext(List<LogEntry> log) {
                List<LogEntry> view = new ArrayList<>(log);
                view.add(new Note("added by a projection stage"));
                return view;
            }
        }).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(List.of(UserMessage.text("hi"), UserMessage.text("[note] added by a projection stage")),
                provider.requests().get(0).context().messages());
        AssistantMessage answer = ((RunOutcome.Completed) outcome).message();
        assertEquals(List.of(new UserEntry(UserMessage.text("hi")), new AssistantEntry(answer)), agent.log());
    }

    @Test
    void prepareRequestHasTheLastWordOnTheRequestIncludingTheToolList() {
        ToolDefinition readFile = new ToolDefinition("read_file", "Reads a file",
                JsonNodeFactory.instance.objectNode().put("type", "object"));
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("ok")));
        Agent agent = agent(provider).systemPrompt("You are Cafecli.").callbacks(new AgentCallbacks() {
            @Override
            public Context prepareRequest(Context request) {
                return new Context(request.systemPrompt() + " Be brief.", request.messages(), List.of(readFile));
            }
        }).build();

        agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(new Context("You are Cafecli. Be brief.", List.of(UserMessage.text("hi")), List.of(readFile)),
                provider.requests().get(0).context());
    }

    @Test
    void anEntryPostedToTheInboxDuringARunReachesTheModelAtTheNextIterationBoundary() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build(),
                FakeResponse.text("done")));
        Agent agent = agent(provider).callbacks(new ShowingNotes()).build();
        agent.subscribe(event -> {
            if (event instanceof MessageStart && provider.requests().size() == 1) {
                agent.post(new Note("posted while the first answer streams"));
            }
        });

        agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of(UserMessage.text("go")), provider.requests().get(0).context().messages());
        List<Message> second = provider.requests().get(1).context().messages();
        assertEquals(List.of(unknownTool("call_1"), UserMessage.text("[note] posted while the first answer streams")),
                second.subList(2, second.size()));
    }

    @Test
    void iterationBoundaryEntriesFollowTheDrainedInboxIntoTheLogAndIntoThatIterationsRequest() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build(),
                FakeResponse.text("done")));
        List<List<LogEntry>> seen = new ArrayList<>();
        Agent agent = agent(provider).callbacks(new ShowingNotes() {
            @Override
            public List<CustomEntry> onIterationBoundary(List<LogEntry> log, CancellationToken cancellation) {
                seen.add(log);
                return List.of(new Note("boundary " + seen.size()));
            }
        }).build();
        agent.post(new Note("posted before the run"));

        agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of(new UserEntry(UserMessage.text("go")), new Note("posted before the run")), seen.get(0));
        assertEquals(List.of(UserMessage.text("go"), UserMessage.text("[note] posted before the run"),
                UserMessage.text("[note] boundary 1")), provider.requests().get(0).context().messages());
        List<Message> second = provider.requests().get(1).context().messages();
        assertEquals(UserMessage.text("[note] boundary 2"), second.get(second.size() - 1));
        assertEquals(List.of(new Note("posted before the run"), new Note("boundary 1"), new Note("boundary 2")),
                agent.log().stream().filter(Note.class::isInstance).toList());
    }

    @Test
    void anIterationEndThatSaysEndStopsTheRunAndItsEntriesLandInTheLog() {
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.builder()
                .toolCall("call_" + request.context().messages().size(), "read_file", "{}").build());
        Agent agent = agent(provider).maxIterations(3).callbacks(new AgentCallbacks() {
            @Override
            public IterationDecision onIterationEnd(AssistantMessage message, List<ToolResultEntry> results) {
                return IterationDecision.end(new Note("stopped by a participant"));
            }
        }).build();

        RunOutcome outcome = agent.run(UserMessage.text("go"), new CancellationToken());

        assertInstanceOf(RunOutcome.Completed.class, outcome);
        assertEquals(1, provider.requests().size());
        List<LogEntry> log = agent.log();
        assertEquals(List.of(new ToolResultEntry(unknownTool("call_1")), new Note("stopped by a participant")),
                log.subList(log.size() - 2, log.size()));
    }

    @Test
    void anIterationEndParticipantCanEndTheRunWithItsOwnBudgetReasonAndEntries() {
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.builder()
                .toolCall("call_" + request.context().messages().size(), "read_file", "{}").build());
        Agent agent = agent(provider).callbacks(new AgentCallbacks() {
            @Override
            public IterationDecision onIterationEnd(AssistantMessage message, List<ToolResultEntry> results) {
                return IterationDecision.exhausted(new Stagnation(3), new Note("same call three times"));
            }
        }).build();

        RunOutcome outcome = agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(new RunOutcome.BudgetExhausted(new Stagnation(3)), outcome);
        assertEquals(1, provider.requests().size());
        List<LogEntry> log = agent.log();
        assertEquals(new Note("same call three times"), log.get(log.size() - 1));
    }

    /** A budget reason the harness defines, as ticket 12's stagnation detection will. */
    record Stagnation(int window) implements BudgetReason {
    }

    @Test
    void anOverflowIsHandedToTheCallbackOnceAndTheRequestRetriedWithWhatItAppended() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().fail(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long"),
                FakeResponse.text("fits now")));
        AtomicInteger overflows = new AtomicInteger();
        Agent agent = agent(provider).callbacks(compactingOnOverflow(overflows)).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals("fits now", assertInstanceOf(RunOutcome.Completed.class, outcome).message().text());
        assertEquals(1, overflows.get());
        assertEquals(2, provider.requests().size());
        assertEquals(List.of(UserMessage.text("hi"), UserMessage.text("[note] summary")),
                provider.requests().get(1).context().messages());
    }

    @Test
    void aSecondOverflowOfTheRetriedRequestEndsTheRunFailedWithOverflow() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().fail(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long"),
                FakeResponse.builder().fail(ErrorKind.CONTEXT_OVERFLOW, "still too long"),
                FakeResponse.text("never requested")));
        AtomicInteger overflows = new AtomicInteger();
        Agent agent = agent(provider).callbacks(compactingOnOverflow(overflows)).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(ErrorKind.CONTEXT_OVERFLOW, assertInstanceOf(RunOutcome.Failed.class, outcome).kind());
        assertEquals(1, overflows.get());
        assertEquals(2, provider.requests().size());
    }

    @Test
    void withoutAnOverflowCallbackAnOverflowEndsTheRunFailedWithOverflowAndIsNotRetried() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().fail(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long"),
                FakeResponse.text("never requested")));
        Agent agent = agent(provider).build();

        RunOutcome outcome = agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(ErrorKind.CONTEXT_OVERFLOW, assertInstanceOf(RunOutcome.Failed.class, outcome).kind());
        assertEquals(1, provider.requests().size());
    }

    @Test
    void runStartEntriesLandRightAfterTheUsersEntryAheadOfTheInboxAndReachTheFirstRequest() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("ok")));
        Agent agent = agent(provider).callbacks(new ShowingNotes() {
            @Override
            public List<CustomEntry> onRunStart(UserMessage input, CancellationToken cancellation) {
                return List.of(new Note("recalled for: " + ((TextBlock) input.content().get(0)).text()));
            }
        }).build();
        agent.post(new Note("posted before the run"));

        agent.run(UserMessage.text("hi"), new CancellationToken());

        assertEquals(List.of(new UserEntry(UserMessage.text("hi")), new Note("recalled for: hi"),
                new Note("posted before the run")), agent.log().subList(0, 3));
        assertEquals(List.of(UserMessage.text("hi"), UserMessage.text("[note] recalled for: hi"),
                UserMessage.text("[note] posted before the run")), provider.requests().get(0).context().messages());
    }

    @Test
    void everyEventCarriesTheIdOfTheAgentThatEmittedIt() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build(),
                FakeResponse.text("done")));
        Agent agent = agent(provider).id("sub-7").build();
        List<AgentEvent> events = recordEvents(agent);

        agent.run(UserMessage.text("go"), new CancellationToken());

        assertEquals(List.of("sub-7"), events.stream().map(AgentEvent::agentId).distinct().toList());
    }

    @Test
    void aRunCutShortByAThrowingSubscriberMidBatchLeavesNoToolCallWithoutItsResult() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{}").build(),
                FakeResponse.text("ok")));
        Agent agent = agent(provider).build();
        Agent.Subscription faulty = agent.subscribe(event -> {
            if (event instanceof ToolStart) {
                throw new IllegalStateException("a buggy subscriber");
            }
        });
        assertThrows(IllegalStateException.class, () -> agent.run(UserMessage.text("go"), new CancellationToken()));
        faulty.close();

        agent.run(UserMessage.text("again"), new CancellationToken());

        assertEquals(List.of(UserMessage.text("go"), UserMessage.text("again")),
                provider.requests().get(1).context().messages());
    }

    @Test
    void anAgentNeedsAPositiveHardCapBecauseCoreHasNoDefaultOfItsOwn() {
        FakeProvider provider = FakeProvider.scripted(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> Agent.builder().id("main").provider(provider).model(MODEL).build());
        assertThrows(IllegalArgumentException.class, () -> agent(provider).maxIterations(0).build());
    }

    @Test
    void aClosedSubscriptionReceivesNoFurtherEvents() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two")));
        Agent agent = agent(provider).build();
        List<AgentEvent> events = new ArrayList<>();
        Agent.Subscription subscription = agent.subscribe(events::add);
        agent.run(UserMessage.text("first"), new CancellationToken());
        int received = events.size();

        subscription.close();
        agent.run(UserMessage.text("second"), new CancellationToken());

        assertEquals(received, events.size());
    }

    /** A custom message kind defined by the test, as the harness defines its own. */
    record Note(String text) implements CustomEntry {
    }

    /** Callbacks whose {@code convertToLlm} shows each {@link Note} to the model as tagged user text. */
    static class ShowingNotes implements AgentCallbacks {
        @Override
        public List<Message> convertToLlm(List<LogEntry> entries) {
            List<Message> messages = new ArrayList<>();
            for (LogEntry entry : entries) {
                if (entry instanceof Note note) {
                    messages.add(UserMessage.text("[note] " + note.text()));
                } else {
                    messages.addAll(AgentCallbacks.super.convertToLlm(List.of(entry)));
                }
            }
            return messages;
        }
    }

    /** Stands in for the harness's compaction: counts the overflows it is handed and appends a summary. */
    private static AgentCallbacks compactingOnOverflow(AtomicInteger overflows) {
        return new ShowingNotes() {
            @Override
            public List<CustomEntry> onContextOverflow(List<LogEntry> log, CancellationToken cancellation) {
                overflows.incrementAndGet();
                return List.of(new Note("summary"));
            }
        };
    }

    /** v1's model-facing text for a call to a tool nobody registered ({@code ToolRegistry}). */
    private static ToolResultMessage unknownTool(String callId) {
        return new ToolResultMessage(callId, "read_file", List.of(new TextBlock("未知工具: read_file")), true);
    }

    private static List<ToolResultMessage> toolResults(List<LogEntry> log) {
        List<ToolResultMessage> results = new ArrayList<>();
        for (LogEntry entry : log) {
            if (entry instanceof ToolResultEntry result) {
                results.add(result.message());
            }
        }
        return results;
    }

    private static List<AgentEvent> recordEvents(Agent agent) {
        List<AgentEvent> events = new ArrayList<>();
        agent.subscribe(events::add);
        return events;
    }

    private static <E extends AgentEvent> E only(List<AgentEvent> events, Class<E> type) {
        List<E> matching = events.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matching.size(), type.getSimpleName());
        return matching.get(0);
    }

    /** The event types in order, with a run of consecutive events of one type (e.g. message updates) shown once. */
    private static List<Class<?>> typesCollapsingRepeats(List<AgentEvent> events) {
        List<Class<?>> types = new ArrayList<>();
        for (AgentEvent event : events) {
            if (types.isEmpty() || types.get(types.size() - 1) != event.getClass()) {
                types.add(event.getClass());
            }
        }
        return types;
    }

    private static Agent.Builder agent(FakeProvider provider) {
        return Agent.builder().id("main").provider(provider).model(MODEL).maxIterations(50);
    }
}
