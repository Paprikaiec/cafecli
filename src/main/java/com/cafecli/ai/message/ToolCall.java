package com.cafecli.ai.message;

import java.util.Objects;

/**
 * A tool call the model made — the request half only. Not a tool block (CONTEXT.md 工具块), which is render's view of
 * a call together with its result.
 *
 * <p>{@code arguments} is the raw JSON text exactly as the model sent it — not parsed, not normalized — so an adapter
 * can echo it back byte for byte, and a malformed argument string survives to be reported by whoever parses it.
 */
public record ToolCall(String id, String name, String arguments) implements AssistantContent {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}
