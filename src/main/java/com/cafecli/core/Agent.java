package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.Provider;
import com.cafecli.ai.ToolDefinition;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * agent (for the main agent, the session's serial executor). The exceptions: {@link #post}, {@link #takeInbox} and {@link #subscribe} may
 * be called from any thread, and a run is cancelled from any thread through its token.
 */
public final class Agent implements AutoCloseable {
    private boolean closed;
    private boolean running;

    /** Idle-only ownership release. The harness cancels and awaits its run before closing an agent. */
    @Override public void close() {
        synchronized (this) {
            if (running) throw new IllegalStateException("cannot close an agent during a run");
            if (closed) return;
            closed = true;
        }
        callbacks.onClose();
    }

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
    private final List<AgentTool> tools;
    private final Duration toolBatchTimeout;
    private final List<LogEntry> log = new ArrayList<>();
    private final List<CustomEntry> inbox = new ArrayList<>();
    private final List<Consumer<? super AgentEvent>> subscribers = new CopyOnWriteArrayList<>();

    private Agent(Builder builder) {
        this.id = builder.id;
        this.provider = builder.provider;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.maxIterations = builder.maxIterations;
        this.callbacks = builder.callbacks;
        this.tools = List.copyOf(builder.tools);
        this.toolBatchTimeout = builder.toolBatchTimeout;
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
        synchronized (this) {
            if (closed) throw new IllegalStateException("agent is closed");
            if (running) throw new IllegalStateException("a run is in flight");
            running = true;
        }
        try { return new AgentLoop(this, cancellation).run(input); }
        finally { synchronized (this) { running = false; } }
    }

    /**
     * Puts {@code entry} in the boundary inbox, from any thread. It is appended to the log at the next iteration
     * boundary — just before the next request — of this run, or of the next run if this one makes no further request.
     */
    public void post(CustomEntry entry) {
        synchronized (inbox) { inbox.add(Objects.requireNonNull(entry, "entry")); }
    }

    /**
     * Atomically removes and returns matching pending entries in arrival order. The harness owns end-of-run policy;
     * entries it leaves here survive into a later run. Does not write the log. The predicate must not block.
     */
    public List<CustomEntry> takeInbox(Predicate<? super CustomEntry> matches) {
        Objects.requireNonNull(matches, "matches");
        synchronized (inbox) {
            List<CustomEntry> taken = new ArrayList<>();
            var iterator = inbox.iterator();
            while (iterator.hasNext()) {
                CustomEntry entry = iterator.next();
                if (matches.test(entry)) { taken.add(entry); iterator.remove(); }
            }
            return List.copyOf(taken);
        }
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

    /** Appends pending entries in arrival order while the run is still accepting them. */
    void drainInbox(CancellationToken cancellation) {
        log.addAll(takeInbox(entry -> !cancellation.isCancelled()));
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

    Duration toolBatchTimeout() {
        return toolBatchTimeout;
    }

    List<AgentTool> tools() {
        return tools;
    }

    List<ToolDefinition> toolDefinitions() {
        return tools.stream().map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters())).toList();
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
        private List<AgentTool> tools = List.of();
        private Duration toolBatchTimeout = Duration.ofSeconds(90);
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

        /** Batch deadline; a single call executes inline without a batch deadline, as in v1. */
        public Builder toolBatchTimeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("toolBatchTimeout must be positive");
            }
            this.toolBatchTimeout = timeout;
            return this;
        }

        public Builder tools(List<AgentTool> tools) {
            this.tools = List.copyOf(tools);
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
            if (tools.stream().map(AgentTool::name).distinct().count() != tools.size()) {
                throw new IllegalArgumentException("tool names must be unique");
            }
            return new Agent(this);
        }
    }
}
