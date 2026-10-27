package com.cafecli.ai.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One piece of a message's content. Which kinds a message may hold is fixed by its role: user and tool-result
 * messages hold {@link UserContent} (text, image), assistant messages hold {@link AssistantContent} (text, thinking,
 * tool call).
 *
 * <p>The JSON type ids ({@code "type": "text" | "thinking" | "toolCall" | "image"}) are part of the persisted agent-log
 * format: renaming one breaks every log written before the rename.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
        @JsonSubTypes.Type(value = ToolCall.class, name = "toolCall"),
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image")
})
public sealed interface ContentBlock permits UserContent, AssistantContent {
}
