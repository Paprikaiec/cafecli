package com.cafecli.harness.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Which provider the session talks to: the configuration slice the composition root builds the {@code ai} provider
 * from. Ticket 06 grows it with what a real provider needs (API key, base URL, model).
 *
 * @param name       the provider's name as configured, trimmed and lower-cased ({@code glm}, {@code deepseek}, …, or
 *                   {@link #FAKE}); not normalized further
 * @param fakeScript the fake provider's script file, absolute, when one is configured
 */
public record ProviderSelection(String name, Optional<Path> fakeScript) {

    /** The fake provider ({@code ai.fake}): answers from a script file, so v2 runs without an API key. */
    public static final String FAKE = "fake";

    public ProviderSelection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fakeScript, "fakeScript");
    }

    public boolean isFake() {
        return FAKE.equals(name);
    }
}
