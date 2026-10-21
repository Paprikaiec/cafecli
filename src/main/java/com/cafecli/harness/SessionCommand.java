package com.cafecli.harness;

import java.util.Objects;
import java.util.function.BiFunction;

/** A session slash command. The UI owns display and unknown-command handling. */
public record SessionCommand(String name, String description, BiFunction<AgentSession, String, String> handler) {
    public SessionCommand {
        if (name == null || !name.startsWith("/") || name.contains(" "))
            throw new IllegalArgumentException("command name must be a slash word");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(handler, "handler");
    }
}
