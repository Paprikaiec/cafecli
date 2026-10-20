package com.cafecli.image;

/**
 * One image attachment carried as base64 — what tools (MCP screenshots, …) and {@code @image:} / {@code @clipboard}
 * references in user input produce. Leaves only know this type; whoever builds the LLM request converts it.
 *
 * <p>A null or blank {@code mimeType} becomes {@code image/png} on construction, so every reader sees the normalized
 * value.
 */
public record ImagePart(String base64, String mimeType) {
    public ImagePart {
        mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
    }
}
