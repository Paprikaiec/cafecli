package com.cafecli.core;

import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.log.ToolResultEntry;

/**
 * What an agent reports while it runs, delivered synchronously to its subscribers on the loop thread (see
 * {@link Agent#subscribe}: subscribers only enqueue). Data only — no user-facing text; the UI decides what to show.
 *
 * <p>Order within a run: {@link RunStart}; then per iteration {@link IterationStart}, the message events of its
 * request(s), the batch's {@link ToolStart} / {@link ToolUpdate} / {@link ToolEnd} events in execution order,
 * an optional {@link ToolBatchEnd} with normalized publication content, {@link IterationEnd}; then
 * {@link RunEnd} with the outcome. A request's events are {@link MessageStart}, {@link MessageUpdate}s,
 * {@link MessageEnd}, with {@link Retry} anywhere between start and end. An overflow retry is a second request in the
 * same iteration. Every start has its end, however the run ends — except when a callback or subscriber throws.
 *
 * <p>Preflight of every call precedes all tool events. Parallel calls may interleave; their log entries stay in call
 * order. Tool progress and completion are delivered on the loop thread. Compaction reports a range replaced in the request view, without deleting source entries.
 */
public sealed interface AgentEvent {

    /**
     * Who emitted an event. One record on every event, so tagging grows in one place (ticket 17 adds the task id of a
     * sub-agent's events).
     */
    record Origin(String agentId) {
    }

    Origin origin();

    default String agentId() {
        return origin().agentId();
    }

    record RunStart(Origin origin) implements AgentEvent {
    }

    record RunEnd(Origin origin, RunOutcome outcome) implements AgentEvent {
    }

    /** {@code iteration} counts from 1 within the run. */
    record IterationStart(Origin origin, int iteration) implements AgentEvent {
    }

    record IterationEnd(Origin origin, int iteration) implements AgentEvent {
    }

    /** A request was issued; the model's answer streams next. */
    record MessageStart(Origin origin) implements AgentEvent {
    }

    /**
     * The next piece of the streaming answer, by block: one of {@code ai}'s content events (text, thinking or tool-call
     * start / delta / end, whose {@code index} is the block's position in the message) or a usage update.
     */
    record MessageUpdate(Origin origin, StreamEvent event) implements AgentEvent {
    }

    /**
     * The request ended. {@code message} is the complete answer — or, when the request failed or was cancelled, the
     * partial it had streamed (stop reason {@code ERROR} / {@code ABORTED}). Only a complete answer of a run that is
     * not cancelled is logged.
     */
    record MessageEnd(Origin origin, AssistantMessage message) implements AgentEvent {
    }

    /** The provider is retrying the request after a transient failure, as reported by {@code ai}. */
    record Retry(Origin origin, StreamEvent.Retry attempt) implements AgentEvent {
    }

    /** A summary was produced for the half-open source-log range. */
    record Compaction(Origin origin, int fromInclusive, int toExclusive) implements AgentEvent { }

    record ToolStart(Origin origin, ToolCall call) implements AgentEvent {
    }

    /** A tool's current content and structured progress; not an agent-log entry. */
    record ToolUpdate(Origin origin, ToolCall call, ToolResult progress) implements AgentEvent {
    }

    /** Immediate execution completion, before final batch publication normalization. */
    record ToolEnd(Origin origin, ToolResultEntry result) implements AgentEvent { }

    /** Final source-order entries replacing completion content for log/model publication; only emitted on change. */
    record ToolBatchEnd(Origin origin, java.util.List<ToolResultEntry> results) implements AgentEvent {
        public ToolBatchEnd { results = java.util.List.copyOf(results); }
    }
}
