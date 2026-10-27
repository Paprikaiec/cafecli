package com.cafecli.ai.message;

/**
 * Token usage of one model response. {@code inputTokens} counts the whole prompt; {@code cachedInputTokens} is the
 * part of it the provider reported as served from its prompt cache.
 */
public record Usage(int inputTokens, int outputTokens, int cachedInputTokens) {
    public static final Usage ZERO = new Usage(0, 0, 0);
}
