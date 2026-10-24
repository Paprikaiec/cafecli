package com.cafecli.harness;

import com.cafecli.core.AgentEvent;
import com.cafecli.ai.CancellationToken;
import java.util.function.BiFunction;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** The {@link SessionRegistrar} the session's capabilities register through while their session hooks run. */
final class SessionContributions implements SessionRegistrar {

    private final SessionEvents events;
    private final boolean interactive;
    private final String sessionId = "session-" + java.util.UUID.randomUUID();
    private final List<BiFunction<String, CancellationToken, Submission>> submitters = new ArrayList<>();
    private final List<Runnable> clearers = new ArrayList<>();
    private final List<SessionCommand> commands = new ArrayList<>();
    private boolean closed;
    private boolean cleaned;
    private final List<AutoCloseable> resources = new ArrayList<>();

    SessionContributions(SessionEvents events) {
        this(events, false);
    }

    SessionContributions(SessionEvents events, boolean interactive) {
        this.events = events;
        this.interactive = interactive;
    }
    @Override public boolean interactive() { return interactive; }

    @Override public String sessionId() { return sessionId; }

    @Override public void command(SessionCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        if (commands.stream().anyMatch(existing -> existing.name().equals(command.name())))
            throw new IllegalArgumentException("duplicate command " + command.name());
        commands.add(command);
    }

    @Override public void onClear(Runnable participant) {
        requireOpen(); clearers.add(Objects.requireNonNull(participant, "participant"));
    }

    void clear() { clearers.forEach(Runnable::run); }

    List<SessionCommand> commands() { return List.copyOf(commands); }

    /** The listener stays for the life of the session. */
    @Override
    public void eventListener(Consumer<? super AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        requireOpen();
        events.add(listener);
    }

    @Override
    public void onClose(AutoCloseable resource) {
        requireOpen();
        resources.add(Objects.requireNonNull(resource, "resource"));
    }

    @Override public void onSubmit(BiFunction<String, CancellationToken, Submission> participant) {
        requireOpen(); submitters.add(Objects.requireNonNull(participant, "participant"));
    }

    Submission prepare(String input, CancellationToken cancellation) {
        for (var participant : submitters) {
            Submission decision = Objects.requireNonNull(participant.apply(input, cancellation), "submission");
            if (decision.blocked()) return decision;
            input = decision.prompt();
        }
        return Submission.proceed(input);
    }

    /** Stops owned resources, after the run or after partial construction. Called by the session's closing owner. */
    void cleanup() {
        if (cleaned) return;
        cleaned = true;
        closed = true;
        Throwable failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); }
            catch (Throwable e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("session cleanup failed", failure);
    }

    /** Ends registration: the session hooks have run. */
    void close() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("session contributions are registered while the session hooks run");
        }
    }
}
