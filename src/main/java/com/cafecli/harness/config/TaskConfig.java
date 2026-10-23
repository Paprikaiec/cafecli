package com.cafecli.harness.config;

/** Captured task policies; synchronous headless sessions never detach work. */
public record TaskConfig(boolean interactive, long autoBackgroundMillis, int maxConcurrency, int maxDepth,
                         int maxIdle, int messageMirrorCap) {
    public static final TaskConfig DEFAULT = new TaskConfig(false, 120000, 8, 5, 8, 50);
    public TaskConfig {
        if (maxConcurrency < 1 || maxDepth < 1 || maxIdle < 1 || messageMirrorCap < 1)
            throw new IllegalArgumentException("task limits must be positive");
    }
}
