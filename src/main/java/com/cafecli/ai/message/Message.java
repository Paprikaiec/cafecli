package com.cafecli.ai.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A provider-neutral conversation message. The system prompt is not a message; it travels separately in
 * {@link com.cafecli.ai.Context}.
 *
 * <p>The JSON type ids ({@code "role": "user" | "assistant" | "toolResult"}) are part of the persisted agent-log
 * format.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
        @JsonSubTypes.Type(value = ToolResultMessage.class, name = "toolResult")
})
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
}
