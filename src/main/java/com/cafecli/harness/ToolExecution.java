package com.cafecli.harness;

import com.cafecli.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.TimeUnit;

/** Leaf execution provenance, independent of the business outcome and model-facing wording. */
public record ToolExecution(Kind kind, String reason, long durationMs) {
    public enum Kind { RETURNED, POLICY_DENIED, FAILED }
    private static final ObjectMapper JSON = new ObjectMapper();

    public static ToolResult record(ToolResult result, Kind kind, String reason, long startNanos) {
        ObjectNode details = result.details() instanceof ObjectNode object ? object : JSON.createObjectNode();
        details.set("execution", JSON.valueToTree(new ToolExecution(kind, reason,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))));
        return new ToolResult(result.content(), details, result.status());
    }

    /** Null for results supplied by preflight or core rather than by a leaf invocation. */
    public static ToolExecution from(ToolResult result) {
        var details = result.details();
        if (details == null || !details.has("execution")) return null;
        return JSON.convertValue(details.get("execution"), ToolExecution.class);
    }
}
