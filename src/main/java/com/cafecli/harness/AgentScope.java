package com.cafecli.harness;

/**
 * One agent being built, as a {@link Capability} sees it in {@link Capability#attachAgent}: where it registers what it
 * contributes to this agent. The factory composes the contributions of all attached capabilities into the agent's one
 * {@code core.AgentCallbacks}; contributions of one kind run in registration order.
 *
 * <p>To be added with the tickets that first need them (not before): tools (05), permission participants (08 / 10),
 * iteration-end participants (10 / 12), inbox producers and projection stages (09), the iteration-boundary and
 * overflow log-writer triggers (12).
 */
public interface AgentScope {

    /** The agent's id, as its events carry it: {@link AgentSession#MAIN_AGENT_ID} for the main agent. */
    String agentId();

    /** Appends {@code writer}'s entries to this agent's log at the start of each of its runs. */
    void runStartWriter(RunStartWriter writer);
}
