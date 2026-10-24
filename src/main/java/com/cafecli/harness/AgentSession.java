package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.Provider;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunInput;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.harness.config.ConfigHomes;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.harness.context.MessageKindRegistry;
import com.cafecli.harness.context.RunInputEntry;
import com.cafecli.harness.context.Steering;
import com.cafecli.harness.tasks.TaskNotification;
import com.cafecli.prompt.PromptAssembler;
import com.cafecli.prompt.PromptRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The session facade (PRD "{@code harness} layer"): the one thing headless — and, from ticket 24, the UI — talks to.
 * It opens with the composition root's capabilities in declared order: their session hooks run once, then the main
 * agent is built from the main-agent profile through the {@link AgentFactory}. It then takes input, runs it, and
 * reports what happened as events and as a typed outcome.
 *
 * <p><b>Synchronous mode</b> (the default): {@link #submit} runs the input to the end of its run on the
 * calling thread and returns a completed submission with its outcome. Concurrent input is accepted as steering.
 * There is no event loop, no pump and no push — what headless {@code run
 * --file} needs. Interactive mode opts into one serial owner, coalesces pending inputs at run boundaries,
 * and starts autonomous runs for task notifications. Its submissions return {@link SubmitResult.Queued} or
 * {@link SubmitResult.SteeringAccepted}; {@link #subscribeCompletion} observes their outcomes.
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

    private final ExecutorService executor;
    private volatile Thread ownerThread;
    private final List<Consumer<Boolean>> activityListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionCompletion>> completionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> failures = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> queuedInputs = new ArrayDeque<>();
    private final List<TaskNotification> queuedNotifications = new ArrayList<>();
    private final AtomicInteger deliveries = new AtomicInteger();
    private boolean loopScheduled;
    private boolean acceptingSteering;
    private final Map<CancellationToken, Thread> preparations = new IdentityHashMap<>();
    private boolean active;
    private final SessionEvents events = new SessionEvents();
    private volatile Agent main;
    private volatile SessionStatus status;
    private final List<Consumer<SessionStatus>> statusListeners = new CopyOnWriteArrayList<>();
    private Runnable invalidateSystemPrompt;
    private final AgentFactory factory;
    private AgentProfile mainProfile;
    private volatile MessageKindRegistry messageKinds;
    private final List<String> followUpInputs = new ArrayList<>();
    private final Object lock = new Object();
    /** The token of the run in flight; {@code null} while idle. Guarded by {@link #lock}. */
    private CancellationToken inFlight;
    private Thread runningThread;
    private boolean commandInFlight;
    private int controlsInFlight;
    private final Map<Thread, Integer> controlOwners = new IdentityHashMap<>();
    private volatile boolean closing;
    private volatile boolean closed;
    private final SessionContributions resources;

    private AgentSession(Builder builder) {
        executor = builder.interactive ? Executors.newSingleThreadExecutor(
                task -> Thread.ofVirtual().name("cafecli-main-session").unstarted(() -> {
                    ownerThread = Thread.currentThread(); task.run();
                })) : null;
        SessionContributions registrar = new SessionContributions(events, builder.interactive);
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
            if (executor != null) this.factory.inboxDelivery(this::deliverInbox);
            this.factory.open(builder.capabilities, events);
            this.mainProfile = AgentProfile.mainAgent(builder.model, builder.capabilities, builder.config.maxIterations());
            this.status = SessionStatus.idle(builder.model);
            var assembly = executor == null ? factory.assemble(MAIN_AGENT_ID, mainProfile)
                    : factory.assembleWithStatus(MAIN_AGENT_ID, mainProfile, this::statusChanged);
            this.main = assembly.agent();
            this.invalidateSystemPrompt = assembly.invalidateSystemPrompt();
            this.messageKinds = assembly.messageKinds();
            this.status = assembly.status();
        } catch (RuntimeException | Error failure) {
            try { registrar.cleanup(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            if (executor != null) executor.shutdown();
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

    /** Latest immutable MAIN status; reading it never performs projection or rebuilds a prompt. */
    public SessionStatus status() { return status; }

    /** Owner-captured status snapshots; subscribers only enqueue. */
    public Subscription subscribeStatus(Consumer<SessionStatus> listener) {
        Objects.requireNonNull(listener);
        statusListeners.add(listener);
        return () -> statusListeners.remove(listener);
    }

    private void statusChanged(SessionStatus next) {
        status = next;
        for (var listener : statusListeners) {
            try { listener.accept(next); }
            catch (RuntimeException | Error failure) { reportFailure(failure); }
        }
    }

    /** The current MAIN model; default child profiles keep their separately captured model. */
    public Model model() { synchronized (lock) { return mainProfile.model(); } }

    /** Idle-only model change: preserve capability state/log and invalidate the next request's prompt. */
    public void switchModel(Model model) {
        Objects.requireNonNull(model);
        if (executor != null && Thread.currentThread() != ownerThread) { idleOwner(() -> switchModel(model)); return; }
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (controlsInFlight != 0) throw new IllegalStateException("a control command is in flight");
            if (inFlight != null && !(commandInFlight && runningThread == Thread.currentThread()))
                throw new IllegalStateException("a run is in flight");
            main.setModel(model);
            mainProfile = new AgentProfile(model, mainProfile.prompt(), mainProfile.capabilities(),
                    mainProfile.maxIterations(), mainProfile.systemPrompt());
            invalidateSystemPrompt.run();
            statusChanged(status.withModel(model));
        }
    }

    /** Replaces the main agent between runs, resetting all per-agent capability state. */
    public void clear() {
        if (executor != null && Thread.currentThread() != ownerThread) { idleOwner(this::clear); return; }
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (controlsInFlight != 0) throw new IllegalStateException("a control command is in flight");
            if (inFlight != null && !(commandInFlight && runningThread == Thread.currentThread())) throw new IllegalStateException("a run is in flight");
            var replacement = executor == null ? factory.assemble(MAIN_AGENT_ID, mainProfile)
                    : factory.assembleWithStatus(MAIN_AGENT_ID, mainProfile, this::statusChanged);
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
            status = replacement.status();
            followUpInputs.clear();
            try { previous.close(); }
            finally { statusChanged(status); }
        }
    }

    /** Ordinary commands reserve the idle main agent; independent control commands may also execute during runs. */
    public Optional<String> dispatch(String input) {
        Objects.requireNonNull(input, "input");
        String[] parts = input.trim().split("\\s+", 2);
        SessionCommand command;
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            command = resources.commands().stream().filter(candidate -> candidate.name().equalsIgnoreCase(parts[0]))
                    .findFirst().orElse(null);
            if (command == null) {
                if (inFlight != null || loopScheduled || !preparations.isEmpty()) throw new IllegalStateException("a run is in flight");
                return Optional.empty();
            }
            if (command.control()) {
                controlsInFlight++;
                controlOwners.merge(Thread.currentThread(), 1, Integer::sum);
            } else {
                if (inFlight != null || loopScheduled || !preparations.isEmpty()) throw new IllegalStateException("a run is in flight");
                inFlight = new CancellationToken();
                runningThread = Thread.currentThread();
                commandInFlight = true;
                if (executor != null) activity(true);
            }
        }
        try {
            Supplier<String> handler = () -> {
                if (!command.control()) synchronized (lock) { runningThread = Thread.currentThread(); }
                return command.handler().apply(this, parts.length == 1 ? "" : parts[1]);
            };
            return Optional.of(command.control() ? handler.get() : onOwner(handler));
        }
        finally {
            synchronized (lock) {
                if (command.control()) {
                    controlsInFlight--;
                    controlOwners.compute(Thread.currentThread(), (owner, count) -> count == 1 ? null : count - 1);
                } else {
                    inFlight = null;
                    runningThread = null;
                    commandInFlight = false;
                    if (executor != null) {
                        collectNotifications();
                        if (!queuedNotifications.isEmpty() && !closing) scheduleLoop();
                        else if (preparations.isEmpty()) activity(false);
                    }
                }
                lock.notifyAll();
            }
        }
    }

    /** Reserves idle admission before moving an external state-changing call onto the main owner. */
    private void idleOwner(Runnable action) {
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (inFlight != null || loopScheduled || !preparations.isEmpty() || controlsInFlight != 0)
                throw new IllegalStateException("session is busy");
            inFlight = new CancellationToken();
            commandInFlight = true;
            activity(true);
        }
        try { onOwner(() -> { synchronized (lock) { runningThread = Thread.currentThread(); } action.run(); return null; }); }
        finally {
            synchronized (lock) {
                inFlight = null; runningThread = null; commandInFlight = false;
                collectNotifications();
                if (!queuedNotifications.isEmpty() && !closing) scheduleLoop();
                else if (preparations.isEmpty()) activity(false);
                lock.notifyAll();
            }
        }
    }

    /** A blocking rendezvous, retaining interruption until the reserved command has released ownership. */
    private <T> T onOwner(Supplier<T> operation) {
        if (executor == null || Thread.currentThread() == ownerThread) return operation.get();
        var done = new CountDownLatch(1);
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        executor.execute(() -> {
            try { value.set(operation.get()); }
            catch (RuntimeException | Error thrown) { failure.set(thrown); }
            finally { done.countDown(); }
        });
        boolean interrupted = false;
        try {
            while (true) {
                try { done.await(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (failure.get() instanceof RuntimeException runtime) throw runtime;
            if (failure.get() instanceof Error error) throw error;
            return value.get();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /** Invokes a tool only for the current idle command owner, through the auxiliary permission route. */
    public ToolResult invokeCommandTool(String name, JsonNode input) {
        CancellationToken token;
        synchronized (lock) {
            if (controlOwners.containsKey(Thread.currentThread()) || !commandInFlight || runningThread != Thread.currentThread() || inFlight == null)
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
     * request, synchronous callers own {@link #takeFollowUpInputs()}; interactive mode promotes it automatically.
     *
     * @param input the user's text; capabilities prepare the run's source message and model presentation
     */
    public SubmitResult submit(String input) {
        if (executor != null) return submitInteractive(input);
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

    /** Interactive work is accepted here; execution and all main-log writes belong to one serial owner. */
    private SubmitResult submitInteractive(String input) {
        Objects.requireNonNull(input, "input");
        CancellationToken token = new CancellationToken();
        CancellationToken parent;
        boolean reserved;
        synchronized (lock) {
            if (closing || closed) throw new IllegalStateException("session is closed");
            if (commandInFlight) throw new IllegalStateException("a command is in flight");
            parent = acceptingSteering ? inFlight : null;
            preparations.put(token, Thread.currentThread());
            reserved = inFlight == null && !loopScheduled;
            if (reserved) { inFlight = token; runningThread = Thread.currentThread(); }
            activity(true);
        }
        try (var link = parent == null ? null : parent.onCancel(token::cancel)) {
            Submission prepared = resources.prepare(input, token);
            synchronized (lock) {
                if (prepared.blocked()) return new SubmitResult.Blocked(prepared.reason());
                if (token.isCancelled() || closing) return new SubmitResult.Completed(new RunOutcome.Cancelled());
                if (acceptingSteering) {
                    main.post(new Steering(prepared.prompt()));
                    return new SubmitResult.SteeringAccepted();
                }
                queuedInputs.add(prepared.prompt());
                if (reserved) { inFlight = null; runningThread = null; }
                scheduleLoop();
                return new SubmitResult.Queued();
            }
        } finally {
            synchronized (lock) {
                preparations.remove(token);
                if (reserved && inFlight == token) { inFlight = null; runningThread = null; }
                if (!closing && inFlight == null) {
                    collectNotifications();
                    if (!queuedNotifications.isEmpty()) scheduleLoop();
                }
                if (!loopScheduled && inFlight == null && preparations.isEmpty()) activity(false);
                lock.notifyAll();
            }
        }
    }

    private void activity(boolean busy) {
        if (active == busy) return;
        active = busy;
        for (var listener : activityListeners) {
            try { listener.accept(busy); }
            catch (RuntimeException | Error failure) { reportFailure(failure); }
        }
    }

    /** Busy/idle changes; listeners enqueue and must return promptly. */
    public Subscription subscribeActivity(Consumer<Boolean> listener) {
        Objects.requireNonNull(listener);
        activityListeners.add(listener);
        return () -> activityListeners.remove(listener);
    }

    /** Interactive outcomes, delivered after run cleanup; observers only enqueue. */
    public Subscription subscribeCompletion(Consumer<SessionCompletion> listener) {
        Objects.requireNonNull(listener);
        completionListeners.add(listener);
        return () -> completionListeners.remove(listener);
    }

    private void completion(SessionCompletion result) {
        for (var listener : completionListeners) {
            try { listener.accept(result); }
            catch (RuntimeException | Error failure) { reportFailure(failure); }
        }
    }

    /** Exceptional interactive callbacks have no blocking submit caller; observers enqueue their failure. */
    public Subscription subscribeFailure(Consumer<Throwable> listener) {
        Objects.requireNonNull(listener);
        failures.add(listener);
        return () -> failures.remove(listener);
    }

    private void reportFailure(Throwable failure) {
        for (var listener : failures) {
            try { listener.accept(failure); }
            catch (RuntimeException | Error ignored) { /* An observer cannot strand session ownership. */ }
        }
    }

    /** Producers never acquire the session lock: task completion may hold its own lifecycle lock. */
    private void deliverInbox(Agent target, CustomEntry entry) {
        if (!(entry instanceof TaskNotification) || !MAIN_AGENT_ID.equals(target.id())) {
            target.post(entry);
            return;
        }
        synchronized (deliveries) {
            if (closing || closed) return;
            deliveries.incrementAndGet();
        }
        try {
            target.post(entry);
            executor.execute(() -> {
                try {
                    synchronized (lock) {
                        if (!closing && target == main) {
                            collectNotifications();
                            if (!queuedNotifications.isEmpty() && inFlight == null) scheduleLoop();
                        }
                    }
                } catch (RuntimeException | Error failure) { reportFailure(failure); }
                finally { synchronized (lock) { deliveries.decrementAndGet(); lock.notifyAll(); } }
            });
        } catch (RuntimeException | Error failure) {
            synchronized (lock) { deliveries.decrementAndGet(); lock.notifyAll(); }
            if (!(failure instanceof RejectedExecutionException) || (!closing && !closed)) throw failure;
        }
    }

    private void collectNotifications() {
        main.takeInbox(TaskNotification.class::isInstance).forEach(entry ->
                queuedNotifications.add((TaskNotification) entry));
    }

    /** Caller holds lock. */
    private void scheduleLoop() {
        if (loopScheduled || closing) return;
        loopScheduled = true;
        activity(true);
        executor.execute(this::drainRuns);
    }

    private void drainRuns() {
        while (true) {
            RunInput input;
            CancellationToken token;
            synchronized (lock) {
                collectNotifications();
                if (closing || (queuedInputs.isEmpty() && queuedNotifications.isEmpty())) {
                    queuedInputs.clear();
                    queuedNotifications.clear();
                    loopScheduled = false;
                    if (preparations.isEmpty()) activity(false);
                    lock.notifyAll();
                    return;
                }
                String user = String.join("\n", queuedInputs.stream().filter(value -> !value.isBlank()).toList()).trim();
                StringBuilder summary = new StringBuilder();
                for (var notification : queuedNotifications) summary.append("<task-notification task_id=\"")
                        .append(notification.taskId()).append("\">\n").append(notification.result())
                        .append("\n</task-notification>\n\n");
                summary.append(user);
                input = queuedNotifications.isEmpty() ? new RunInput(UserMessage.text(user))
                        : new RunInput(UserMessage.text(user), UserMessage.text(summary.toString().trim()),
                        List.of(new RunInputEntry(new ArrayList<>(queuedNotifications))), 0, !user.isBlank());
                queuedInputs.clear();
                queuedNotifications.clear();
                inFlight = token = new CancellationToken();
                runningThread = Thread.currentThread();
                acceptingSteering = true;
            }
            try {
                completion(new SessionCompletion.Completed(main.run(input, token)));
            } catch (RuntimeException | Error failure) {
                completion(new SessionCompletion.Failed(failure));
                reportFailure(failure);
            }
            finally {
                synchronized (lock) {
                    acceptingSteering = false;
                    collectNotifications();
                    var pending = main.takeInbox(Steering.class::isInstance);
                    if (!token.isCancelled()) for (var entry : pending) queuedInputs.add(((Steering) entry).text());
                    inFlight = null;
                    runningThread = null;
                    lock.notifyAll();
                }
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
        List<CancellationToken> tokens;
        synchronized (lock) {
            tokens = cancellationTokens();
            if (executor != null) queuedInputs.clear();
        }
        cancelTokens(tokens);
    }

    private List<CancellationToken> cancellationTokens() {
        var tokens = new LinkedHashSet<>(preparations.keySet());
        if (inFlight != null) tokens.add(inFlight);
        return List.copyOf(tokens);
    }

    private static void cancelTokens(List<CancellationToken> tokens) {
        Throwable failure = null;
        for (var token : tokens) {
            try { token.cancel(); }
            catch (RuntimeException | Error thrown) {
                if (failure == null) failure = thrown; else if (failure != thrown) failure.addSuppressed(thrown);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    /** Whether submission preparation, a run or command still owns this session's execution lifetime. */
    public boolean isBusy() {
        synchronized (lock) {
            return inFlight != null || controlsInFlight != 0 || loopScheduled || deliveries.get() != 0
                    || !preparations.isEmpty() || (!closing && !queuedNotifications.isEmpty());
        }
    }

    /** Returns once no run is in flight — at once in synchronous mode, unless another thread's run is in flight. */
    public void awaitIdle() throws InterruptedException {
        synchronized (lock) {
            while (inFlight != null || controlsInFlight != 0 || loopScheduled || deliveries.get() != 0 || !preparations.isEmpty() || (!closing && !queuedNotifications.isEmpty())) {
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
            List<CancellationToken> tokens;
            synchronized (lock) {
                if (ownerThread == Thread.currentThread() || runningThread == Thread.currentThread() || controlOwners.containsKey(Thread.currentThread()) || preparations.containsValue(Thread.currentThread())) {
                    throw new IllegalStateException("cannot close a session from its running thread");
                }
                while (closing && !closed) {
                    try { lock.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (closed) return;
                synchronized (deliveries) { closing = true; }
                queuedInputs.clear();
                queuedNotifications.clear();
                tokens = cancellationTokens();
            }
            Throwable failure = null;
            try {
                try { cancelTokens(tokens); }
                catch (RuntimeException | Error e) { failure = e; }
                synchronized (lock) {
                    while (inFlight != null || controlsInFlight != 0 || loopScheduled || deliveries.get() != 0 || !preparations.isEmpty()) {
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
                if (executor != null) executor.shutdown();
                synchronized (lock) {
                    queuedInputs.clear(); queuedNotifications.clear();
                    closed = true; lock.notifyAll();
                }
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
        private boolean interactive;

        /** Opt into the serial interactive event loop. Headless remains synchronous by default. */
        public Builder interactive(boolean interactive) { this.interactive = interactive; return this; }

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
