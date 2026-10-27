package com.cafecli.harness.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A config file that exists but could not be read, so the defaults were used instead (v1 does the same, and says so on
 * stderr). Data only: the entry decides what to tell the user.
 *
 * @param file   the file that could not be read
 * @param detail why, as the parser reported it
 */
public record ConfigWarning(Path file, String detail) {
    public ConfigWarning {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(detail, "detail");
    }
}
