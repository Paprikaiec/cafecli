package com.cafecli.core;

/** Mechanical failures, translated to model-facing wording by the harness callback. */
public record ToolFailure(Kind kind, String detail, long timeoutSeconds) {
    public enum Kind { UNKNOWN_TOOL, EXECUTION, CANCELLED, INTERRUPTED, BATCH_INTERRUPTED, TIMEOUT }

    public ToolResult.Status status() {
        return switch (kind) {
            case UNKNOWN_TOOL, EXECUTION -> ToolResult.Status.ERROR;
            case CANCELLED, INTERRUPTED, BATCH_INTERRUPTED -> ToolResult.Status.CANCELLED;
            case TIMEOUT -> ToolResult.Status.TIMEOUT;
        };
    }
}
