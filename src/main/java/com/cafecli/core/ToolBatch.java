package com.cafecli.core;

import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.AgentEvent.Origin;
import com.cafecli.core.AgentEvent.ToolEnd;
import com.cafecli.core.AgentEvent.ToolStart;
import com.cafecli.core.log.ToolResultEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns one answer's tool calls into their results: one result per call, in call order, each between a
 * {@link ToolStart} and a {@link ToolEnd} event. Every call needs a result before the next request.
 *
 * <p>The one place ticket 05 replaces: an agent has no tools yet, so every call is answered as a call to an unknown
 * tool. 05 brings the tool interface and the real scheduling (preflight through {@code beforeToolCall} in call order,
 * parallel execution, {@code afterToolCall}) and keeps this answer for a name no tool has.
 */
final class ToolBatch {

    private final Origin origin;
    private final Consumer<AgentEvent> events;

    ToolBatch(Origin origin, Consumer<AgentEvent> events) {
        this.origin = origin;
        this.events = events;
    }

    List<ToolResultEntry> run(List<ToolCall> calls) {
        List<ToolResultEntry> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            events.accept(new ToolStart(origin, call));
            ToolResultEntry result = unknownTool(call);
            results.add(result);
            events.accept(new ToolEnd(origin, result));
        }
        return results;
    }

    /** v1's exact model-facing text ({@code ToolRegistry}), so the parity harness sees equal requests. */
    private static ToolResultEntry unknownTool(ToolCall call) {
        return new ToolResultEntry(new ToolResultMessage(call.id(), call.name(),
                List.of(new TextBlock("未知工具: " + call.name())), true));
    }
}
