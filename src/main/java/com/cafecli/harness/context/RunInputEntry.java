package com.cafecli.harness.context;

import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;
import java.util.List;
import java.util.Optional;

/** Provenance and one-time presentation of a run triggered by custom inputs. No synthetic source user is needed. */
public record RunInputEntry(List<CustomEntry> sources, UserMessage source, UserMessage presentation,
                            List<CustomEntry> metadata, boolean sourceUser) implements CustomEntry {
    public static final MessageKind<RunInputEntry> KIND = new MessageKind<>("run-input", RunInputEntry.class,
            entry -> entry.sourceUser() ? Optional.empty() : Optional.ofNullable(entry.presentation()));
    public RunInputEntry {
        sources = List.copyOf(sources);
        metadata = List.copyOf(metadata);
    }
    public RunInputEntry(List<CustomEntry> sources) { this(sources, null, null, List.of(), false); }
}
