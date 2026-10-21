package com.cafecli.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cafecli.ai.Model.Feature.*;

/** Bundled defaults from the existing clients; explicit model records override or extend the table per registry. */
public final class ModelRegistry {
    private static final String API = "openai-completions";
    private static final Map<String, String> DEFAULT_IDS = Map.of(
            "glm", "glm-5.1", "deepseek", "deepseek-v4-flash", "step", "step-3.5-flash",
            "kimi", "kimi-k2.6", "freellmapi", "auto");
    private static final List<Model> BUNDLED = List.of(
            cached("glm", "glm-5.1", 200_000),
            cached("deepseek", "deepseek-v4-flash", 1_000_000),
            cached("step", "step-3.5-flash", 256_000),
            cached("kimi", "kimi-k2.6", 256_000),
            new Model("auto", "freellmapi", API, 128_000, 8192, Set.of(REASONING, TOOL_CALL_STREAMING)));
    private final Map<Key, Model> models;

    public ModelRegistry() {
        this(List.of());
    }

    public ModelRegistry(List<Model> overrides) {
        Map<Key, Model> table = new HashMap<>();
        for (Model model : BUNDLED) table.put(new Key(model.provider(), model.id()), model);
        for (Model model : overrides) table.put(new Key(model.provider(), model.id()), model);
        models = Map.copyOf(table);
    }

    public Model defaultModel(String provider) {
        String id = DEFAULT_IDS.get(provider);
        if (id == null) throw new IllegalArgumentException("unknown provider: " + provider);
        return models.get(new Key(provider, id));
    }

    /** v1 permits an arbitrary model id and keeps that provider's limits when it has no explicit metadata. */
    public Model resolve(String provider, String id) {
        if (id == null || id.isBlank()) return defaultModel(provider);
        Model configured = models.get(new Key(provider, id));
        if (configured != null) return configured;
        Model defaults = defaultModel(provider);
        return new Model(id, provider, defaults.api(), defaults.contextWindow(), defaults.maxOutputTokens(), defaults.features());
    }

    private static Model cached(String provider, String id, int window) {
        return new Model(id, provider, API, window, 8192, Set.of(REASONING, PROMPT_CACHING, TOOL_CALL_STREAMING));
    }

    private record Key(String provider, String id) { }
}
