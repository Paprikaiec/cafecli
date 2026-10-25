package com.cafecli.harness.config;

import java.nio.file.Path;

/** Captured embeddings and vector-store location; no operation rereads process configuration. */
public record RagConfig(String provider,String model,String baseUrl,String apiKey,Path directory) {
    public static final RagConfig DEFAULT=new RagConfig("ollama","nomic-embed-text:latest","http://localhost:11434","",Path.of(".cafecli/rag"));
    public RagConfig(String provider,String model,String baseUrl,String apiKey) { this(provider,model,baseUrl,apiKey,DEFAULT.directory()); }
}
