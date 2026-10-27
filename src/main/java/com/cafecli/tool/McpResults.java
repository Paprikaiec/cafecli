package com.cafecli.tool;

import com.cafecli.mcp.McpResultPersister;
import java.util.List;
import java.util.function.Supplier;

/** Existing MCP per-result and aggregate budgets, reusable without constructing a v1 registry. */
public final class McpResults {
    private final McpResultBudget budget;
    public McpResults(Supplier<McpResultPersister> persister) { budget = new McpResultBudget(persister); }
    public ToolOutput reference(String callId, String tool, ToolOutput output) {
        return budget.referenceLargeMcpResults(new ToolInvocation(callId, tool, "{}"), output);
    }
    public List<ToolExecutionResult> batch(List<ToolExecutionResult> results) { return budget.applyMcpBatchBudget(results); }
}
