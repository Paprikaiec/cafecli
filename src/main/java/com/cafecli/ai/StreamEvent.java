package com.cafecli.ai;

import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.Usage;

/**
 * One event of an {@link AssistantStream}. {@code index} is the block's position in the final message's content.
 *
 * <p>Order: {@link Start} first; then content blocks one after another, each as start, zero or more deltas, end;
 * {@link UsageUpdate} and {@link Retry} anywhere in between; and exactly one terminal event, {@link Done} or
 * {@link Failed}, last. A {@link Failed} may cut the open block short — no end event follows it.
 */
public sealed interface StreamEvent {

    /** The request was issued. Always the first event. */
    record Start() implements StreamEvent {
    }

    record TextStart(int index) implements StreamEvent {
    }

    record TextDelta(int index, String delta) implements StreamEvent {
    }

    record TextEnd(int index, TextBlock block) implements StreamEvent {
    }

    record ThinkingStart(int index) implements StreamEvent {
    }

    record ThinkingDelta(int index, String delta) implements StreamEvent {
    }

    record ThinkingEnd(int index, ThinkingBlock block) implements StreamEvent {
    }

    /** A tool call began; its id and name are known, its arguments follow as deltas. */
    record ToolCallStart(int index, String id, String name) implements StreamEvent {
    }

    /** The next piece of the tool call's raw JSON arguments. */
    record ToolCallDelta(int index, String delta) implements StreamEvent {
    }

    record ToolCallEnd(int index, ToolCall toolCall) implements StreamEvent {
    }

    /** Usage so far for this message (cumulative, not an increment). The last one reported is the message's usage. */
    record UsageUpdate(Usage usage) implements StreamEvent {
    }

    /**
     * The provider is retrying after a {@link ErrorKind#RETRYABLE} failure: this is attempt {@code attempt} of
     * {@code maxAttempts}.
     */
    record Retry(int attempt, int maxAttempts, String reason) implements StreamEvent {
    }

    /** Terminal: the model finished. */
    record Done(AssistantMessage message) implements StreamEvent {
        public StopReason stopReason() {
            return message.stopReason();
        }
    }

    /**
     * Terminal: the request failed. {@code partial} is exactly what the consumer was streamed before this event (plus
     * a thinking signature already received for the open block, which has no event of its own), with stop reason
     * {@link StopReason#ABORTED} for {@link ErrorKind#ABORTED} and {@link StopReason#ERROR} otherwise; its last block
     * may be cut short. {@code message} is for logs and tests, not for users.
     */
    record Failed(ErrorKind kind, String message, AssistantMessage partial) implements StreamEvent {
    }
}
