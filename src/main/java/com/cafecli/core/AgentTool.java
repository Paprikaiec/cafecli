package com.cafecli.core;

import com.cafecli.ai.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/** A tool's definition and guarded execution. Progress may arrive on the executing thread. */
public interface AgentTool {
    /** Compatible execution context for tools delegating to another tool in this request. */
    default ToolResult execute(String callId, JsonNode input, CancellationToken cancellation,
                               Consumer<ToolResult> progress, ToolInvoker invoker) throws Exception {
        return execute(callId, input, cancellation, progress);
    }

    String name();
    String description();
    JsonNode parameters();
    ToolResult execute(String callId, JsonNode input, CancellationToken cancellation,
                       Consumer<ToolResult> progress) throws Exception;
}
