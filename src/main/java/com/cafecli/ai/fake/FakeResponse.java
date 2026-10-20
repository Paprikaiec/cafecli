package com.cafecli.ai.fake;

import com.cafecli.ai.AssistantStream;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One scripted answer of the {@link FakeProvider}.
 *
 * <p>Streams like a real provider, deterministically: every text — thinking, answer text, tool-call arguments — is
 * split into at most three deltas of near-equal length (on code-point boundaries), so a consumer's delta handling is
 * always exercised. Content streams in the order it was added to the {@link Builder}. One response can be replayed by
 * any number of streams.
 */
public final class FakeResponse {

    private static final int DELTAS_PER_TEXT = 3;

    private final List<Consumer<AssistantStream.Emitter>> steps;

    private FakeResponse(List<Consumer<AssistantStream.Emitter>> steps) {
        this.steps = List.copyOf(steps);
    }

    /** A plain text answer. */
    public static FakeResponse text(String text) {
        return builder().text(text).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<Consumer<AssistantStream.Emitter>> steps = new ArrayList<>();
        private boolean toolCalls;
        private StopReason stopReason;

        private Builder() {
        }

        /** Thinking, streamed as up to three deltas. Consecutive thinking (or text) calls extend the same block. */
        public Builder thinking(String thinking) {
            for (String chunk : chunks(thinking)) {
                steps.add(out -> out.thinkingDelta(chunk));
            }
            return this;
        }

        /** Signs the thinking added just before (as some providers do at the end of a thinking block). */
        public Builder thinkingSignature(String signature) {
            steps.add(out -> out.thinkingSignature(signature));
            return this;
        }

        /** Answer text, streamed as up to three deltas. */
        public Builder text(String text) {
            for (String chunk : chunks(text)) {
                steps.add(out -> out.textDelta(chunk));
            }
            return this;
        }

        /** A tool call with its arguments as raw JSON text, streamed as given (it need not even be valid JSON). */
        public Builder toolCall(String id, String name, String argumentsJson) {
            steps.add(out -> out.toolCallStart(id, name));
            for (String chunk : chunks(argumentsJson)) {
                steps.add(out -> out.toolCallDelta(chunk));
            }
            toolCalls = true;
            return this;
        }

        /** Reports usage so far (cumulative; the last one reported is the message's usage). */
        public Builder usage(Usage usage) {
            steps.add(out -> out.usage(usage));
            return this;
        }

        /** Announces a retry, as a provider does after a retryable failure. */
        public Builder retry(int attempt, int maxAttempts, String reason) {
            steps.add(out -> out.retry(attempt, maxAttempts, reason));
            return this;
        }

        /** Overrides the stop reason {@link #build()} would derive (e.g. {@link StopReason#LENGTH}). */
        public Builder stopReason(StopReason stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        /** Ends the answer with an error of {@code kind}, after whatever content was added so far. */
        public FakeResponse fail(ErrorKind kind, String message) {
            steps.add(out -> out.fail(kind, message));
            return new FakeResponse(steps);
        }

        /**
         * Ends the answer normally: {@link StopReason#TOOL_USE} if it has tool calls, else {@link StopReason#END_TURN},
         * unless {@link #stopReason} said otherwise.
         */
        public FakeResponse build() {
            StopReason reason = stopReason != null ? stopReason
                    : toolCalls ? StopReason.TOOL_USE : StopReason.END_TURN;
            steps.add(out -> out.done(reason));
            return new FakeResponse(steps);
        }
    }

    /** The answer as a source that performs one step per pull. */
    AssistantStream.Source source() {
        var remaining = steps.iterator();
        return out -> {
            if (!remaining.hasNext()) {
                return false;
            }
            remaining.next().accept(out);
            return true;
        };
    }

    private static List<String> chunks(String text) {
        int length = text.codePointCount(0, text.length());
        int pieces = Math.min(DELTAS_PER_TEXT, length);
        List<String> chunks = new ArrayList<>(pieces);
        int start = 0;
        for (int i = 0; i < pieces; i++) {
            int codePoints = length / pieces + (i < length % pieces ? 1 : 0);
            int end = text.offsetByCodePoints(start, codePoints);
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
