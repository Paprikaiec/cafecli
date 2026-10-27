package com.cafecli.harness.config;

import java.time.Duration;

/** Typed workspace settings; v1's command timeout is a constructor input, defaulting to sixty seconds. */
public record WorkspaceConfig(boolean editFileDisabled, long commandTimeoutSeconds) {
    public WorkspaceConfig {
        if (commandTimeoutSeconds <= 0 || commandTimeoutSeconds > Long.MAX_VALUE / 1_000_000_000L - 5) {
            throw new IllegalArgumentException("commandTimeoutSeconds must be positive and fit a duration");
        }
    }

    public static WorkspaceConfig defaults() {
        return new WorkspaceConfig(false, 60);
    }

    public Duration toolBatchTimeout() {
        return Duration.ofSeconds(Math.max(commandTimeoutSeconds + 5, 90));
    }
}
