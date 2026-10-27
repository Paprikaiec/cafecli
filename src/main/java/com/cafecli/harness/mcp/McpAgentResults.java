package com.cafecli.harness.mcp;

import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.ToolExecution;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.mcp.McpResultPersister;
import com.cafecli.tool.McpResults;
import com.cafecli.tool.ToolExecutionResult;
import com.cafecli.tool.ToolOutput;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All result-budget state belongs to this agent, never to a connection or a current-call slot. */
final class McpAgentResults implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path directory;
    private final McpResults results;
    private boolean used;
    private boolean closed;

    McpAgentResults(Path project, String session, String agent) {
        McpResultPersister persister = new McpResultPersister(project, session, agent);
        directory = McpResultPersister.sessionDirectory(project, session).resolve(agent);
        results = new McpResults(() -> persister);
    }
    synchronized ToolOutput reference(String call, String tool, ToolOutput output) {
        if (closed) return ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用");
        used = true;
        return results.reference(call, tool, output);
    }

    List<ToolResultEntry> batch(List<ToolResultEntry> entries) {
        var leaves = new ArrayList<ToolExecutionResult>();
        for (var entry : entries) {
            var details = entry.details();
            ToolOutput output = null;
            if (McpToolDescriptor.isNamespaced(entry.message().toolName())
                    && details instanceof ObjectNode object && ToolExecution.from(new ToolResult(
                    entry.message().content(), details, entry.status())) != null) {
                var leaf = object.deepCopy(); leaf.remove("execution");
                output = JSON.convertValue(leaf, ToolOutput.class);
            }
            leaves.add(new ToolExecutionResult(entry.message().toolCallId(), entry.message().toolName(), "{}", output, 0, false));
        }
        var normalized = results.batch(leaves);
        var published = new ArrayList<ToolResultEntry>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (normalized.get(i) == leaves.get(i)) { published.add(entry); continue; }
            ToolResult mapped = LeafToolAdapter.map(normalized.get(i).output());
            ObjectNode details = (ObjectNode) entry.details();
            details.setAll((ObjectNode) mapped.details());
            published.add(new ToolResultEntry(new ToolResultMessage(entry.message().toolCallId(), entry.message().toolName(),
                    mapped.content(), entry.message().isError()), entry.status(), details));
        }
        return List.copyOf(published);
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        // A freshly assembled replacement has not acquired this canonical scope yet. Its failed construction must
        // not remove the still-live old agent's files. Ownership starts only with actual execution after installation.
        if (used) deleteDirectory(directory);
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
