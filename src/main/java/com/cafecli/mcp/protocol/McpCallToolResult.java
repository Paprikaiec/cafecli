package com.cafecli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.cafecli.llm.LlmClient;
import com.cafecli.tool.ToolOutput;
import com.cafecli.image.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallToolResult(List<McpContent> content, JsonNode structuredContent, boolean isError) {
    public McpCallToolResult(List<McpContent> content, boolean isError) {
        this(content, null, isError);
    }

    public String formatForLlm() {
        return toToolOutput().renderTextForLlm();
    }

    public ToolOutput toToolOutput() {
        if (content == null || content.isEmpty()) {
            if (structuredContent == null || structuredContent.isNull() || structuredContent.isMissingNode()) {
                return ToolOutput.text(isError ? "MCP 工具返回错误，但没有错误正文" : "");
            }
        }
        List<LlmClient.ContentPart> imageParts = new ArrayList<>();
        List<ToolOutput.Block> blocks = new ArrayList<>();
        if (content != null) {
            for (McpContent item : content) {
                String type = item.type() == null || item.type().isBlank() ? "text" : item.type();
                if ("text".equals(type)) {
                    blocks.add(ToolOutput.Block.text(item.text() == null ? "" : item.text()));
                } else if ("image".equals(type)) {
                    blocks.add(ToolOutput.Block.text(formatImage(item, imageParts)));
                } else {
                    blocks.add(ToolOutput.Block.text("[此工具返回了 " + type + "，请向用户描述结果]"));
                }
            }
        }
        if (structuredContent != null && !structuredContent.isNull() && !structuredContent.isMissingNode()) {
            blocks.add(ToolOutput.Block.structuredJson(structuredContent));
        }
        return ToolOutput.ofBlocks(blocks, imageParts);
    }

    private static String formatImage(McpContent item, List<LlmClient.ContentPart> imageParts) {
        String mimeType = item.mimeType() == null || item.mimeType().isBlank()
                ? "image/png"
                : item.mimeType();
        int base64Length = item.data() == null ? 0 : item.data().length();
        boolean hasData = item.data() != null && !item.data().isBlank();
        ImageProcessor.ProcessedImage processed = null;
        String error = null;

        if (hasData) {
            try {
                processed = ImageProcessor.fromBase64(item.data(), mimeType);
                imageParts.add(ImageProcessor.toContentPart(processed));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        StringBuilder fallback = new StringBuilder();
        fallback.append("[此工具返回了 image: mimeType=").append(mimeType)
                .append(", base64Length=").append(base64Length);
        if (!hasData) {
            fallback.append("，图片数据为空，未作为图片附件附加。]");
        } else if (processed == null) {
            fallback.append("，图片处理失败: ").append(error)
                    .append("，未作为图片附件附加；请缩小视口或改用 take_snapshot 获取 DOM 文本快照。]");
        } else {
            String metadataText = ImageProcessor.createMetadataText(processed);
            if (metadataText != null) {
                fallback.append(", ").append(metadataText);
            }
            fallback.append("。Cafecli 会在下一轮把图片作为图片附件附加；"
                    + "如果模型无法稳定识别该图片，请优先调用 take_snapshot 获取 DOM 文本快照。]");
        }
        return fallback.toString();
    }
}
