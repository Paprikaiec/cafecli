package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.core.AgentEvent;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * What a {@link Capability} contributes to the whole session, in {@link Capability#attachSession}.

 */
public interface SessionRegistrar {

    /** Whether this session owns the interactive event loop. */
    default boolean interactive() { return false; }

    /** Stable identity shared by this session's agents and runs, in v1's {@code session-<UUID>} format. */
    String sessionId();

    /** Registers a slash command, in declaration order. */
    void command(SessionCommand command);

    /** Resets session-owned state whose current grants must not survive /clear. */
    void onClear(Runnable participant);

    /**
     * Hears every event of every agent of the session — the main agent's, and sub-agents' from ticket 17 — for the
     * life of the session (e.g. the trace, ticket 20). Listeners hear an event in registration order, all of them
     * before any {@link AgentSession#subscribe} subscriber; synchronously, on the thread running the agent, so a
     * listener only enqueues, and an exception it throws ends the run.
     */
    void eventListener(Consumer<? super AgentEvent> listener);

    /** Every user submission, after admission and outside the session lock; may rewrite or block it. */
    void onSubmit(BiFunction<String, CancellationToken, Submission> participant);

    /**
     * Registers an owned resource. Session close stops the run, then closes resources in reverse registration order;
     * construction failure also closes every resource already registered. All are attempted if one close fails.
     */
    void onClose(AutoCloseable resource);
}
