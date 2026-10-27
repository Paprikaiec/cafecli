package com.cafecli.core;

import com.cafecli.ai.message.UserContent;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/** Content shown to the model, plus lossless details and an outcome independent of presentation. */
public record ToolResult(List<UserContent> content, JsonNode details, Status status) {
    public enum Status { OK, ERROR, DENIED, BLOCKED, CANCELLED, TIMEOUT }

    public ToolResult {
        content = List.copyOf(content);
        details = details == null || details.isNull() ? null : details.deepCopy();
        Objects.requireNonNull(status, "status");
    }

    @Override
    public JsonNode details() {
        return details == null || details.isNull() ? null : details.deepCopy();
    }
}
