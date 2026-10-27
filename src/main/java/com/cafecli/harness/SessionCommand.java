package com.cafecli.harness;

import com.cafecli.input.CommandCompleter;
import com.cafecli.input.CommandCompleter.Query;
import com.cafecli.input.CommandCompleter.Item;
import com.cafecli.input.CommandHints;
import com.cafecli.input.CommandHints.Hint;
import java.util.List;
import java.util.function.Function;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.cafecli.input.SlashCommandGrammar;
import java.util.function.BiFunction;

/** A session slash command. Controls touch independent domain state and may run alongside the main agent. */
public record SessionCommand(String name, String description, BiFunction<AgentSession, String, String> handler,
                             boolean control, Function<Query,List<Item>> completer,
                             List<Hint> hints,
                             BiFunction<AgentSession,String,AgentSession.CommandPresentation> presentation) {
    public SessionCommand(String name, String description, BiFunction<AgentSession, String, String> handler) {
        this(name, description, handler, false);
    }

    public SessionCommand(String name, String description, BiFunction<AgentSession,String,String> handler, boolean control) {
        this(name,description,handler,control,null,CommandHints.forCommand(name,description),
                (session,args) -> AgentSession.CommandPresentation.normal());
    }

    public SessionCommand withCompleter(Function<Query,List<Item>> completer) {
        return new SessionCommand(name,description,handler,control,completer,hints,presentation);
    }
    public SessionCommand withPresentation(BiFunction<AgentSession,String,AgentSession.CommandPresentation> presentation) {
        return new SessionCommand(name,description,handler,control,completer,hints,presentation);
    }
    List<Item> complete(Query query,java.nio.file.Path completionRoot) {
        return completer != null ? completer.apply(query) : new CommandCompleter(
                List::of,List::of,() -> hints,completionRoot).suggest(query);
    }

    public static SessionCommand control(String name, String description, BiFunction<AgentSession, String, String> handler) {
        return new SessionCommand(name, description, handler, true);
    }

    /** The legacy grammar remains a neutral leaf shared with the v1 parser wrapper. */
    Optional<String> arguments(String input) {
        String normalized = input.trim();
        String[] parts = normalized.split("\\s+", 2);
        String word = parts[0].startsWith("/") ? parts[0] : "/" + parts[0];
        if (!name.equalsIgnoreCase(word)) return Optional.empty();
        if (LEGACY.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            var parsed = SlashCommandGrammar.parse(normalized);
            if (parsed.type() == SlashCommandGrammar.CommandType.UNKNOWN_COMMAND
                    || parsed.type() == SlashCommandGrammar.CommandType.NONE) return Optional.empty();
        } else if (!normalized.startsWith("/")) return Optional.empty();
        return Optional.of(parts.length == 1 ? "" : parts[1]);
    }

    private static final Set<String> LEGACY = Set.of("/exit", "/quit", "/cancel", "/clear", "/transcript",
            "/history", "/model", "/plan", "/hitl", "/memory", "/mem", "/save", "/index", "/search",
            "/graph", "/context", "/ctx", "/policy", "/config", "/audit", "/snapshot", "/restore",
            "/browser", "/task", "/skill", "/hooks", "/mcp");

    public SessionCommand {
        if (name == null || !name.startsWith("/") || name.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("command name must be a slash word");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(handler, "handler");
        hints = List.copyOf(hints);
        Objects.requireNonNull(presentation, "presentation");
    }
}
