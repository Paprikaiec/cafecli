package com.cafecli.harness.browser;

import com.cafecli.browser.BrowserConnector;
import com.cafecli.browser.BrowserGuard;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.BrowserTools;

/** Browser mode tools and guarded Chrome execution. */
public final class BrowserCapability implements Capability {
    private final BrowserConnector connector;
    private final BrowserGuard guard;
    private java.util.function.BiFunction<com.cafecli.harness.AgentSession, String, String> command;

    public BrowserCapability(java.nio.file.Path sensitiveRules,
            java.util.function.Supplier<com.cafecli.mcp.McpServerManager> manager,
            com.cafecli.harness.permission.PermissionCapability permissions,
            com.cafecli.browser.BrowserConnectivityCheck connectivity) {
        this(new com.cafecli.browser.BrowserSession(), sensitiveRules, manager, permissions, connectivity);
    }
    public BrowserCapability(com.cafecli.browser.BrowserSession browser, java.nio.file.Path sensitiveRules,
            java.util.function.Supplier<com.cafecli.mcp.McpServerManager> manager,
            com.cafecli.harness.permission.PermissionCapability permissions,
            com.cafecli.browser.BrowserConnectivityCheck connectivity) {
        guard = new BrowserGuard(browser, new com.cafecli.browser.SensitivePagePolicy(sensitiveRules));
        Runnable clear = () -> permissions.clearApprovedForServer("chrome-devtools");
        connector = new BrowserConnector() {
            private String execute(String payload) {
                return com.cafecli.browser.BrowserCommands.handle(payload, browser, connectivity, manager.get(),
                        (name, arguments) -> { throw new IllegalStateException("command invocation context required"); }, clear);
            }
            public String status() { return execute("status"); }
            public String connectDefault() { return execute("connect"); }
            public String disconnect() { return execute("disconnect"); }
        };
        command = (context, arguments) -> com.cafecli.browser.BrowserCommands.handle(arguments, browser, connectivity,
                manager.get(), (name, input) -> {
                    try {
                        var result = context.invokeCommandTool(name, new com.fasterxml.jackson.databind.ObjectMapper().readTree(input));
                        return result.content().stream().filter(com.cafecli.ai.message.TextBlock.class::isInstance)
                                .map(com.cafecli.ai.message.TextBlock.class::cast).map(com.cafecli.ai.message.TextBlock::text)
                                .collect(java.util.stream.Collectors.joining("\n"));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
                }, clear);
    }
    public BrowserCapability(BrowserConnector connector, BrowserGuard guard) {
        this.connector = connector;
        this.guard = guard;
    }
    @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) {
        if (command != null) session.command(new com.cafecli.harness.SessionCommand("/browser",
                "Manage the browser session", command));
    }
    @Override public void attachAgent(AgentScope agent) {
        agent.approvalNotice(call -> {
            var check = guard.check(call.name(), call.arguments(), false);
            return check.requiresPerCallApproval() ? check.sensitiveNotice() : null;
        });
        agent.toolSnapshotStage(tools -> tools.stream().map(tool -> BrowserGuard.isChromeTool(tool.name())
                ? (com.cafecli.core.AgentTool) new GuardedBrowserTool(tool, guard) : tool).toList());
        new BrowserTools(() -> connector).tools().forEach(tool -> agent.tool(new LeafToolAdapter(tool)));
    }
}
