package com.cafecli.ai.openai;

/** HTTP timeout seconds, loaded explicitly rather than read by the adapter from JVM globals. */
public record HttpTimeouts(long connect, long read, long write, long call) {
    public static final HttpTimeouts DEFAULT = new HttpTimeouts(60, 300, 60, 600);

    public HttpTimeouts {
        if (connect <= 0 || read <= 0 || write <= 0 || call <= 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
    }
}
