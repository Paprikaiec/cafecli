package com.cafecli.harness;

import com.cafecli.core.RunInput;
import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.core.AgentTool;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.LogEntry;
import java.util.function.UnaryOperator;
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
 */
public interface AgentScope {

    /** The agent's id, as its events carry it: {@link AgentSession#MAIN_AGENT_ID} for the main agent. */
    String agentId();

    /** Metadata of the actual model this agent asks. */
    Model model();

    /** Loop-thread access to the cached prompt; consumes explicit invalidation with one rebuild. */
    String systemPrompt();

    /** Skill/MCP-set or model changes request one rebuild at the next request; ordinary runs never rebuild. */
    Runnable systemPromptInvalidator();

    /** Read-only projected history on the loop thread; registered visible/hidden semantics apply. */
    List<Message> messages();

    /** Presents an immutable snapshot through this agent's ordered projection stages and registered kinds. */
    List<Message> projectMessages(List<LogEntry> log);

    /**
     * Registers pure source-range normalization, before all ordinary view stages regardless of capability order.
     * Source normalizers run in registration order, receiving immutable snapshots. Range-sensitive normalizers must
     * run before transformations that change source positions; ordinary filtering/rewriting belongs in projectionStage.
     */
    void sourceProjectionStage(UnaryOperator<List<LogEntry>> stage);

    /** Registers a pure view transformation; immutable views compose in registration order after source normalization. */
    void projectionStage(UnaryOperator<List<LogEntry>> stage);

    /** Pure proposed user presentation, including preparation-owned user-prefix metadata. */
    RunInput presentInput(RunInput input);

    /** Ordered one-time input preparation, before source append; later run-start writers receive the raw input. */
    void inputPreparation(BiFunction<RunInput, CancellationToken, RunInput> participant);

    /** Log writers at the iteration boundary and after a context overflow, in registration order. */
    void iterationBoundaryWriter(LogWriter writer);
    void contextOverflowWriter(LogWriter writer);

    /** Publishes product events on the run thread; the origin must name this agent. */
    void emit(AgentEvent event);

    /** Budget checks run after ordinary iteration-end participants, only when the run would continue. */
    void iterationBudget(BiFunction<AssistantMessage, List<ToolResultEntry>, IterationDecision> participant);

    /** Declares a custom kind for this agent, including serialization and model presentation. */
    void messageKind(MessageKind<?> kind);

    /** A thread-safe producer for this agent only; usable after all agent hooks have completed. */
    BoundaryInbox inbox();

    /** Registers a tool for this agent, in capability declaration order. */
    void tool(AgentTool tool);

    /** Supplies an immutable live tool set at request preparation; execution uses that exact request snapshot. */
    void toolSource(java.util.function.Supplier<List<AgentTool>> source);

    /** Decorates the complete immutable request snapshot, after static and discovered tool sources. */
    void toolSnapshotStage(UnaryOperator<List<AgentTool>> stage);

    /** Owner-thread observations for auxiliary work; no ordinary tool event/log entry is published. */
    void afterAuxiliaryToolCall(java.util.function.Consumer<com.cafecli.core.AuxiliaryToolCall> observer);

    /** Adds static prompt sections; evaluated only when the cached prompt is explicitly rebuilt. */
    void promptContext(java.util.function.UnaryOperator<com.cafecli.prompt.PromptContext> contribution);

    /** Owns per-agent resources; closed after the final run, on clear or task reaping. */
    void onClose(AutoCloseable resource);

    /** Normalizes final batch publication after immediate individual completion events. */
    void toolBatchResults(UnaryOperator<List<ToolResultEntry>> normalizer);

    /** Preflight participants, in registration order; an adjusted call continues, a supplied result short-circuits. */
    void beforeToolCall(BiFunction<ToolCall, CancellationToken, ToolPreflight> participant);

    /**
     * Routes fold once in declaration order immediately before the first approval, or after generic preflight when
     * no approval is registered. Generic preflight and approval otherwise retain registration order. Strictest wins.
     */
    void permissionRoute(BiFunction<ToolCall, CancellationToken, PermissionRoute> participant);

    /** Per-agent ordinary-approval constraint, checked only after Hook ALLOW/headless/disabled fast paths. */
    void approvalNotice(java.util.function.Function<ToolCall, String> constraint);
    String approvalNotice(ToolCall call);

    /** Approval receives the invocation's folded route; it never lifts a leaf guard. */
    void approval(PermissionRoute.Approval participant);

    /** Completion transformations, in registration order, on the loop thread. */
    void afterToolCall(BiFunction<ToolCall, ToolResult, ToolResult> participant);

    /** After the complete assistant/results iteration is logged; a participant may continue a proposed end. */
    void iterationEnd(BiFunction<AssistantMessage, List<ToolResultEntry>, IterationDecision> participant);

    /** Appends {@code writer}'s entries to this agent's log at the start of each of its runs. */
    void runStartWriter(RunStartWriter writer);
    default void runStartWriter(RunStartWriter.WithTools writer) { runStartWriter((RunStartWriter) writer); }
}
