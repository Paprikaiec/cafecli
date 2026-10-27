package com.cafecli.mcp;

import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolOutput;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Discovery observations for MCP leaf lifecycle tests. */
public final class RecordingMcpToolSink implements McpToolSink {
    private final AuditLog audit;
    private final Set<String> tools = ConcurrentHashMap.newKeySet();
    public RecordingMcpToolSink(Path project) { audit = new AuditLog(project.resolve("audit")); }
    @Override public AuditLog getAuditLog() { return audit; }
    public boolean hasTool(String name) { return tools.contains(name); }
    @Override public synchronized void replaceMcpToolOutputsForServer(String server, List<McpToolDescriptor> descriptors,
            Function<McpToolDescriptor, Function<String, ToolOutput>> invokers) {
        tools.removeIf(name -> name.startsWith("mcp__" + server + "__"));
        descriptors.forEach(descriptor -> tools.add(descriptor.namespacedName()));
    }
    @Override public void unregisterMcpTool(String name) { tools.remove(name); }
}
