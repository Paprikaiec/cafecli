package com.cafecli.core;

import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;

import java.util.List;
import java.util.Objects;

/** Prepared source input, optional cached model presentation, and separately retained input metadata. */
public record RunInput(UserMessage source, UserMessage presentation, List<CustomEntry> entries,
                       int preparedPrefixCount) {
    public RunInput {
        Objects.requireNonNull(source, "source");
        entries = List.copyOf(entries);
        if (preparedPrefixCount < 0 || preparedPrefixCount > entries.size()
                || (presentation == null && preparedPrefixCount != 0))
            throw new IllegalArgumentException("prepared prefixes require a presentation");
    }
    public RunInput(UserMessage source) { this(source, null, List.of(), 0); }
}
