package com.cafecli.harness.workspace;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserContent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.ToolResult;
import com.cafecli.policy.PolicyException;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Executes the leaf body, including its guard; no callback can bypass it. */
public final class LeafToolAdapter implements AgentTool {
    private final Tool leaf;

    public LeafToolAdapter(Tool leaf) {
        this.leaf = leaf;
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
        try {
            return map(leaf.executor().execute(input));
        } catch (PolicyException e) {
            return map(ToolOutput.failure(ToolOutput.Status.DENIED, "🛡️ 策略拒绝: " + e.getMessage()));
        } catch (Exception e) {
            return map(ToolOutput.failure(ToolOutput.Status.ERROR, "工具执行失败: " + e.getMessage()));
        }
    }

    private static ToolResult map(ToolOutput output) {
        if (output == null) output = ToolOutput.text("");
        List<UserContent> content = new ArrayList<>();
        content.add(new TextBlock(output.renderTextForLlm()));
        output.imageParts().forEach(image -> content.add(new ImageBlock(image.base64(), image.mimeType())));
        // Keep all leaf fields (including structured blocks and their metadata), not just their text projection.
        JsonNode details = new ObjectMapper().valueToTree(output);
        return new ToolResult(content, details, ToolResult.Status.valueOf(output.status().name()));
    }
}
