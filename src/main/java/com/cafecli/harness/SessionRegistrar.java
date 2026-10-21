package com.cafecli.harness;

import com.cafecli.core.AgentEvent;

import java.util.function.Consumer;

/**
 * What a {@link Capability} contributes to the whole session, in {@link Capability#attachSession}.
 *
 * <p>To be added with the tickets that first need them (not before): custom message kinds with their serialization
 * and presentation to the model (09), slash commands (11).
 */
public interface SessionRegistrar {

    /**
     * Hears every event of every agent of the session — the main agent's, and sub-agents' from ticket 17 — for the
     * life of the session (e.g. the trace, ticket 20). Listeners hear an event in registration order, all of them
     * before any {@link AgentSession#subscribe} subscriber; synchronously, on the thread running the agent, so a
     * listener only enqueues, and an exception it throws ends the run.
     */
    void eventListener(Consumer<? super AgentEvent> listener);

    /**
     * Registers an owned resource. Session close stops the run, then closes resources in reverse registration order;
     * construction failure also closes every resource already registered. All are attempted if one close fails.
     */
    void onClose(AutoCloseable resource);
}
