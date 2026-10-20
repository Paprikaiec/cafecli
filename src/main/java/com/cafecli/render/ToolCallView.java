package com.cafecli.render;

/**
 * One tool call as the renderer sees it: what {@link Renderer#appendToolCalls} draws a [[工具块]]'s
 * call header from.
 *
 * <p>Render owns this type so that whoever drives it — the v1 agent today, v2's event → render adapter
 * later — converts at its own call site, and render imports neither the LLM client's tool call nor
 * the tool registry's types (ADR 0020 D9).
 *
 * @param id            the {@code tool_call_id}; {@link ToolResultView#id()} matches a result to it
 * @param name          the tool name the model called
 * @param argumentsJson the arguments exactly as the model sent them
 */
public record ToolCallView(String id, String name, String argumentsJson) {
}
