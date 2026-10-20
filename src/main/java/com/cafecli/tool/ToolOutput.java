package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.cafecli.image.ImagePart;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 一次工具调用的结果：回灌模型的文本 / 图片 / 块，外加类型化的结果状态 {@link Status}。
 *
 * <p>{@code status} 在<b>产生结果的地方</b>设置（取消 / 未知工具 / 策略拒绝 / 异常 / Hook 拦截 / HITL / 批次超时 /
 * MCP {@code isError} / 内置工具体的业务级失败）。消费方判断成败读 {@code status}；文本前缀（「工具执行失败」「🛡️」「[HITL]」…）
 * 只是呈现，不是判据。
 */
public record ToolOutput(String text, List<ImagePart> imageParts, List<Block> blocks, Status status) {
    /** 工具调用的结果状态。 */
    public enum Status {
        /**
         * 工具执行了、且没有报告失败。内置工具的业务级失败（文件不存在、编辑未命中、参数为空…）不在此列，
         * 它们带 ERROR 等状态；{@code execute_command} 跑完即 OK，非零退出码只在文本里。
         */
        OK,
        /** 执行异常、未知工具、MCP {@code isError}，或内置工具报告的业务级失败。 */
        ERROR,
        /** 被策略守卫拒绝（含 {@code web_fetch} 的网络策略 / 限流），或 HITL 拒绝 / 跳过；工具没有执行。 */
        DENIED,
        /** 被 PreToolUse Hook 拦截；工具没有执行。这是模型反馈级阻断，不是安全拒绝。 */
        BLOCKED,
        /** 用户取消或中断。 */
        CANCELLED,
        /** 批次超时，或 {@code execute_command} 的命令超时。 */
        TIMEOUT
    }

    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        blocks = blocks == null ? defaultBlocks(text, imageParts) : List.copyOf(blocks);
        status = status == null ? Status.OK : status;
    }

    public ToolOutput(String text, List<ImagePart> imageParts, List<Block> blocks) {
        this(text, imageParts, blocks, Status.OK);
    }

    public ToolOutput(String text, List<ImagePart> imageParts) {
        this(text, imageParts, defaultBlocks(text, imageParts));
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), List.of(Block.text(text)));
    }

    /** 带非 OK 状态的纯文本结果：{@code text(text).withStatus(status)}。 */
    public static ToolOutput failure(Status status, String text) {
        return text(text).withStatus(status);
    }

    /** 同文本 / 图片 / 块、换一个状态的副本。 */
    public ToolOutput withStatus(Status status) {
        return new ToolOutput(text, imageParts, blocks, status);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public static ToolOutput ofBlocks(List<Block> blocks, List<ImagePart> imageParts) {
        List<Block> safeBlocks = blocks == null ? List.of() : List.copyOf(blocks);
        List<ImagePart> safeImages = imageParts == null ? List.of() : List.copyOf(imageParts);
        return new ToolOutput(renderBlocks(safeBlocks), safeImages, safeBlocks);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }

    public String renderTextForLlm() {
        return renderBlocks(blocks);
    }

    private static List<Block> defaultBlocks(String text, List<ImagePart> imageParts) {
        if ((text == null || text.isBlank()) && (imageParts == null || imageParts.isEmpty())) {
            return List.of();
        }
        List<Block> textBlocks = text == null || text.isBlank()
                ? List.of()
                : List.of(Block.text(text));
        if (imageParts == null || imageParts.isEmpty()) {
            return textBlocks;
        }
        java.util.ArrayList<Block> combined = new java.util.ArrayList<>(textBlocks);
        for (ImagePart part : imageParts) {
            combined.add(Block.image(part));
        }
        return List.copyOf(combined);
    }

    private static String renderBlocks(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .map(Block::renderText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    public enum BlockType {
        TEXT,
        IMAGE,
        STRUCTURED_JSON,
        MCP_RESULT_REFERENCE
    }

    public record Block(BlockType type, String text, JsonNode structuredJson,
                        ImagePart imagePart, String path, long sizeBytes, String mediaType) {
        public Block {
            type = Objects.requireNonNull(type, "type");
        }

        public static Block text(String text) {
            return new Block(BlockType.TEXT, text == null ? "" : text, null, null, null, 0L, null);
        }

        public static Block image(ImagePart imagePart) {
            return new Block(BlockType.IMAGE, null, null, imagePart, null, 0L,
                    imagePart == null ? null : imagePart.mimeType());
        }

        public static Block structuredJson(JsonNode json) {
            return new Block(BlockType.STRUCTURED_JSON, null, json, null, null, 0L, "application/json");
        }

        public static Block mcpResultReference(String path, long sizeBytes, String mediaType, String text) {
            return new Block(BlockType.MCP_RESULT_REFERENCE, text, null, null, path, sizeBytes, mediaType);
        }

        private String renderText() {
            return switch (type) {
                case TEXT -> text;
                case IMAGE -> text;
                case STRUCTURED_JSON -> structuredJson == null ? "" : structuredJson.toPrettyString();
                case MCP_RESULT_REFERENCE -> text;
            };
        }
    }
}
