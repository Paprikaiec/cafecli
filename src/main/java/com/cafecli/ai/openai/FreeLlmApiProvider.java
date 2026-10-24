package com.cafecli.ai.openai;

/** FreeLlmApi's OpenAI-compatible wire adapter. Model choice belongs to each request. */
public final class FreeLlmApiProvider extends OpenAiCompatibleProvider {
    public static final String DEFAULT_URL = "http://localhost:5173/v1";

    public FreeLlmApiProvider(String apiKey, String baseUrl, HttpTimeouts timeouts) {
        super(apiKey, baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl, timeouts);
    }

}
