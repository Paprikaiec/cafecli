package com.cafecli.harness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns explicit model/provider edits to the captured file tree; environment credentials remain fallback inputs. */
public final class ModelPreferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final ObjectNode document;
    private final java.util.Map<String,ProviderSettings> fallbacks;

    ModelPreferences(Path file, ObjectNode document,java.util.Map<String,ProviderSettings> fallbacks) {
        this.file = file;
        this.document = document.deepCopy();
        this.fallbacks=java.util.Map.copyOf(fallbacks);
    }

    public void explicitModel(String provider, String model) {
        object(object(document, "providers"), provider).put("model", model);
    }

    public String defaultProvider() { return document.path("defaultProvider").asText("glm"); }
    public ProviderSettings raw(String provider) {
        var node=document.path("providers").path(provider);
        return new ProviderSettings(node.path("apiKey").isTextual() ? node.path("apiKey").asText() : null,
                node.path("model").isTextual() ? node.path("model").asText() : null,
                node.path("baseUrl").isTextual() ? node.path("baseUrl").asText() : null);
    }
    public ProviderSettings effective(String provider) {
        var raw=raw(provider);
        var fallback=fallbacks.getOrDefault(provider,new ProviderSettings(null,null,null));
        return new ProviderSettings(nonblank(raw.apiKey(),fallback.apiKey()),nonblank(raw.model(),fallback.model()),
                nonblank(raw.baseUrl(),fallback.baseUrl()));
    }
    private static String nonblank(String value,String fallback) { return value == null || value.isBlank() ? fallback : value; }
    public void update(com.cafecli.config.ProviderConfigCommand.Update update) {
        var node=object(object(document,"providers"),update.provider());
        if (update.apiKey()!=null) node.put("apiKey",update.apiKey());
        if (update.model()!=null) node.put("model",update.model());
        if (update.baseUrl()!=null) node.put("baseUrl",update.baseUrl());
        if (update.setDefault()) document.put("defaultProvider",update.provider());
    }
    public void saveCurrent() throws IOException {
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),document);
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        if (parent.get(field) instanceof ObjectNode value) return value;
        return parent.putObject(field);
    }

    public void save(String provider) throws IOException {
        document.put("defaultProvider", provider);
        saveCurrent();
    }
}
