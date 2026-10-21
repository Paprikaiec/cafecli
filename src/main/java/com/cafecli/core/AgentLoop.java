package com.cafecli.core;

import com.cafecli.ai.AssistantStream;
import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.StreamOptions;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent.IterationEnd;
import com.cafecli.core.AgentEvent.IterationStart;
import com.cafecli.core.AgentEvent.MessageEnd;
import com.cafecli.core.AgentEvent.MessageStart;
import com.cafecli.core.AgentEvent.MessageUpdate;
import com.cafecli.core.AgentEvent.Origin;
import com.cafecli.core.AgentEvent.RunEnd;
import com.cafecli.core.AgentEvent.RunStart;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;

import java.util.List;

/**
 * One run of an {@link Agent}: holds only per-run state (the token, the origin of its events) and does everything else
 * through the agent's {@link AgentCallbacks}. Built by {@link Agent#run}, used once.
 *
 * <p>A run: append the input; run-start callback; then iterations until one ends the run. An iteration: drain the
 * boundary inbox; iteration-boundary callback; build the context projection and send it as a request (on a context
 * overflow: the overflow callback, then one retry); log the answer with its tool batch's results; iteration-end
 * callback. Cancellation and the hard cap are checked before each iteration, cancellation also after each request.
 *
 * <p>The request uses the run's own token: cancelling the run aborts the request in flight, and nothing else needs a
 * request of its own to abort (yet).
 */
final class AgentLoop {

    private final Agent agent;
    private final CancellationToken cancellation;
    private final Origin origin;

    AgentLoop(Agent agent, CancellationToken cancellation) {
        this.agent = agent;
        this.cancellation = cancellation;
        this.origin = new Origin(agent.id());
    }

    RunOutcome run(UserMessage input) {
        agent.append(new UserEntry(input));
        agent.emit(new RunStart(origin));
        agent.callbacks().onRunStart(input, cancellation).forEach(agent::append);
        RunOutcome outcome = iterate();
        agent.emit(new RunEnd(origin, outcome));
        return outcome;
    }

    private RunOutcome iterate() {
        for (int iteration = 1; ; iteration++) {
            if (cancellation.isCancelled()) {
                return new RunOutcome.Cancelled();
            }
            if (iteration > agent.maxIterations()) {
                return new RunOutcome.BudgetExhausted(new BudgetReason.IterationCap(agent.maxIterations()));
            }
            agent.emit(new IterationStart(origin, iteration));
            RunOutcome outcome = runIteration();
            agent.emit(new IterationEnd(origin, iteration));
            if (outcome != null) {
                return outcome;
            }
        }
    }

    /** One iteration; returns the outcome if the run ends with it, {@code null} if the run goes on. */
    private RunOutcome runIteration() {
        agent.drainInbox();
        agent.callbacks().onIterationBoundary(agent.log(), cancellation).forEach(agent::append);
        StreamEvent terminal = request();
        if (isOverflow(terminal) && !cancellation.isCancelled()) {
            // Once per request: an empty recovery (no callback) means nothing changed, so a retry would overflow too.
            List<CustomEntry> recovery = agent.callbacks().onContextOverflow(agent.log(), cancellation);
            if (!recovery.isEmpty()) {
                recovery.forEach(agent::append);
                terminal = request();
            }
        }
        // A cancelled answer is not kept, complete or not (see Agent): the log stays as it was before the request,
        // and no tool of it runs. A cancel that lands after the stream finished is still a cancel.
        if (cancellation.isCancelled()) {
            return new RunOutcome.Cancelled();
        }
        if (terminal instanceof StreamEvent.Failed failed) {
            // The partial answer of a failed request is not logged either.
            return new RunOutcome.Failed(failed.kind(), failed.message());
        }
        AssistantMessage message = ((StreamEvent.Done) terminal).message();
        List<ToolResultEntry> results = new ToolBatch(origin, agent::emit).run(message.toolCalls());
        // The answer and its results go in together, after the batch: whatever is thrown during the batch, the log
        // never holds a tool call without its result.
        agent.append(new AssistantEntry(message));
        results.forEach(agent::append);
        IterationDecision decision = agent.callbacks().onIterationEnd(message, results);
        decision.entries().forEach(agent::append);
        return switch (decision) {
            case IterationDecision.Proceed ignored -> null;
            case IterationDecision.End ignored -> new RunOutcome.Completed(message);
            case IterationDecision.Exhausted exhausted -> new RunOutcome.BudgetExhausted(exhausted.reason());
        };
    }

    private static boolean isOverflow(StreamEvent terminal) {
        return terminal instanceof StreamEvent.Failed failed && failed.kind() == ErrorKind.CONTEXT_OVERFLOW;
    }

    /**
     * Builds the request as the context projection of the log — transform, convert, wrap with the system prompt and
     * the tools, shape — and streams it, emitting the message events. Returns the stream's terminal event: {@link StreamEvent.Done} or
     * {@link StreamEvent.Failed}.
     */
    private StreamEvent request() {
        AgentCallbacks callbacks = agent.callbacks();
        List<Message> messages = callbacks.convertToLlm(callbacks.transformContext(agent.log()));
        // No tools until ticket 05; prepareRequest may still set the per-agent tool list.
        Context request = callbacks.prepareRequest(new Context(agent.systemPrompt(), messages, List.of()));
        StreamEvent terminal = null;
        try (AssistantStream stream = agent.provider().stream(agent.model(), request,
                new StreamOptions(cancellation))) {
            for (StreamEvent event : stream) {
                switch (event) {
                    case StreamEvent.Start ignored -> agent.emit(new MessageStart(origin));
                    case StreamEvent.Retry retry -> agent.emit(new AgentEvent.Retry(origin, retry));
                    case StreamEvent.Done done -> {
                        terminal = done;
                        agent.emit(new MessageEnd(origin, done.message()));
                    }
                    case StreamEvent.Failed failed -> {
                        terminal = failed;
                        agent.emit(new MessageEnd(origin, failed.partial()));
                    }
                    default -> agent.emit(new MessageUpdate(origin, event));
                }
            }
        }
        return terminal;
    }
}
