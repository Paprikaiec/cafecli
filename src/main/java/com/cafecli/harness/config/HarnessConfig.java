package com.cafecli.harness.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * v2's configuration, typed and loaded once ({@link ConfigLoader}) at the composition root, which hands each part its
 * own slice. Holds only what has a reader so far; later tickets grow it next to the sources v1 reads for them.
 *
 * @param provider      which provider to talk to
 * @param maxIterations the hard iteration cap of a run (v1: {@code react.hard.max.iterations}, default 50)
 * @param projectRoot   the project the session works on: the working directory
 * @param homes         the user and project config homes
 * @param warnings      config files that could not be read, so their defaults were used
 */
public record HarnessConfig(ProviderSelection provider, int maxIterations, Path projectRoot, ConfigHomes homes,
                            List<ConfigWarning> warnings) {
    public HarnessConfig {
        Objects.requireNonNull(provider, "provider");
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive, was " + maxIterations);
        }
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(homes, "homes");
        warnings = List.copyOf(warnings);
    }
}
