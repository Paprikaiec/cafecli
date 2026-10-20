package com.cafecli.ai.message;

import java.util.List;

public record UserMessage(List<UserContent> content) implements Message {
    public UserMessage {
        content = List.copyOf(content);
    }

    public static UserMessage text(String text) {
        return new UserMessage(List.of(new TextBlock(text)));
    }
}
