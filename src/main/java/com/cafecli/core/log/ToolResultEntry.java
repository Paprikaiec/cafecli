package com.cafecli.core.log;

import com.cafecli.ai.message.ToolResultMessage;

import java.util.Objects;

/**
 * The result of one tool call, as answered to the model. (Ticket 05 adds what the model is not shown: the typed tool
 * status of ADR 0019 and structured details such as a diff.)
 */
public record ToolResultEntry(ToolResultMessage message) implements LogEntry {
    public ToolResultEntry {
        Objects.requireNonNull(message, "message");
    }
}
