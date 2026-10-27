package com.cafecli.ai.openai;

/** Kimi's OpenAI-compatible wire adapter. Model choice belongs to each request. */
public final class KimiProvider extends OpenAiCompatibleProvider {
    public static final String DEFAULT_URL = "https://api.moonshot.ai/v1";

    public KimiProvider(String apiKey, String baseUrl, HttpTimeouts timeouts) {
        super(apiKey, baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl, timeouts);
    }

    @Override protected boolean sendThinkingHistory() { return true; }
    @Override protected boolean contextOverflow(com.fasterxml.jackson.databind.JsonNode error) {
        return "invalid_request_error".equals(error.path("type").asText())
                && error.path("message").asText("").toLowerCase(java.util.Locale.ROOT).startsWith("input token length too long");
    }

}
