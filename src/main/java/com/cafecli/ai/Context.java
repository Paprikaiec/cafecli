package com.cafecli.ai;

import com.cafecli.ai.message.Message;

import java.util.List;

/**
 * Everything one request sends the model: the system prompt (not a message — it travels here, separately), the
 * conversation, and the tools the model may call. Immutable.
 */
public record Context(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
    public Context {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
