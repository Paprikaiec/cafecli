package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}
