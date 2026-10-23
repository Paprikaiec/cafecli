package com.cafecli.harness;

import java.util.Objects;
import java.util.function.BiFunction;

/** A session slash command. Controls touch independent domain state and may run alongside the main agent. */
public record SessionCommand(String name, String description, BiFunction<AgentSession, String, String> handler,
                             boolean control) {
    public SessionCommand(String name, String description, BiFunction<AgentSession, String, String> handler) {
        this(name, description, handler, false);
    }

    public static SessionCommand control(String name, String description, BiFunction<AgentSession, String, String> handler) {
        return new SessionCommand(name, description, handler, true);
    }

    public SessionCommand {
        if (name == null || !name.startsWith("/") || name.contains(" "))
            throw new IllegalArgumentException("command name must be a slash word");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(handler, "handler");
    }
}
