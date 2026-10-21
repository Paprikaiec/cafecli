package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.Provider;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A generic agent: its agent log, the model and provider it asks, its system prompt, its boundary inbox, and its
 * subscribers. {@link #run} processes one user submission until the model is done, through the {@link AgentCallbacks}
 * the harness built the agent with.
 *
 * <p><b>The agent log</b> ({@link #log()}) is append-only and is never the request itself: every request is a
 * context projection of it (see {@link AgentCallbacks}). Core keeps it valid for any later request, whatever a run ends with
 * — any outcome, or an exception thrown out of {@link #run} by a callback or a subscriber:
 * <ul>
 *   <li>Only core writes the built-in kinds: the run's input as a user entry, each complete answer as an assistant
 *       entry, and each tool call's result as a tool-result entry. Callbacks and the inbox append custom entries
 *       only.</li>
 *   <li>An assistant entry is always a complete answer of a run that was not cancelled. The answer of a cancelled
 *       request — partial, or complete when the cancel lands just after it finished streaming — and the partial
 *       answer of a failed request are <em>not</em> logged, and no tool of theirs runs. Subscribers saw them
 *       streamed, and {@link AgentEvent.MessageEnd} carries them; no half message and no truncated tool call is ever
 *       sent back to the model.</li>
 *   <li>An assistant entry is followed directly by one tool-result entry per tool call, in call order.</li>
 * </ul>
 *
 * <p><b>Threads</b>: not thread-safe. One run at a time, and {@link #run} / {@link #log()} on the thread that owns the
 * agent (for the main agent, the session's serial executor). The exceptions: {@link #post} and {@link #subscribe} may
 * be called from any thread, and a run is cancelled from any thread through its token.
 */
public final class Agent {

    /** Undoes a {@link #subscribe}. Idempotent. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final String id;
    private final Provider provider;
    private final Model model;
    private final String systemPrompt;
    private final int maxIterations;
    private final AgentCallbacks callbacks;
    private final List<LogEntry> log = new ArrayList<>();
    private final Queue<CustomEntry> inbox = new ConcurrentLinkedQueue<>();
    private final List<Consumer<? super AgentEvent>> subscribers = new CopyOnWriteArrayList<>();

    private Agent(Builder builder) {
        this.id = builder.id;
        this.provider = builder.provider;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.maxIterations = builder.maxIterations;
        this.callbacks = builder.callbacks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    /**
     * Runs one submission to its end, blocking the calling thread: appends {@code input} as a user entry, then
     * iterates — request, tool batch, iteration-end callback — until the model is done, the hard iteration cap is
     * reached, the request fails, or {@code cancellation} is cancelled. Events go to subscribers as it goes.
     *
     * @param input        what the user submitted; the harness builds it (text, and images once ticket 16 lands)
     * @param cancellation cancels this run; cancelling mid-stream aborts the request in flight
     * @return how the run ended; never {@code null}
     */
    public RunOutcome run(UserMessage input, CancellationToken cancellation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(cancellation, "cancellation");
        return new AgentLoop(this, cancellation).run(input);
    }

    /**
     * Puts {@code entry} in the boundary inbox, from any thread. It is appended to the log at the next iteration
     * boundary — just before the next request — of this run, or of the next run if this one makes no further request.
     */
    public void post(CustomEntry entry) {
        inbox.add(Objects.requireNonNull(entry, "entry"));
    }

    /**
     * Delivers every event of this agent to {@code subscriber}, synchronously, on the thread running the agent. A
     * subscriber must only enqueue — hand the event to its own thread — and return: it holds up the run while it
     * runs, and an exception it throws ends the run with that exception.
     */
    public Subscription subscribe(Consumer<? super AgentEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /** The agent log so far, oldest first; a snapshot. */
    public List<LogEntry> log() {
        return List.copyOf(log);
    }

    // ---- for AgentLoop ----

    void emit(AgentEvent event) {
        for (Consumer<? super AgentEvent> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }

    void append(LogEntry entry) {
        log.add(entry);
    }

    /** Appends everything posted to the inbox so far, in the order it was posted. */
    void drainInbox() {
        for (CustomEntry entry; (entry = inbox.poll()) != null; ) {
            log.add(entry);
        }
    }

    Provider provider() {
        return provider;
    }

    Model model() {
        return model;
    }

    String systemPrompt() {
        return systemPrompt;
    }

    AgentCallbacks callbacks() {
        return callbacks;
    }

    int maxIterations() {
        return maxIterations;
    }

    /**
     * Required: {@link #id}, {@link #provider}, {@link #model}, {@link #maxIterations}. Optional: {@link #systemPrompt}
     * (empty), {@link #callbacks} (every default).
     */
    public static final class Builder {
        private String id;
        private Provider provider;
        private Model model;
        private String systemPrompt = "";
        private int maxIterations;
        private AgentCallbacks callbacks = new AgentCallbacks() {
        };

        private Builder() {
        }

        /** Names the agent in every event it emits. */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder provider(Provider provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * The hard iteration cap: a run makes at most this many iterations, then ends with
         * {@link RunOutcome.BudgetExhausted}. The only budget core knows; there is no default (the harness reads it
         * from configuration).
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder callbacks(AgentCallbacks callbacks) {
            this.callbacks = callbacks;
            return this;
        }

        public Agent build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("an agent needs an id");
            }
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(callbacks, "callbacks");
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive, was " + maxIterations);
            }
            return new Agent(this);
        }
    }
}
