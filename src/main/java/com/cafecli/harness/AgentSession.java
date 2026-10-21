package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.Provider;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.LogEntry;
import com.cafecli.harness.config.ConfigHomes;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptRepository;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The session facade (PRD "{@code harness} layer"): the one thing headless — and, from ticket 24, the UI — talks to.
 * It opens with the composition root's capabilities in declared order: their session hooks run once, then the main
 * agent is built from the main-agent profile through the {@link AgentFactory}. It then takes input, runs it, and
 * reports what happened as events and as a typed outcome.
 *
 * <p><b>Synchronous mode</b> (the only mode so far): {@link #submit} runs the input to the end of its run on the
 * calling thread and returns the outcome. There is no event loop, no pump and no push — what headless {@code run
 * --file} needs, and must keep. Ticket 23 adds the interactive mode behind this same facade (one serial executor as
 * the main agent's only writer, the run-boundary drain, autonomous runs on task completion); headless keeps calling
 * {@link #submit} and reading its outcome.
 *
 * <p><b>Threads</b>: one run at a time. {@link #subscribe}, {@link #cancel} and {@link #awaitIdle} may be called from
 * any thread, {@link #log} once the session is idle. Events reach subscribers on the thread running the agent (core's
 * rule: subscribers only enqueue).
 */
public final class AgentSession {

    /** The main agent's id: what its events carry. */
    public static final String MAIN_AGENT_ID = "main";

    private final SessionEvents events = new SessionEvents();
    private final Agent main;
    private final Object lock = new Object();
    /** The token of the run in flight; {@code null} while idle. Guarded by {@link #lock}. */
    private CancellationToken inFlight;

    private AgentSession(Builder builder) {
        SessionContributions registrar = new SessionContributions(events);
        for (Capability capability : builder.capabilities) {
            capability.attachSession(registrar);
        }
        registrar.close();
        ConfigHomes homes = builder.config.homes();
        PromptAssembler prompts = new PromptAssembler(
                new PromptRepository(homes.user().resolve("prompts"), homes.project().resolve("prompts")));
        AgentFactory factory = new AgentFactory(builder.provider, prompts, builder.capabilities, events);
        this.main = factory.create(MAIN_AGENT_ID,
                AgentProfile.mainAgent(builder.model, builder.capabilities, builder.config.maxIterations()));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs {@code input} as the user's next message, to the end of its run, and returns how the run ended. The run
     * continues the main agent's log: earlier submissions are part of its context.
     *
     * @param input the user's text; becomes the run's user message (image references come with ticket 16)
     * @throws IllegalStateException if a run is already in flight — in synchronous mode input is not accepted while a
     *                               run is in flight; ticket 09 makes it a steering message
     */
    public RunOutcome submit(String input) {
        Objects.requireNonNull(input, "input");
        // Ticket 10: the UserPromptSubmit hook runs here, before the run starts; it may block the submission or
        // change its text.
        CancellationToken cancellation = new CancellationToken();
        synchronized (lock) {
            if (inFlight != null) {
                throw new IllegalStateException("a run is in flight: input during a run is steering (ticket 09)");
            }
            inFlight = cancellation;
        }
        try {
            return main.run(UserMessage.text(input), cancellation);
        } finally {
            synchronized (lock) {
                inFlight = null;
                lock.notifyAll();
            }
        }
    }

    /**
     * Cancels the run in flight, if there is one — the request it is waiting on included — so it ends
     * {@link RunOutcome.Cancelled}. Does nothing while idle: a cancel never carries over to the next run.
     */
    public void cancel() {
        CancellationToken cancellation;
        synchronized (lock) {
            cancellation = inFlight;
        }
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /** Returns once no run is in flight — at once in synchronous mode, unless another thread's run is in flight. */
    public void awaitIdle() throws InterruptedException {
        synchronized (lock) {
            while (inFlight != null) {
                lock.wait();
            }
        }
    }

    /** The main agent's log, oldest first; a snapshot. */
    public List<LogEntry> log() {
        return main.log();
    }

    /** Undoes a {@link #subscribe}. Idempotent. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Delivers every event of every agent of this session — the main agent's now, sub-agents' from ticket 17 — to
     * {@code subscriber}, after the capabilities' own event listeners, synchronously on the thread running the agent.
     * The subscriber only enqueues; an exception it throws ends the run with that exception.
     */
    public Subscription subscribe(Consumer<? super AgentEvent> subscriber) {
        return events.add(subscriber);
    }

    /** Required: {@link #config}, {@link #provider}, {@link #model}. Optional: {@link #capabilities} (none). */
    public static final class Builder {
        private HarnessConfig config;
        private Provider provider;
        private Model model;
        private List<Capability> capabilities = List.of();

        /** Where the session works (project root, config homes) and the main agent's hard iteration cap. */
        public Builder config(HarnessConfig config) {
            this.config = config;
            return this;
        }

        public Builder provider(Provider provider) {
            this.provider = provider;
            return this;
        }

        /** The main agent's model. */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * The session's capabilities, constructed by the composition root, in declared order — the order their hooks
         * run in. The main agent attaches all of them.
         */
        public Builder capabilities(List<Capability> capabilities) {
            this.capabilities = List.copyOf(capabilities);
            return this;
        }

        public AgentSession build() {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            return new AgentSession(this);
        }
    }
}
