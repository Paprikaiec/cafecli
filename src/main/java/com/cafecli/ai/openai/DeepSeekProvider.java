package com.cafecli.ai.openai;

/** DeepSeek owns the reasoning_content history round-trip. Model choice stays in each request's model record. */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {
    public static final String DEFAULT_URL = "https://api.deepseek.com/chat/completions";

    public DeepSeekProvider(String apiKey) {
        this(apiKey, DEFAULT_URL);
    }

    public DeepSeekProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, HttpTimeouts.DEFAULT);
    }

    public DeepSeekProvider(String apiKey, String baseUrl, HttpTimeouts timeouts) {
        super(apiKey, baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl, timeouts);
    }

    @Override
    protected boolean sendThinkingHistory() {
        return true;
    }
}
