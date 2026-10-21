package com.cafecli.harness.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The two config homes, resolved the way v1 resolves them ({@code ConfigHome.configDirUnder}): {@code .cafecli}, or
 * the legacy {@code .paicli} when only that one exists.
 *
 * @param user    under the user's home: {@code config.json}, prompt overrides, and later hooks, MCP, memory, …
 * @param project under the project root: project prompt overrides, and later project hooks, MCP, skills, …
 */
public record ConfigHomes(Path user, Path project) {
    public ConfigHomes {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(project, "project");
    }
}
