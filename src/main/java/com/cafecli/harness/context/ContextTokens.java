package com.cafecli.harness.context;

import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.memory.MemoryEntry;
import java.util.List;

/** The existing approximate text/image estimate, expressed over provider-neutral messages. */
final class ContextTokens {
    private ContextTokens() { }
    static long estimate(List<Message> messages) {
        long tokens = messages.size() * 4L;
        for (Message message : messages) {
            switch (message) {
                case UserMessage user -> { for (var block : user.content()) tokens += userTokens(block); }
                case ToolResultMessage result -> { for (var block : result.content()) tokens += userTokens(block); }
                case AssistantMessage assistant -> {
                    for (var block : assistant.content()) {
                        if (block instanceof TextBlock text) tokens += textTokens(text.text());
                        if (block instanceof ToolCall call) tokens += textTokens(call.arguments());
                    }
                }
            }
        }
        return tokens;
    }
    private static long userTokens(UserContent block) {
        return switch (block) {
            case TextBlock text -> textTokens(text.text());
            case ImageBlock image -> image.base64().isBlank() ? 1024
                    : Math.max(256, Math.min(4096, image.base64().length() * 3L / 4 / 768));
        };
    }
    static long textTokens(String text) {
        return MemoryEntry.estimateTokens(text);
    }
}
