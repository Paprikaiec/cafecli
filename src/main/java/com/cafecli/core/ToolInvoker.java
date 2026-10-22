package com.cafecli.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Invocation-local access to the immutable executable tool snapshot. Auxiliary calls never enter the model log. */
public interface ToolInvoker {
    Optional<AgentTool> find(String name);
    ToolResult invoke(String name, JsonNode input);
}
