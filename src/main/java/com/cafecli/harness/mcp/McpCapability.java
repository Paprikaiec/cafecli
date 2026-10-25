package com.cafecli.harness.mcp;

import com.cafecli.ai.CancellationToken;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionCommand;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.Submission;
import com.cafecli.harness.ToolExecution;
import com.cafecli.harness.config.McpConfig;
import com.cafecli.harness.permission.AuditCapability;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.mcp.McpClient;
import com.cafecli.mcp.McpResultPersister;
import com.cafecli.mcp.McpServerManager;
import com.cafecli.mcp.McpToolSink;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.mcp.mention.AtMentionExpander;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolOutput;
import com.cafecli.prompt.PromptContext;
import com.cafecli.policy.PolicyException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Consumer;

/** Session connections and discovery; each attached agent receives its own execution adapters and result scope. */
public final class McpCapability implements Capability {
    private final McpConfig config;
    private final AuditCapability audit;
    private final Map<String, List<Discovered>> servers = new TreeMap<>();
    private final List<Runnable> invalidators = new CopyOnWriteArrayList<>();
    private McpServerManager manager;
    private String sessionId;
    private String resourceIndex = "";
    private record Discovered(McpToolDescriptor descriptor, Function<String, ToolOutput> invoke) { }

    public McpCapability(McpConfig config, AuditCapability audit) {
        this.config = Objects.requireNonNull(config);
        this.audit = Objects.requireNonNull(audit);
    }

    @Override public void attachSession(SessionRegistrar session) {
        sessionId = session.sessionId();
        manager = new McpServerManager(new Sink(), config.projectRoot(), config.loader());
        session.onClose(() -> McpAgentResults.deleteDirectory(McpResultPersister.sessionDirectory(config.projectRoot(), sessionId)));
        session.onClose(manager);
        try { manager.loadConfiguredServers(); manager.startAll(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        session.command(new SessionCommand("/mcp", "管理 MCP servers", (context, arguments) -> command(arguments))
                .withCompleter(new com.cafecli.input.CommandCompleter(manager::resourceCandidates,List::of,
                        () -> com.cafecli.input.CommandHints.forCommand("/mcp","管理 MCP servers"))::suggest));
        session.completion(query -> {
            if (query.word() == null || !query.word().startsWith("@") || query.word().startsWith("@image:")) return List.of();
            // The shared leaf already suppresses image/local forms before resource mention completion.
            return new com.cafecli.input.CommandCompleter(manager::resourceCandidates,List::of,List::of,config.projectRoot()).suggest(query)
                    .stream().filter(item -> "MCP resources".equals(item.group()) || item.value().contains(":"))
                    .toList();
        });
        var mentions = new AtMentionExpander(manager);
        session.onSubmit((input, token) -> Submission.proceed(mentions.expand(input)));
    }
    /** Session-owned manager for explicitly composed leaf adapters, after session attachment. */
    public McpServerManager serverManager() {
        return Objects.requireNonNull(manager, "session hooks must run first");
    }
    private String command(String arguments) {
        String trimmed = arguments.trim();
        if (trimmed.isEmpty()) return manager.formatStatus();
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length != 2 || parts[1].isBlank()) return "用法: /mcp [resources|prompts|restart|logs|disable|enable] <name>";
        String name = parts[1].trim();
        return switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "resources" -> manager.resources(name);
            case "prompts" -> manager.prompts(name);
            case "restart" -> manager.restart(name);
            case "logs" -> manager.logs(name);
            case "disable" -> manager.disable(name);
            case "enable" -> manager.enable(name);
            default -> "未知 MCP 命令: " + parts[0];
        };
    }
    @Override public void attachAgent(AgentScope agent) {
        var invalidate = agent.systemPromptInvalidator();
        invalidators.add(invalidate);
        agent.onClose(() -> invalidators.remove(invalidate));
        var results = new McpAgentResults(config.projectRoot(), sessionId, agent.agentId());
        agent.onClose(results);
        agent.toolSource(() -> discovered().stream().map(tool -> adapt(tool, results)).toList());
        agent.toolBatchResults(results::batch);
        agent.promptContext(context -> new PromptContext(context.approvalMode(), context.memoryContext(),
                String.join("\n\n", context.externalContext(), promptIndex()).trim(),
                context.skillIndex(), context.variables()));
    }
    private String promptIndex() {
        String index = manager.refreshedResourceIndexForPrompt();
        synchronized (this) { resourceIndex = index; }
        return index;
    }
    private AgentTool adapt(Discovered tool, McpAgentResults results) {
        var d = tool.descriptor();
        return new AgentTool() {
            @Override public String name() { return d.namespacedName(); }
            @Override public String description() {
                String base = d.description() == null || d.description().isBlank()
                        ? "MCP server 提供的外部工具" : d.description();
                return base + " (MCP server: " + d.serverName() + ", tool: " + d.name() + ")";
            }
            @Override public JsonNode parameters() { return d.inputSchema().deepCopy(); }
            @Override public ToolResult execute(String callId, JsonNode input,
                    CancellationToken token, Consumer<ToolResult> progress) {
                if (token.isCancelled()) return LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用"));
                long start = System.nanoTime();
                try {
                    var returned = tool.invoke().apply(input.toString());
                    if (token.isCancelled()) return LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用"));
                    var output = results.reference(callId, name(), returned);
                    return ToolExecution.record(LeafToolAdapter.map(output), ToolExecution.Kind.RETURNED, null, start);
                } catch (PolicyException e) {
                    return ToolExecution.record(LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.DENIED, "🛡️ 策略拒绝: " + e.getMessage())),
                            ToolExecution.Kind.POLICY_DENIED, e.getMessage(), start);
                } catch (Exception e) {
                    return ToolExecution.record(LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.ERROR, "MCP 工具调用失败: " + e.getMessage())),
                            ToolExecution.Kind.FAILED, e.getMessage(), start);
                }
            }
        };
    }
    private synchronized List<Discovered> discovered() {
        return servers.values().stream().flatMap(List::stream).toList();
    }
    private void changed() { invalidators.forEach(Runnable::run); }
    private final class Sink implements McpToolSink {
        @Override public AuditLog getAuditLog() { throw new UnsupportedOperationException("use queued observations"); }
        @Override public void resourceRead(String tool, String args, String reason, long durationMs) {
            audit.observeResourceRead(tool, args, reason, durationMs);
        }
        @Override public void resourcesChanged() { changed(); }
        @Override public ToolOutput invokeTool(McpClient client, McpToolDescriptor descriptor, String arguments) {
            try { return client.callToolOutput(descriptor.name(), arguments); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        @Override public void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> descriptors,
                Function<McpToolDescriptor, Function<String, ToolOutput>> factory) {
            boolean changed;
            synchronized (McpCapability.this) {
                var replacement = descriptors.stream().map(d -> new Discovered(d, factory.apply(d))).toList();
                var previous = servers.put(serverName, replacement);
                String index = manager.resourceIndexForPrompt();
                changed = previous == null || !previous.stream().map(Discovered::descriptor).toList().equals(descriptors)
                        || !resourceIndex.equals(index);
                resourceIndex = index;
            }
            if (changed) changed();
        }
        @Override public void unregisterMcpTool(String name) {
            boolean removed = false;
            synchronized (McpCapability.this) {
                for (var entry : servers.entrySet()) {
                    var previous = entry.getValue();
                    var remaining = previous.stream().filter(d -> !d.descriptor().namespacedName().equals(name)).toList();
                    removed |= previous.size() != remaining.size(); entry.setValue(remaining);
                }
            }
            if (removed) changed();
        }
    }
}
