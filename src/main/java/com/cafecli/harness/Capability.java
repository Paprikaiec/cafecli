package com.cafecli.harness;

/**
 * One harness area — MCP, skills, memory, tasks, trace, … (CONTEXT.md 「能力」) — plugged into the session through
 * this internal SPI. Not a plugin system: no loading, no isolation, no promises outside Cafecli. Capabilities are
 * constructed by the composition root, with their dependencies passed to their constructors, and handed to the
 * session in a declared order; that order is the order every hook below runs in.
 *
 * <p>Two hooks, both optional:
 * <ul>
 *   <li>{@link #attachSession} runs once, when the session opens, before any agent is built: session-scoped
 *       contributions, through the {@link SessionRegistrar}.</li>
 *   <li>{@link #attachAgent} runs for every agent the session's {@link AgentFactory} builds from a profile that attaches
 *       this capability, before that agent exists: per-agent contributions, through the {@link AgentScope}. Per-agent
 *       state is whatever the capability creates in this hook (and its contributions close over), so every agent —
 *       a fresh main agent after {@code /clear}, each sub-agent — starts with its own.</li>
 * </ul>
 * A capability its profile does not attach contributes nothing to that agent (Capability Closure).
 *
 * <p>Registrations are accepted only while a hook runs; a registrar or scope kept for later refuses them.
 */
public interface Capability {

    default void attachSession(SessionRegistrar session) {
    }

    default void attachAgent(AgentScope agent) {
    }
}
