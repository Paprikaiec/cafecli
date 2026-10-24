package com.cafecli.ai.openai;

/** Step's OpenAI-compatible wire adapter. Model choice belongs to each request. */
public final class StepProvider extends OpenAiCompatibleProvider {
    public static final String DEFAULT_URL = "https://api.stepfun.com/v1";

    public StepProvider(String apiKey, String baseUrl, HttpTimeouts timeouts) {
        super(apiKey, baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl, timeouts);
    }

    @Override protected void customizeRequest(com.cafecli.ai.Model model, com.fasterxml.jackson.databind.node.ObjectNode body) {
        body.put("reasoning_format", "deepseek-style");
        if (model.id().contains("2603")) body.put("reasoning_effort", "high");
    }
    // Assumption: this provider/gateway uses OpenAI's structured overflow code; no captured error fixture yet.
    @Override protected boolean contextOverflow(com.fasterxml.jackson.databind.JsonNode error) {
        return "context_length_exceeded".equals(error.path("code").asText());
    }

}
