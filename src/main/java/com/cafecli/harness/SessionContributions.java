package com.cafecli.harness;

import com.cafecli.core.AgentEvent;

import java.util.Objects;
import java.util.function.Consumer;

/** The {@link SessionRegistrar} the session's capabilities register through while their session hooks run. */
final class SessionContributions implements SessionRegistrar {

    private final SessionEvents events;
    private boolean closed;

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
