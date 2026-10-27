package com.cafecli.harness.browser;

import com.cafecli.ai.CancellationToken;
import com.cafecli.browser.BrowserGuard;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolInvoker;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.ToolExecution;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;

/** The absolute browser refusal lives in execution, after every hook and approval route. */
final class GuardedBrowserTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentTool delegate;
    private final BrowserGuard guard;
    GuardedBrowserTool(AgentTool delegate, BrowserGuard guard) { this.delegate = delegate; this.guard = guard; }
    public String name() { return delegate.name(); }
    public String description() { return delegate.description(); }
    public JsonNode parameters() { return delegate.parameters(); }
    public ToolResult execute(String id, JsonNode input, CancellationToken token, Consumer<ToolResult> progress) throws Exception {
        return execute(id, input, token, progress, null);
    }
    @Override public ToolResult execute(String id, JsonNode input, CancellationToken token,
                                       Consumer<ToolResult> progress, ToolInvoker invoker) throws Exception {
        if (token.isCancelled()) return LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用"));
        long start = System.nanoTime();
        var check = guard.check(name(), input.toString(), false);
        ToolResult result;
        if (check.blocked()) {
            result = ToolExecution.record(LeafToolAdapter.map(ToolOutput.failure(ToolOutput.Status.DENIED,
                    "🛡️ 策略拒绝: " + check.reason())), ToolExecution.Kind.POLICY_DENIED, check.reason(), start);
        } else {
            result = invoker == null ? delegate.execute(id, input, token, progress)
                    : delegate.execute(id, input, token, progress, invoker);
            var execution = ToolExecution.from(result);
            if (execution != null && execution.kind() == ToolExecution.Kind.RETURNED) {
                guard.applyAfterExecution(name(), input.toString(), result.details().path("text").asText(""));
            }
        }
        ObjectNode details = result.details() instanceof ObjectNode object ? object.deepCopy() : JSON.createObjectNode();
        details.set("browser", JSON.valueToTree(check.metadata()));
        return new ToolResult(result.content(), details, result.status());
    }
}
