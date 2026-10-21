package com.cafecli.core.log;

import com.cafecli.ai.message.AssistantMessage;

import java.util.Objects;

/**
 * One complete answer of the model. A cancelled answer (partial or complete) and the partial answer of a failed request
 * are never logged.
 */
public record AssistantEntry(AssistantMessage message) implements LogEntry {
    public AssistantEntry {
        Objects.requireNonNull(message, "message");
    }
}
