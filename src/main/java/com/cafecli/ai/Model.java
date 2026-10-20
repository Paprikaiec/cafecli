package com.cafecli.ai;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A model as data: passed with every request to a stateless {@link Provider}, so switching models is changing a value.
 *
 * @param id              the model id sent to the provider (e.g. {@code deepseek-chat})
 * @param provider        the provider name (e.g. {@code deepseek})
 * @param api             the API family: which wire format / adapter speaks to it (e.g. {@code openai-completions})
 * @param contextWindow   the context window in tokens
 * @param maxOutputTokens the maximum output length in tokens
 * @param features        what the model supports
 */
public record Model(String id, String provider, String api, int contextWindow, int maxOutputTokens,
                    Set<Feature> features) {

    public enum Feature {
        REASONING,
        IMAGE_INPUT,
        PROMPT_CACHING,
        TOOL_CALL_STREAMING
    }

    public Model {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(api, "api");
        features = Collections.unmodifiableSet(enumSetOf(features));
    }

    public boolean supports(Feature feature) {
        return features.contains(feature);
    }

    /** Ordered by declaration, so the record serializes the same way on every run. */
    private static EnumSet<Feature> enumSetOf(Collection<Feature> features) {
        EnumSet<Feature> set = EnumSet.noneOf(Feature.class);
        if (features != null) {
            set.addAll(features);
        }
        return set;
    }
}
