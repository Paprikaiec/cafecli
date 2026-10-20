package com.cafecli.mcp;

import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolOutput;

import java.util.List;
import java.util.function.Function;

/**
 * Where {@link McpServerManager} puts the tools it discovers: replace or remove one server's MCP tools, and the audit
 * log it records resource reads in. v1's tool registry implements it.
 */
public interface McpToolSink {
    AuditLog getAuditLog();

    void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                        Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory);

    void unregisterMcpTool(String toolName);
}
