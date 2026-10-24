package com.cafecli.harness.config;

/** Captured wire settings for one provider; never expose credentials through diagnostics. */
public record ProviderSettings(String apiKey, String model, String baseUrl) {
    public boolean hasKey() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String toString() { return "ProviderSettings[model=" + model + ", baseUrl=" + baseUrl + "]"; }
}
