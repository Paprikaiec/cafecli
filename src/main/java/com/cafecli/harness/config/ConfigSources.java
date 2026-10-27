package com.cafecli.harness.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the {@link ConfigLoader} reads configuration from, passed in rather than read from the JVM, so the loader
 * has no hidden inputs: tests pass maps and temporary directories, and the real entry builds this once from the
 * process ({@code com.cafecli.app.SystemEntry}, the one v2 class that reads system properties, the environment,
 * {@code user.home} and {@code user.dir}).
 *
 * @param properties       the system properties (both {@code cafecli.*} and the legacy {@code paicli.*} names)
 * @param environment      the environment variables (both {@code CAFECLI_*} and the legacy {@code PAICLI_*} names)
 * @param userHome         the user's home directory; the user config home is under it. Absolute.
 * @param workingDirectory the directory Cafecli was started in: the project root, and what relative paths on the command
 *                         line and in properties are resolved against. Absolute, so nothing resolves against the
 *                         process's own working directory behind the caller's back.
 */
public record ConfigSources(Map<String, String> properties, Map<String, String> environment, Path userHome,
                            Path workingDirectory) {

    public ConfigSources {
        properties = Map.copyOf(properties);
        environment = Map.copyOf(environment);
        requireAbsolute(userHome, "userHome");
        requireAbsolute(workingDirectory, "workingDirectory");
    }

    private static void requireAbsolute(Path path, String name) {
        Objects.requireNonNull(path, name);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute, was " + path);
        }
    }
}
