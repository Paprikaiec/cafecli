package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything product-specific about an agent's runs (ADR 0020 decision 5). The harness implements this once per agent,
 * composing its capabilities' participants into each callback; every callback has a default, so an agent built with
 * none is a plain chat loop. All callbacks run on the loop thread.
 *
 * <p><b>Context projection</b> — every request is built from the log, never kept: {@link #transformContext}
 * (projection stages), then {@link #convertToLlm}, then a {@link Context} of the system prompt, those messages and the
 * tool list, then {@link #prepareRequest}. The three are pure: they never write the log.
 *
 * <p><b>Log writers</b> — at the moments ADR 0020 decision 4 names: {@link #onRunStart}, {@link #onIterationBoundary},
 * {@link #onContextOverflow}; plus {@link #onIterationEnd}. Each returns custom entries for core to append; the
 * boundary inbox ({@link Agent#post}) is the other way in. Writers never append built-in kinds: core keeps the log
 * valid by writing those itself.
 *
 */
public interface AgentCallbacks {
    /** Resolves request-time executable tools; the loop snapshots these for the corresponding tool batch. */
    default java.util.List<AgentTool> tools(java.util.List<AgentTool> initial) { return initial; }


    /** Releases agent-owned resources after its final run, including task reaping. */
    default void onClose() { }

    /** Final publication normalization; preserve source-order identity, status and execution details. */
    default List<ToolResultEntry> afterToolBatch(List<ToolResultEntry> results) { return results; }

    /** Source-order preflight; forwards the effective call or fills its slot without executing it. */
    default ToolPreflight beforeToolCall(ToolCall call, CancellationToken cancellation) {
        return ToolPreflight.allow(call);
    }

    /** On the loop thread as each call completes, before its end event and before any results are logged. */
    default ToolResult afterToolCall(ToolCall call, ToolResult result) {
        return result;
    }

    /** Auxiliary completion is observation-only; it emits no ordinary tool events or model result. */
    default void afterAuxiliaryToolCall(AuxiliaryToolCall completion) { }

    /** Supplies presentation for mechanical failures. Core's default is content-free, with the typed outcome. */
    default ToolResult toolFailure(ToolCall call, ToolFailure failure) {
        return new ToolResult(List.of(), null, failure.status());
    }


    /**
     * Run start: entries to append right after the run's user entry, before the first request — memory recall, skill
     * bodies, a freshness search. May block (e.g. on a web search); should give up when {@code cancellation} is
     * cancelled. Default: none.
     */
    default List<CustomEntry> onRunStart(UserMessage input, CancellationToken cancellation) {
        return List.of();
    }

    /** Compatible run-start context for a cancellable auxiliary search before the first request. */
    default List<CustomEntry> onRunStart(UserMessage input, CancellationToken cancellation, ToolInvoker invoker) {
        return onRunStart(input, cancellation);
    }

    /**
     * Every iteration boundary — before each request of a run, the first included — right after the boundary inbox
     * was drained: entries to append before the context projection is built (e.g. a compaction summary once the log
     * has grown past a threshold). May block (e.g. on a summarizing model call); should give up when
     * {@code cancellation} is cancelled. Default: none.
     *
     * @param log the whole log, drained inbox entries included — not the view the projection stages produce
     */
    default List<CustomEntry> onIterationBoundary(List<LogEntry> log, CancellationToken cancellation) {
        return List.of();
    }

    /**
     * The projection stages: from the whole log to the view the model is shown, as a pure function. Runs before every
     * request. Default: the log itself.
     */
    default List<LogEntry> transformContext(List<LogEntry> log) {
        return log;
    }

    /**
     * From log entries (the view the projection stages produced) to the provider-neutral messages of the request: decides how — and
     * whether — each custom kind is shown to the model. Default: user, assistant and tool-result entries pass through
     * as their messages; custom entries are dropped. An override can delegate the built-in kinds back here.
     */
    default List<Message> convertToLlm(List<LogEntry> entries) {
        List<Message> messages = new ArrayList<>(entries.size());
        for (LogEntry entry : entries) {
            switch (entry) {
                case UserEntry user -> messages.add(user.message());
                case AssistantEntry assistant -> messages.add(assistant.message());
                case ToolResultEntry result -> messages.add(result.message());
                case CustomEntry ignored -> {
                }
            }
        }
        return messages;
    }

    /**
     * The last word on a request before it is sent — e.g. the per-agent tool list. {@code request} holds the agent's
     * system prompt, the converted messages, and the agent's tool definitions. May filter definitions. Default: unchanged.
     */
    default Context prepareRequest(Context request) {
        return request;
    }

    /**
     * After every iteration (pi's {@code finishTurn}), once the answer and its tool results are in the log: whether
     * the run goes on, ends, or ends because a harness budget ran out (token budget, stagnation), plus entries to
     * append either way (e.g. a Finish Gate stop-block that continues the run). Default: go on exactly when the model
     * called tools.
     */
    default IterationDecision onIterationEnd(AssistantMessage message, List<ToolResultEntry> results) {
        return message.toolCalls().isEmpty() ? IterationDecision.end() : IterationDecision.proceed();
    }

    /**
     * The request did not fit the context window: entries to append (e.g. a compaction summary) before core retries
     * it — once; a second overflow ends the run. Returning none means nothing could be done, and the run ends without
     * a retry. Called at most once per iteration. Default: none.
     *
     * @param log the whole log, not the view the projection stages produce
     */
    default List<CustomEntry> onContextOverflow(List<LogEntry> log, CancellationToken cancellation) {
        return List.of();
    }
}
