package com.cafecli.ai.message;

import java.util.List;
import java.util.Objects;

/**
 * The result of one tool call, answered to the model. {@code isError} is the only outcome {@code ai} knows about; the
 * typed tool status (ADR 0019) lives above {@code ai}.
 */
public record ToolResultMessage(String toolCallId, String toolName, List<UserContent> content, boolean isError)
        implements Message {
    public ToolResultMessage {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        content = List.copyOf(content);
    }
}
