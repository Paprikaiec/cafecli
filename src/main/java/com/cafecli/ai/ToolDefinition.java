package com.cafecli.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A tool as the model is told about it. {@code parameters} is the JSON Schema of the arguments object; it is copied in
 * and out, so a definition is a snapshot no caller can mutate afterwards.
 */
public record ToolDefinition(String name, String description, JsonNode parameters) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
    }

    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }
}
