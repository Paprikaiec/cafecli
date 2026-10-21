package com.cafecli.core.log;

import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.ToolResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.Objects;

/** The model's result message together with its typed outcome and structured details. */
public record ToolResultEntry(ToolResultMessage message, ToolResult.Status status,
                              @JsonDeserialize(using = DetailsDeserializer.class) JsonNode details) implements LogEntry {
    private static final ObjectMapper DETAILS = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    public ToolResultEntry {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(status, "status");
        // JSON has numeric values, not Java's IntNode/LongNode distinction. Canonicalize at entry creation so every
        // detail tree round-trips, without rounding exact decimal values.
        if (details == null || details.isNull()) {
            details = null;
        } else {
            try {
                details = DETAILS.readTree(DETAILS.writeValueAsBytes(details));
            } catch (IOException e) {
                throw new IllegalArgumentException("tool details cannot be represented as JSON", e);
            }
        }
    }

    /** Exact numeric decoding belongs only to tool details; custom entry kinds retain Jackson's own defaults. */
    public static final class DetailsDeserializer extends JsonDeserializer<JsonNode> {
        @Override
        public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return DETAILS.readTree(parser);
        }
    }

    public ToolResultEntry(ToolResultMessage message) {
        this(message, message.isError() ? ToolResult.Status.ERROR : ToolResult.Status.OK, null);
    }

    @Override
    public JsonNode details() {
        return details == null || details.isNull() ? null : details.deepCopy();
    }
}
