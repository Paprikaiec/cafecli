package com.cafecli.harness.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit long-term storage location and the project scope key. */
public record MemoryConfig(Path directory, String projectKey) {
    public static MemoryConfig forProject(Path directory, Path project) {
        Path normalized = project.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized)) normalized = normalized.toRealPath();
        } catch (IOException ignored) {
            // v1 retains the normalized absolute path when canonicalization fails.
        }
        return new MemoryConfig(directory, normalized.toString());
    }

    public MemoryConfig {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(projectKey, "projectKey");
    }
}
