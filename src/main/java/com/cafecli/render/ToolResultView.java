package com.cafecli.render;

/**
 * One tool result as the renderer sees it: what {@link Renderer#appendToolResults} fills a
 * [[工具块]] in with.
 *
 * <p>Render owns this type for the same reason as {@link ToolCallView}. It carries no outcome status:
 * nothing on screen reads one today, and the result text alone is what the block shows.
 *
 * @param id            the {@code tool_call_id} of the call this answers (ADR 0017 D2)
 * @param name          the tool name, which decides whether the result is already shown as a diff
 * @param argumentsJson the call's arguments, whose {@code path} identifies that diff
 * @param text          the result text exactly as it goes back to the model
 */
public record ToolResultView(String id, String name, String argumentsJson, String text) {
}
