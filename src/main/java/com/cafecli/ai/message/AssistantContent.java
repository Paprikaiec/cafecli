package com.cafecli.ai.message;

/** Content an assistant message may hold: text, thinking, and tool calls. */
public sealed interface AssistantContent extends ContentBlock permits TextBlock, ThinkingBlock, ToolCall {
}
