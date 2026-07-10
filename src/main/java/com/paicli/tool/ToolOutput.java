package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.llm.LlmClient;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record ToolOutput(String text, List<LlmClient.ContentPart> imageParts, List<Block> blocks) {
    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        blocks = blocks == null ? defaultBlocks(text, imageParts) : List.copyOf(blocks);
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, defaultBlocks(text, imageParts));
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), List.of(Block.text(text)));
    }

    public static ToolOutput ofBlocks(List<Block> blocks, List<LlmClient.ContentPart> imageParts) {
        List<Block> safeBlocks = blocks == null ? List.of() : List.copyOf(blocks);
        List<LlmClient.ContentPart> safeImages = imageParts == null ? List.of() : List.copyOf(imageParts);
        return new ToolOutput(renderBlocks(safeBlocks), safeImages, safeBlocks);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }

    public String renderTextForLlm() {
        return renderBlocks(blocks);
    }

    private static List<Block> defaultBlocks(String text, List<LlmClient.ContentPart> imageParts) {
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
        for (LlmClient.ContentPart part : imageParts) {
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
                        LlmClient.ContentPart imagePart, String path, long sizeBytes, String mediaType) {
        public Block {
            type = Objects.requireNonNull(type, "type");
        }

        public static Block text(String text) {
            return new Block(BlockType.TEXT, text == null ? "" : text, null, null, null, 0L, null);
        }

        public static Block image(LlmClient.ContentPart imagePart) {
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
