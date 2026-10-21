package com.cafecli.harness;

import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.core.ToolPreflight;
import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ToolCall;
import java.util.function.BiFunction;

/**
 * One agent being built, as a {@link Capability} sees it in {@link Capability#attachAgent}: where it registers what it
 * contributes to this agent. The factory composes the contributions of all attached capabilities into the agent's one
 * {@code core.AgentCallbacks}; contributions of one kind run in registration order.
 *
 * <p>To be added with the tickets that first need them (not before): hook permission routing (10),
 * iteration-end participants (10 / 12), inbox producers and projection stages (09), the iteration-boundary and
 * overflow log-writer triggers (12).
 */
public interface AgentScope {

    /** The agent's id, as its events carry it: {@link AgentSession#MAIN_AGENT_ID} for the main agent. */
    String agentId();

    /** Registers a tool for this agent, in capability declaration order. */
    void tool(AgentTool tool);

    /** Preflight participants, in registration order; an adjusted call continues, a supplied result short-circuits. */
    void beforeToolCall(BiFunction<ToolCall, CancellationToken, ToolPreflight> participant);

    /** Completion transformations, in registration order, on the loop thread. */
    void afterToolCall(BiFunction<ToolCall, ToolResult, ToolResult> participant);

    /** Appends {@code writer}'s entries to this agent's log at the start of each of its runs. */
    void runStartWriter(RunStartWriter writer);
}
