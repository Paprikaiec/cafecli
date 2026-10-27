package com.cafecli.ai.message;

import java.util.Objects;

public record TextBlock(String text) implements UserContent, AssistantContent {
    public TextBlock {
        Objects.requireNonNull(text, "text");
    }
}
