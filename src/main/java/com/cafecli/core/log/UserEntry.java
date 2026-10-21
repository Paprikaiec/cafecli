package com.cafecli.core.log;

import com.cafecli.ai.message.UserMessage;

import java.util.Objects;

/** What the user submitted to start a run. */
public record UserEntry(UserMessage message) implements LogEntry {
    public UserEntry {
        Objects.requireNonNull(message, "message");
    }
}
