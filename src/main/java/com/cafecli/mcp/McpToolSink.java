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

    /** Resource observations may be queued by a harness; v1 retains its direct audit path. */
    default void resourceRead(String tool, String args, String failure, long durationMs) {
        getAuditLog().record(failure == null ? AuditLog.AuditEntry.allowByMention(tool, args, durationMs)
                : AuditLog.AuditEntry.error(tool, args, failure, durationMs));
    }

    /** Resource discovery notifications; implementations only request a prompt refresh. */
    default void resourcesChanged() { }

    /** v1 keeps its historical exception presentation; a harness can preserve typed failure provenance. */
    default ToolOutput invokeTool(McpClient client, McpToolDescriptor descriptor, String arguments) {
        try { return client.callToolOutput(descriptor.name(), arguments); }
        catch (Exception e) {
            return ToolOutput.text("MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): " + e.getMessage());
        }
    }

    void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                        Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory);

    void unregisterMcpTool(String toolName);
}
