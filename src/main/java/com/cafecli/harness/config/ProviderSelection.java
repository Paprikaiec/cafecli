package com.cafecli.harness.config;

import com.cafecli.ai.Model;
import com.cafecli.ai.openai.HttpTimeouts;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider configuration loaded once. Keys and configured wire options follow v1's source precedence; a base URL is
 * retained even for providers whose v1 adapter ignores it (DeepSeek). Model metadata is data owned by ai.
 */
public record ProviderSelection(String name, Optional<Path> fakeScript, String apiKey, String model, String baseUrl,
                                List<Model> modelOverrides, HttpTimeouts timeouts,
                                java.util.Map<String, ProviderSettings> providers, Optional<ModelPreferences> preferences, String startupRoute) {
    public static final String FAKE = "fake";

    public ProviderSelection(String name, Optional<Path> fakeScript, String apiKey, String model, String baseUrl,
                             List<Model> modelOverrides, HttpTimeouts timeouts) {
        this(name, fakeScript, apiKey, model, baseUrl, modelOverrides, timeouts, java.util.Map.of(), Optional.empty(), name);
    }

    public ProviderSelection(String name, Optional<Path> fakeScript) {
        this(name, fakeScript, null, null, null, List.of(), HttpTimeouts.DEFAULT);
    }

    public ProviderSelection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startupRoute, "startupRoute");
        Objects.requireNonNull(fakeScript, "fakeScript");
        modelOverrides = List.copyOf(modelOverrides);
        providers = java.util.Map.copyOf(providers);
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(timeouts, "timeouts");
    }

    public boolean isFake() {
        return FAKE.equals(name);
    }

    /** Configuration is often included in diagnostics: never print the credential. */
    @Override
    public String toString() {
        return "ProviderSelection[name=" + name + ", fakeScript=" + fakeScript + ", model=" + model + "]";
    }
}
