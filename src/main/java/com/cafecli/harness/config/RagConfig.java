package com.cafecli.harness.config;

/** Explicit embedding settings; v1 reads environment before same-named properties. */
public record RagConfig(String provider, String model, String baseUrl, String apiKey) {
    public static final RagConfig DEFAULT = new RagConfig("ollama", "nomic-embed-text:latest", "http://localhost:11434", "");
}
