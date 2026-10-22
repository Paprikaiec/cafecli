package com.cafecli.harness.context;

import com.cafecli.core.log.CustomEntry;
import java.util.Objects;

/** An append-only summary of a half-open range in the source log. */
public record Summary(int fromInclusive, int toExclusive, String text) implements CustomEntry {
    public static final MessageKind<Summary> KIND = MessageKind.tagged("summary", Summary.class,
            "[已压缩的历史对话摘要]", Summary::text);
    public Summary {
        if (fromInclusive < 0 || toExclusive <= fromInclusive) throw new IllegalArgumentException("invalid summary range");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("summary must not be blank");
    }
}
