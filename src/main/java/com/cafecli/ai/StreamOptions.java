package com.cafecli.ai;

import java.util.Objects;

/** Per-request options for {@link Provider#stream}. */
public record StreamOptions(CancellationToken cancellation) {
    public StreamOptions {
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /** Options with a fresh token nobody else holds, i.e. a request that is never cancelled. */
    public static StreamOptions defaults() {
        return new StreamOptions(new CancellationToken());
    }
}
