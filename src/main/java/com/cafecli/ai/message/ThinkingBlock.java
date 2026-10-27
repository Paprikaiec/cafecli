package com.cafecli.ai.message;

import java.util.Objects;

/**
 * The model's reasoning. {@code signature} is the opaque token some providers attach so the block can be sent back
 * verbatim; {@code null} when the provider sends none (every OpenAI-compatible provider today).
 */
public record ThinkingBlock(String thinking, String signature) implements AssistantContent {
    public ThinkingBlock {
        Objects.requireNonNull(thinking, "thinking");
    }

    public ThinkingBlock(String thinking) {
        this(thinking, null);
    }
}
