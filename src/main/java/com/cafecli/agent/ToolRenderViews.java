package com.cafecli.agent;

import com.cafecli.llm.LlmClient;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.ToolResultView;
import com.cafecli.tool.ToolExecutionResult;

import java.util.List;

/**
 * v1's side of the renderer's tool-call API: the renderer takes its own view types, so the agent
 * converts where it hands tool calls and results over, and render imports no v1 type.
 *
 * <p>Null in, null out — for the list and for each element — so whatever a renderer did with a null
 * before (skip a null result; fail on a null call) it still does; converting changes nothing about it.
 */
final class ToolRenderViews {

    private ToolRenderViews() {
    }

    static List<ToolCallView> calls(List<LlmClient.ToolCall> calls) {
        if (calls == null) {
            return null;
        }
        return calls.stream()
                .map(call -> call == null ? null
                        : new ToolCallView(call.id(), call.function().name(), call.function().arguments()))
                .toList();
    }

    /** The text is {@link ToolExecutionResult#result()}, the very string the model is sent. */
    static List<ToolResultView> results(List<ToolExecutionResult> results) {
        if (results == null) {
            return null;
        }
        return results.stream()
                .map(result -> result == null ? null
                        : new ToolResultView(result.id(), result.name(), result.argumentsJson(), result.result()))
                .toList();
    }
}
