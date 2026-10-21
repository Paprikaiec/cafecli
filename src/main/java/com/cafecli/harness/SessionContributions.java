package com.cafecli.harness;

import com.cafecli.core.AgentEvent;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** The {@link SessionRegistrar} the session's capabilities register through while their session hooks run. */
final class SessionContributions implements SessionRegistrar {

    private final SessionEvents events;
    private boolean closed;
    private boolean cleaned;
    private final List<AutoCloseable> resources = new ArrayList<>();

    SessionContributions(SessionEvents events) {
        this.events = events;
    }

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
