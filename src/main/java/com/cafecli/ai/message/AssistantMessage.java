package com.cafecli.ai.message;

import java.util.List;
import java.util.Objects;

/**
 * What the model answered, in the order it produced it, plus why it stopped, what it cost, and which model produced
 * it ({@code provider} and {@code model} are the {@link com.cafecli.ai.Model}'s provider and id).
 *
 * <p>A message whose stop reason is {@link StopReason#ERROR} or {@link StopReason#ABORTED} is a partial: its last
 * block may have been cut short (a tool call's arguments may not even be valid JSON).
 */
public record AssistantMessage(List<AssistantContent> content, StopReason stopReason, Usage usage,
                               String provider, String model) implements Message {
    public AssistantMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(stopReason, "stopReason");
        usage = usage == null ? Usage.ZERO : usage;
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
    }

    /** The text blocks joined in order; {@code ""} when there are none. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (AssistantContent block : content) {
            if (block instanceof TextBlock text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    public List<ToolCall> toolCalls() {
        return content.stream()
                .filter(ToolCall.class::isInstance)
                .map(ToolCall.class::cast)
                .toList();
    }
}
