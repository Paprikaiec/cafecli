package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.AgentTool;
import com.cafecli.core.IterationDecision;
import com.cafecli.core.ToolPreflight;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.context.MessageKind;
import com.cafecli.harness.permission.PermissionRoute;

import java.util.List;
import java.util.function.BiFunction;

/**
 * One agent being built, as a {@link Capability} sees it in {@link Capability#attachAgent}: where it registers what it
 * contributes to this agent. The factory composes the contributions of all attached capabilities into the agent's one
 * {@code core.AgentCallbacks}; contributions of one kind run in registration order.
 *
 * <p>To be added with the tickets that first need them (not before): projection stages (11 / 12),
 * the iteration-boundary and
 * overflow log-writer triggers (12).
 */
public interface AgentScope {

    /** The agent's id, as its events carry it: {@link AgentSession#MAIN_AGENT_ID} for the main agent. */
    String agentId();

    /** Metadata of the actual model this agent asks. */
    Model model();

    /** Skill/MCP-set or model changes request one rebuild at the next request; ordinary runs never rebuild. */
    Runnable systemPromptInvalidator();

    /** Read-only log presentation on the loop thread; visible custom kinds use their declarations, hidden kinds omit. */
    List<Message> messages();

    /** Declares a custom kind for this agent, including serialization and model presentation. */
    void messageKind(MessageKind<?> kind);

    /** A thread-safe producer for this agent only; usable after all agent hooks have completed. */
    BoundaryInbox inbox();

    /** Registers a tool for this agent, in capability declaration order. */
    void tool(AgentTool tool);

    /** Preflight participants, in registration order; an adjusted call continues, a supplied result short-circuits. */
    void beforeToolCall(BiFunction<ToolCall, CancellationToken, ToolPreflight> participant);

    /**
     * Routes fold once in declaration order immediately before the first approval, or after generic preflight when
     * no approval is registered. Generic preflight and approval otherwise retain registration order. Strictest wins.
     */
    void permissionRoute(BiFunction<ToolCall, CancellationToken, PermissionRoute> participant);

    /** Approval receives the invocation's folded route; it never lifts a leaf guard. */
    void approval(PermissionRoute.Approval participant);

    /** Completion transformations, in registration order, on the loop thread. */
    void afterToolCall(BiFunction<ToolCall, ToolResult, ToolResult> participant);

    /** After the complete assistant/results iteration is logged; a participant may continue a proposed end. */
    void iterationEnd(BiFunction<AssistantMessage, List<ToolResultEntry>, IterationDecision> participant);

    /** Appends {@code writer}'s entries to this agent's log at the start of each of its runs. */
    void runStartWriter(RunStartWriter writer);
}
