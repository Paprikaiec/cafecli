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
import com.cafecli.harness.context.MessageKindRegistry;
import com.cafecli.harness.context.Steering;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The session facade (PRD "{@code harness} layer"): the one thing headless — and, from ticket 24, the UI — talks to.
 * It opens with the composition root's capabilities in declared order: their session hooks run once, then the main
 * agent is built from the main-agent profile through the {@link AgentFactory}. It then takes input, runs it, and
 * reports what happened as events and as a typed outcome.
 *
 * <p><b>Synchronous mode</b> (the only mode so far): {@link #submit} runs the input to the end of its run on the
 * calling thread and returns a completed submission with its outcome. Concurrent input is accepted as steering.
 * There is no event loop, no pump and no push — what headless {@code run
 * --file} needs, and must keep. Ticket 23 adds the interactive mode behind this same facade (one serial executor as
 * the main agent's only writer, the run-boundary drain, autonomous runs on task completion); headless keeps calling
 * {@link #submit} and reading its outcome.
 *
 * <p><b>Threads</b>: one run at a time. {@link #subscribe}, {@link #cancel} and {@link #awaitIdle} may be called from
 * any thread, as may {@link #submit} to steer the running agent; {@link #log} once the session is idle. Events reach
 * subscribers on the thread running the agent (core's rule: subscribers only enqueue).
 *
 * <p>The caller owns the session: close it (normally with try-with-resources) to stop its run and drain capability
 * resources such as the audit writer. A failed construction also closes every resource already registered.
 */
public final class AgentSession implements AutoCloseable {

    /** The main agent's id: what its events carry. */
    public static final String MAIN_AGENT_ID = "main";

    private final SessionEvents events = new SessionEvents();
    private volatile Agent main;
    private Runnable invalidateSystemPrompt;
    private final AgentFactory factory;
    private final AgentProfile mainProfile;
    private volatile MessageKindRegistry messageKinds;
    private final List<String> followUpInputs = new ArrayList<>();
    private final Object lock = new Object();
    /** The token of the run in flight; {@code null} while idle. Guarded by {@link #lock}. */
    private CancellationToken inFlight;
    private Thread runningThread;
    private boolean commandInFlight;
    private boolean closing;
    private boolean closed;
    private final SessionContributions resources;

    private AgentSession(Builder builder) {
        SessionContributions registrar = new SessionContributions(events);
        this.resources = registrar;
        try {
            if (builder.factory != null) builder.factory.claim();
            for (Capability capability : builder.capabilities) {
                capability.attachSession(registrar);
            }
            registrar.close();
            ConfigHomes homes = builder.config.homes();
            PromptAssembler prompts = new PromptAssembler(
                    new PromptRepository(homes.user().resolve("prompts"), homes.project().resolve("prompts")));
            this.factory = builder.factory != null ? builder.factory
                    : new AgentFactory(builder.provider, prompts, builder.config.workspace().toolBatchTimeout());
            this.factory.open(builder.capabilities, events);
            this.mainProfile = AgentProfile.mainAgent(builder.model, builder.capabilities, builder.config.maxIterations());
            var assembly = factory.assemble(MAIN_AGENT_ID, mainProfile);
            this.main = assembly.agent();
            this.invalidateSystemPrompt = assembly.invalidateSystemPrompt();
            this.messageKinds = assembly.messageKinds();
        } catch (RuntimeException | Error failure) {
            try { registrar.cleanup(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Requests a prompt rebuild at the next safe request boundary, including during a running agent. */
    public void rebuildSystemPrompt() {
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            invalidateSystemPrompt.run();
        }
    }

    /** Replaces the main agent between runs, resetting all per-agent capability state. */
    public void clear() {
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (inFlight != null && !(commandInFlight && runningThread == Thread.currentThread())) throw new IllegalStateException("a run is in flight");
            var replacement = factory.assemble(MAIN_AGENT_ID, mainProfile);
            try { resources.clear(); }
            catch (RuntimeException | Error failure) {
                try { replacement.agent().close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
            Agent previous = main;
            main = replacement.agent();
            invalidateSystemPrompt = replacement.invalidateSystemPrompt();
            messageKinds = replacement.messageKinds();
            followUpInputs.clear();
            previous.close();
        }
    }

    /** Commands are idle-only and never enter the agent log or provider. Unknown names remain the caller's concern. */
    public Optional<String> dispatch(String input) {
        Objects.requireNonNull(input, "input");
        String[] parts = input.trim().split("\\s+", 2);
        SessionCommand command;
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (inFlight != null) throw new IllegalStateException("a run is in flight");
            command = resources.commands().stream().filter(candidate -> candidate.name().equalsIgnoreCase(parts[0]))
                    .findFirst().orElse(null);
            if (command == null) return Optional.empty();
            inFlight = new CancellationToken();
            runningThread = Thread.currentThread();
            commandInFlight = true;
        }
        try { return Optional.of(command.handler().apply(this, parts.length == 1 ? "" : parts[1])); }
        finally {
            synchronized (lock) {
                inFlight = null;
                runningThread = null;
                commandInFlight = false;
                lock.notifyAll();
            }
        }
    }

    /** Invokes a tool only for the current idle command owner, through the auxiliary permission route. */
    public com.cafecli.core.ToolResult invokeCommandTool(String name, com.fasterxml.jackson.databind.JsonNode input) {
        CancellationToken token;
        synchronized (lock) {
            if (!commandInFlight || runningThread != Thread.currentThread() || inFlight == null)
                throw new IllegalStateException("command owner required");
            token = inFlight;
        }
        return main.invokeTool(name, input, token);
    }

    /** Declared slash commands, for UI completion and dispatch. */
    public List<SessionCommand> commands() { return resources.commands(); }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs {@code input} as the user's next message, to the end of its run, and returns how the run ended. The run
     * continues the main agent's log: earlier submissions are part of its context.
     *
     * <p>Input during a run is queued atomically as steering and returns immediately. If that run makes no further
     * request, the caller owns re-submission of {@link #takeFollowUpInputs()} as ordinary input.
     *
     * @param input the user's text; capabilities prepare the run's source message and model presentation
     */
    public SubmitResult submit(String input) {
        Objects.requireNonNull(input, "input");
        CancellationToken cancellation = new CancellationToken();
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (inFlight != null) {
                if (commandInFlight) throw new IllegalStateException("a command is in flight");
                main.post(new Steering(input));
                return new SubmitResult.SteeringAccepted();
            }
            inFlight = cancellation;
            runningThread = Thread.currentThread();
        }
        try {
            Submission submission = resources.prepare(input, cancellation);
            if (submission.blocked()) return new SubmitResult.Blocked(submission.reason());
            return new SubmitResult.Completed(main.run(UserMessage.text(submission.prompt()), cancellation));
        } finally {
            synchronized (lock) {
                var pending = main.takeInbox(Steering.class::isInstance);
                if (!cancellation.isCancelled()) {
                    for (var entry : pending) followUpInputs.add(((Steering) entry).text());
                }
                inFlight = null;
                runningThread = null;
                lock.notifyAll();
            }
        }
    }

    /**
     * Takes undelivered, non-cancelled steering in submission order, promoted to ordinary next inputs. This also
     * preserves inputs when a callback throws. No automatic runs: the caller must submit the returned inputs.
     * Taking again returns nothing until another run hands input back. Drain after {@code submit} returns or throws,
     * or after {@link #awaitIdle()}; the run's final event precedes this handoff. May be called from any thread.
     */
    public List<String> takeFollowUpInputs() {
        synchronized (lock) {
            List<String> taken = List.copyOf(followUpInputs);
            followUpInputs.clear();
            return taken;
        }
    }

    /** This agent's attached custom kinds, for lossless log serialization. */
    public MessageKindRegistry messageKinds() { return messageKinds; }

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

    /**
     * Cancels and waits for an external run, then drains/closes owned resources in reverse order. Idempotent; waits
     * preserve interruption so an interrupted caller cannot abandon queued audit records. A loop callback must not
     * close its own session: it can cancel the run and let its caller close afterward.
     */
    @Override public void close() {
        boolean interrupted = false;
        try {
            CancellationToken cancellation;
            synchronized (lock) {
                if (runningThread == Thread.currentThread()) {
                    throw new IllegalStateException("cannot close a session from its running thread");
                }
                while (closing && !closed) {
                    try { lock.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (closed) return;
                closing = true;
                cancellation = inFlight;
            }
            Throwable failure = null;
            try {
                try { if (cancellation != null) cancellation.cancel(); }
                catch (RuntimeException | Error e) { failure = e; }
                synchronized (lock) {
                    while (inFlight != null) {
                        try { lock.wait(); }
                        catch (InterruptedException e) { interrupted = true; }
                    }
                }
                try { main.close(); }
                catch (RuntimeException | Error e) {
                    if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e);
                }
                try { resources.cleanup(); }
                catch (RuntimeException | Error e) {
                    if (failure == null) failure = e;
                    else if (failure != e) failure.addSuppressed(e);
                }
            } finally {
                synchronized (lock) { closed = true; lock.notifyAll(); }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
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
        private AgentFactory factory;
        private Model model;
        private List<Capability> capabilities = List.of();

        /** Where the session works (project root, config homes) and the main agent's hard iteration cap. */
        public Builder config(HarnessConfig config) {
            this.config = config;
            return this;
        }

        /** Explicit shared factory, also injected into sub-agent capabilities by the composition root. */
        public Builder factory(AgentFactory factory) {
            this.factory = Objects.requireNonNull(factory);
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
            if (factory == null) Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            return new AgentSession(this);
        }
    }
}
