package com.cafecli.ai.message;

import java.util.Objects;

/** An image as base64 data plus its mime type (e.g. {@code image/png}). */
public record ImageBlock(String base64, String mimeType) implements UserContent {
    public ImageBlock {
        Objects.requireNonNull(base64, "base64");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
