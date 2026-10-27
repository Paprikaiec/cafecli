package com.cafecli.ai;

import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.Usage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The answer to one model request as a pull-based stream of {@link StreamEvent}s, iterated once on the caller's own
 * (virtual) thread:
 *
 * <pre>{@code
 * try (AssistantStream stream = provider.stream(model, context, options)) {
 *     for (StreamEvent event : stream) { ... }
 * }
 * }</pre>
 *
 * or drained in one call with {@link #complete()}.
 *
 * <p>Guarantees, whatever the provider does: {@link StreamEvent.Start} comes first; exactly one terminal event
 * ({@link StreamEvent.Done} or {@link StreamEvent.Failed}) comes last; nothing follows it. A provider that throws, or
 * runs out without finishing, ends the stream with {@link ErrorKind#FATAL}.
 *
 * <p>Cancelling the token ends the stream with {@link ErrorKind#ABORTED}: from the next event the consumer asks for,
 * the source is not pulled again; events it had already produced are still handed over first, so the aborted partial
 * is exactly what the consumer was given. An abort supersedes a terminal event the source had produced but the
 * consumer had not yet taken.
 *
 * <p>Single consumer: iterate, {@link #complete()} and {@link #close()} on one thread; cancel from any thread through
 * the token.
 *
 * <p>Providers build a stream from a {@link Source}, which writes into an {@link Emitter}; the stream assembles the
 * blocks and the final message from what was emitted.
 */
public final class AssistantStream implements Iterable<StreamEvent>, AutoCloseable {

    /**
     * The provider's side of a stream. Pulled on the consumer's thread, only when the consumer wants an event and
     * none is buffered; blocking (e.g. on a socket read) is expected.
     */
    @FunctionalInterface
    public interface Source extends AutoCloseable {

        /**
         * Produces the next part of the answer into {@code out} — any number of calls, including none. Returns
         * {@code false} once there is nothing more to produce; the stream stops pulling as soon as a terminal event
         * is emitted. Ending without one, or throwing, ends the stream with a {@link ErrorKind#FATAL} error.
         */
        boolean pull(Emitter out) throws Exception;

        /** Releases what the source holds (e.g. a response body). Called once, when the stream ends or is closed. */
        @Override
        default void close() throws Exception {
        }
    }

    /**
     * Where a {@link Source} writes the answer, in the order it arrives. Blocks open and close implicitly: a delta of
     * another kind, a new tool call, or {@link #done} closes the open block.
     */
    public static final class Emitter {

        private final Deque<StreamEvent> out;
        private final PartialMessage partial;
        private boolean finished;

        private Emitter(Deque<StreamEvent> out, PartialMessage partial) {
            this.out = out;
            this.partial = partial;
        }

        /** Appends to the open text block, opening one if needed. Empty deltas are ignored. */
        public void textDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (partial.openKind() != PartialMessage.Kind.TEXT) {
                closeOpenBlock();
                emit(new StreamEvent.TextStart(partial.index()));
            }
            emit(new StreamEvent.TextDelta(partial.index(), delta));
        }

        /** Appends to the open thinking block, opening one if needed. Empty deltas are ignored. */
        public void thinkingDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (partial.openKind() != PartialMessage.Kind.THINKING) {
                closeOpenBlock();
                emit(new StreamEvent.ThinkingStart(partial.index()));
            }
            emit(new StreamEvent.ThinkingDelta(partial.index(), delta));
        }

        /**
         * Sets the provider's signature on the open thinking block; it travels on the block's end event and in the
         * message. Emits no event of its own.
         *
         * @throws IllegalStateException if no thinking block is open
         */
        public void thinkingSignature(String signature) {
            Objects.requireNonNull(signature, "signature");
            if (finished || partial.openKind() != PartialMessage.Kind.THINKING) {
                throw new IllegalStateException("no thinking block is open");
            }
            partial.signOpenThinking(signature);
        }

        /** Opens a tool call; its arguments follow through {@link #toolCallDelta}. */
        public void toolCallStart(String id, String name) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            closeOpenBlock();
            emit(new StreamEvent.ToolCallStart(partial.index(), id, name));
        }

        /** Appends raw JSON text to the open tool call's arguments. Empty deltas are ignored. */
        public void toolCallDelta(String argumentsDelta) {
            if (partial.openKind() != PartialMessage.Kind.TOOL_CALL) {
                throw new IllegalStateException("no tool call is open");
            }
            if (argumentsDelta == null || argumentsDelta.isEmpty()) {
                return;
            }
            emit(new StreamEvent.ToolCallDelta(partial.index(), argumentsDelta));
        }

        /** Reports usage so far for this message: cumulative, not an increment. Does not close the open block. */
        public void usage(Usage usage) {
            emit(new StreamEvent.UsageUpdate(Objects.requireNonNull(usage, "usage")));
        }

        /** Announces that the provider is retrying: attempt {@code attempt} of {@code maxAttempts}. */
        public void retry(int attempt, int maxAttempts, String reason) {
            emit(new StreamEvent.Retry(attempt, maxAttempts, reason));
        }

        /** Ends the stream: the model finished for {@code stopReason}. Failures end it through {@link #fail}. */
        public void done(StopReason stopReason) {
            if (stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED) {
                throw new IllegalArgumentException("a failed stream ends through fail(), not done(" + stopReason + ")");
            }
            closeOpenBlock();
            emit(new StreamEvent.Done(partial.snapshot(stopReason)));
            finished = true;
        }

        /**
         * Ends the stream with an error. The open block is left as it is — cut short, with no end event — and the
         * partial message carries it.
         */
        public void fail(ErrorKind kind, String message) {
            Objects.requireNonNull(kind, "kind");
            StopReason stopReason = kind == ErrorKind.ABORTED ? StopReason.ABORTED : StopReason.ERROR;
            emit(new StreamEvent.Failed(kind, message, partial.snapshot(stopReason)));
            finished = true;
        }

        boolean finished() {
            return finished;
        }

        /** The terminal event of a cancelled stream, superseding any terminal the source emitted. */
        StreamEvent.Failed abort() {
            finished = true;
            return new StreamEvent.Failed(ErrorKind.ABORTED, "request cancelled", partial.snapshot(StopReason.ABORTED));
        }

        private void closeOpenBlock() {
            PartialMessage.Kind kind = partial.openKind();
            if (kind == null) {
                return;
            }
            int index = partial.index();
            switch (kind) {
                case TEXT -> emit(new StreamEvent.TextEnd(index, (TextBlock) partial.openBlock()));
                case THINKING -> emit(new StreamEvent.ThinkingEnd(index, (ThinkingBlock) partial.openBlock()));
                case TOOL_CALL -> emit(new StreamEvent.ToolCallEnd(index, (ToolCall) partial.openBlock()));
            }
        }

        private void emit(StreamEvent event) {
            if (finished) {
                throw new IllegalStateException("the stream already ended");
            }
            partial.apply(event);
            out.add(event);
        }
    }

    private final CancellationToken cancellation;
    private final Source source;
    private final Deque<StreamEvent> pending = new ArrayDeque<>();
    private final Emitter emitter;
    private boolean started;
    private StreamEvent terminal;
    private boolean closed;
    private boolean sourceClosed;
    private boolean iterated;

    private AssistantStream(Model model, CancellationToken cancellation, Source source) {
        this.cancellation = cancellation;
        this.source = source;
        this.emitter = new Emitter(pending, new PartialMessage(model));
    }

    /** The one way to build a stream; {@code model} names the provider and model on the messages it produces. */
    public static AssistantStream of(Model model, CancellationToken cancellation, Source source) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(source, "source");
        return new AssistantStream(model, cancellation, source);
    }

    /** Iterates the events. Can be called once. */
    @Override
    public Iterator<StreamEvent> iterator() {
        if (iterated) {
            throw new IllegalStateException("an AssistantStream can be iterated only once");
        }
        iterated = true;
        return new Iterator<>() {
            private StreamEvent next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    next = advance();
                }
                return next != null;
            }

            @Override
            public StreamEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                StreamEvent event = next;
                next = null;
                return event;
            }
        };
    }

    /**
     * Drains the stream (whatever part of it was not iterated yet), closes it, and returns the final message.
     *
     * @throws ProviderException carrying the {@link ErrorKind} if the stream ended with {@link StreamEvent.Failed}
     * @throws IllegalStateException if the stream was closed before its terminal event
     */
    public AssistantMessage complete() throws ProviderException {
        try {
            while (terminal == null && advance() != null) {
                // drain
            }
        } finally {
            close();
        }
        return switch (terminal) {
            case StreamEvent.Done done -> done.message();
            case StreamEvent.Failed failed -> throw new ProviderException(failed);
            case null, default -> throw new IllegalStateException("the stream was closed before it finished");
        };
    }

    /** Stops the stream and releases the source. Idempotent; no event is delivered afterwards. */
    @Override
    public void close() {
        closed = true;
        closeSource();
    }

    private StreamEvent advance() {
        if (terminal != null || closed) {
            return null;
        }
        if (!started) {
            started = true;
            return new StreamEvent.Start();
        }
        // Cancellation stops the pulling, not the delivery of what was already produced: the aborted partial is
        // then exactly what the consumer was handed. A buffered terminal is superseded by the abort.
        while (true) {
            boolean cancelled = cancellation.isCancelled();
            StreamEvent event = pending.peek();
            if (event != null && !(cancelled && isTerminal(event))) {
                return deliver(pending.poll());
            }
            if (cancelled) {
                // Only a terminal event can still be buffered here (everything before it was just delivered), and
                // emit() refuses anything after a terminal: this clear drops at most that one superseded event.
                pending.clear();
                return deliver(emitter.abort());
            }
            pullSource();
        }
    }

    private void pullSource() {
        try {
            if (!source.pull(emitter) && !emitter.finished()) {
                emitter.fail(ErrorKind.FATAL, "the provider stream ended without a terminal event");
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Cancelled: the provider was woken by aborting its I/O, so the failure is the cancellation itself and
            // the next advance turns it into the abort.
            if (!emitter.finished() && !cancellation.isCancelled()) {
                emitter.fail(ErrorKind.FATAL, e.toString());
            }
        }
    }

    private static boolean isTerminal(StreamEvent event) {
        return event instanceof StreamEvent.Done || event instanceof StreamEvent.Failed;
    }

    private StreamEvent deliver(StreamEvent event) {
        if (isTerminal(event)) {
            terminal = event;
            closeSource();
        }
        return event;
    }

    private void closeSource() {
        if (sourceClosed) {
            return;
        }
        sourceClosed = true;
        try {
            source.close();
        } catch (Exception ignored) {
            // Nothing is waiting on the source any more; a failure to release it changes no outcome.
        }
    }
}
