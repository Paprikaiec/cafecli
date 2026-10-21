package com.cafecli.harness.workspace;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserContent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.harness.ToolExecution;
import com.cafecli.policy.PolicyException;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.cafecli.tool.WorkspaceTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Executes the leaf body, including its guard; no callback can bypass it. */
public final class LeafToolAdapter implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Tool leaf;
    private final WorkspaceTools workspace;

    public LeafToolAdapter(Tool leaf) {
        this(leaf, null);
    }

    public LeafToolAdapter(Tool leaf, WorkspaceTools workspace) {
        this.leaf = leaf;
        this.workspace = workspace;
    }

    @Override public String name() { return leaf.name(); }
    @Override public String description() { return leaf.description(); }
    @Override public JsonNode parameters() { return leaf.parameters().deepCopy(); }

    @Override
    public ToolResult execute(String callId, JsonNode input, CancellationToken cancellation,
                              Consumer<ToolResult> progress) {
        if (cancellation.isCancelled()) {
            return map(ToolOutput.failure(ToolOutput.Status.CANCELLED, "用户取消了此次工具调用"));
        }
        long start = System.nanoTime();
        try {
            ObjectNode changes = JSON.createObjectNode();
            ToolResult result = map(workspace == null ? leaf.executor().execute(input)
                    : workspace.execute(name(), input, cancellation::isCancelled,
                            (command, line) -> {
                                ObjectNode update = JSON.createObjectNode();
                                update.put("command", command);
                                update.put("line", line);
                                progress.accept(new ToolResult(List.of(new TextBlock(line)), update, ToolResult.Status.OK));
                            }, (path, change) -> {
                                ObjectNode diff = changes.putObject("diff");
                                diff.put("path", path);
                                diff.put("before", change[0]);
                                diff.put("after", change[1]);
                            }));
            ObjectNode details = (ObjectNode) result.details();
            details.setAll(changes);
            return ToolExecution.record(new ToolResult(result.content(), details, result.status()),
                    ToolExecution.Kind.RETURNED, null, start);
        } catch (PolicyException e) {
            return ToolExecution.record(map(ToolOutput.failure(ToolOutput.Status.DENIED, "🛡️ 策略拒绝: " + e.getMessage())),
                    ToolExecution.Kind.POLICY_DENIED, e.getMessage(), start);
        } catch (Exception e) {
            return ToolExecution.record(map(ToolOutput.failure(ToolOutput.Status.ERROR, "工具执行失败: " + e.getMessage())),
                    ToolExecution.Kind.FAILED, e.getMessage(), start);
        }
    }

    private static ToolResult map(ToolOutput output) {
        if (output == null) output = ToolOutput.text("");
        List<UserContent> content = new ArrayList<>();
        content.add(new TextBlock(output.renderTextForLlm()));
        output.imageParts().forEach(image -> content.add(new ImageBlock(image.base64(), image.mimeType())));
        // Keep all leaf fields (including structured blocks and their metadata), not just their text projection.
        JsonNode details = JSON.valueToTree(output);
        return new ToolResult(content, details, ToolResult.Status.valueOf(output.status().name()));
    }
}
