package com.cafecli.core.log;

import com.cafecli.ai.message.UserMessage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/** Source input plus an optional one-time model presentation; the source never contains inserted prefixes. */
public record UserEntry(UserMessage message,
                        @JsonInclude(JsonInclude.Include.NON_NULL)
                        UserMessage presentation,
                        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
                        int preparedPrefixCount) implements LogEntry {
    public UserEntry {
        Objects.requireNonNull(message, "message");
        if (preparedPrefixCount < 0 || (presentation == null && preparedPrefixCount != 0))
            throw new IllegalArgumentException("prepared prefixes require a presentation");
    }
    public UserEntry(UserMessage message) { this(message, null, 0); }
}
