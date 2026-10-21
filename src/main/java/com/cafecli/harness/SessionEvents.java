package com.cafecli.harness;

import com.cafecli.core.AgentEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The session's one event stream: every event of every agent the session's factory builds, handed to each listener in
 * the order the listeners were added — capabilities' listeners first (they are added when the session opens), then
 * {@link AgentSession#subscribe} subscribers. Delivery is core's: synchronous, on the thread running the agent, so a
 * listener only enqueues. Listeners may be added and removed from any thread.
 */
final class SessionEvents implements Consumer<AgentEvent> {

    private final List<Consumer<? super AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    AgentSession.Subscription add(Consumer<? super AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        // A wrapper of its own, so one listener added twice is removed once per subscription.
        Consumer<AgentEvent> entry = listener::accept;
        listeners.add(entry);
        return () -> listeners.remove(entry);
    }

    @Override
    public void accept(AgentEvent event) {
        for (Consumer<? super AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
