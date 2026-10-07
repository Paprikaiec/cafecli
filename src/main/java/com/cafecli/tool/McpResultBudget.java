package com.cafecli.tool;

import com.cafecli.mcp.McpResultPersister;
import com.cafecli.mcp.StructuredContentRenderer;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.tool.ToolRegistry.ToolExecutionResult;
import com.cafecli.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * MCP 大结果的上下文预算：单条结果超过阈值、或同一批次 MCP 结果合计超出预算时，把大块落盘到
 * {@code <configDir>/tool-results/<session>/<scope>/}，回灌模型的只是一条带摘要与读取提示的引用。
 *
 * <p>落盘位置跟随 registry 的<b>当前</b>项目路径 / 会话 / 作用域：每次落盘现场向 {@code persisterFactory}
 * 要一个 {@link McpResultPersister}，不在构造期捕获（{@code setProjectPath} / {@code setResultScope} 之后照样生效）。
 */
final class McpResultBudget {
    private static final int MCP_RESULT_INLINE_THRESHOLD_CHARS = 25_000;
    private static final int MCP_BATCH_INLINE_BUDGET_CHARS = 125_000;
    private static final int MCP_TEXT_DIGEST_CHARS = 800;

    private final StructuredContentRenderer structuredContentRenderer = new StructuredContentRenderer();
    private final Supplier<McpResultPersister> persisterFactory;

    McpResultBudget(Supplier<McpResultPersister> persisterFactory) {
        this.persisterFactory = persisterFactory;
    }

    ToolOutput referenceLargeMcpResults(ToolInvocation invocation, ToolOutput output) {
        if (output == null || invocation == null || !McpToolDescriptor.isNamespaced(invocation.name())) {
            return output;
        }
        List<ToolOutput.Block> blocks = output.blocks();
        if (blocks == null || blocks.isEmpty()) {
            return output;
        }

        boolean changed = false;
        boolean textAlreadyPersisted = false;
        List<ToolOutput.Block> normalizedBlocks = persistCombinedMcpTextIfLarge(invocation, blocks);
        if (normalizedBlocks != blocks) {
            blocks = normalizedBlocks;
            changed = true;
            textAlreadyPersisted = true;
        }

        List<ToolOutput.Block> transformed = new ArrayList<>(blocks.size());
        for (ToolOutput.Block block : blocks) {
            if (block.type() == ToolOutput.BlockType.STRUCTURED_JSON && block.structuredJson() != null) {
                int compactLength = structuredContentRenderer.compactLength(block.structuredJson());
                if (compactLength >= MCP_RESULT_INLINE_THRESHOLD_CHARS) {
                    transformed.add(persistStructuredMcpBlock(invocation, block.structuredJson(), compactLength));
                    changed = true;
                    continue;
                }
            } else if (!textAlreadyPersisted
                    && block.type() == ToolOutput.BlockType.TEXT && block.text() != null
                    && block.text().length() >= MCP_RESULT_INLINE_THRESHOLD_CHARS) {
                transformed.add(persistTextMcpBlock(invocation, block.text()));
                changed = true;
                continue;
            }
            transformed.add(block);
        }
        return changed ? ToolOutput.ofBlocks(transformed, output.imageParts()).withStatus(output.status()) : output;
    }

    private List<ToolOutput.Block> persistCombinedMcpTextIfLarge(
            ToolInvocation invocation,
            List<ToolOutput.Block> blocks) {
        int textBlocks = 0;
        List<String> textParts = new ArrayList<>();
        for (ToolOutput.Block block : blocks) {
            if (block.type() == ToolOutput.BlockType.TEXT && block.text() != null) {
                textBlocks++;
                textParts.add(block.text());
            }
        }
        if (textBlocks <= 1) {
            return blocks;
        }

        String combinedText = String.join("\n\n", textParts);
        if (combinedText.length() < MCP_RESULT_INLINE_THRESHOLD_CHARS) {
            return blocks;
        }

        ToolOutput.Block reference = persistTextMcpBlock(invocation, combinedText);
        List<ToolOutput.Block> transformed = new ArrayList<>(blocks.size() - textBlocks + 1);
        boolean referenceAdded = false;
        for (ToolOutput.Block block : blocks) {
            if (block.type() == ToolOutput.BlockType.TEXT && block.text() != null) {
                if (!referenceAdded) {
                    transformed.add(reference);
                    referenceAdded = true;
                }
                continue;
            }
            transformed.add(block);
        }
        return transformed;
    }

    List<ToolExecutionResult> applyMcpBatchBudget(List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return results == null ? List.of() : results;
        }
        List<McpInlineCandidate> candidates = new ArrayList<>();
        int inlineChars = 0;
        for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
            ToolExecutionResult result = results.get(resultIndex);
            if (result == null || result.timedOut() || !McpToolDescriptor.isNamespaced(result.name()) || result.output() == null) {
                continue;
            }
            List<ToolOutput.Block> blocks = result.output().blocks();
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ToolOutput.Block block = blocks.get(blockIndex);
                int size = inlineMcpBlockSize(block);
                if (size <= 0) {
                    continue;
                }
                inlineChars += size;
                candidates.add(new McpInlineCandidate(resultIndex, blockIndex, size));
            }
        }
        if (inlineChars <= MCP_BATCH_INLINE_BUDGET_CHARS || candidates.isEmpty()) {
            return results;
        }

        List<List<ToolOutput.Block>> replacementBlocks = new ArrayList<>(Collections.nCopies(results.size(), null));
        candidates.sort(Comparator.comparingInt(McpInlineCandidate::size).reversed());
        int remainingInlineChars = inlineChars;
        for (McpInlineCandidate candidate : candidates) {
            if (remainingInlineChars <= MCP_BATCH_INLINE_BUDGET_CHARS) {
                break;
            }
            ToolExecutionResult result = results.get(candidate.resultIndex());
            List<ToolOutput.Block> blocks = replacementBlocks.get(candidate.resultIndex());
            if (blocks == null) {
                blocks = new ArrayList<>(result.output().blocks());
                replacementBlocks.set(candidate.resultIndex(), blocks);
            }
            ToolOutput.Block block = blocks.get(candidate.blockIndex());
            ToolInvocation invocation = new ToolInvocation(result.id(), result.name(), result.argumentsJson());
            if (block.type() == ToolOutput.BlockType.STRUCTURED_JSON && block.structuredJson() != null) {
                blocks.set(candidate.blockIndex(),
                        persistStructuredMcpBlock(invocation, block.structuredJson(), candidate.size()));
            } else if (block.type() == ToolOutput.BlockType.TEXT && block.text() != null) {
                blocks.set(candidate.blockIndex(), persistTextMcpBlock(invocation, block.text()));
            }
            remainingInlineChars -= candidate.size();
        }

        List<ToolExecutionResult> transformed = new ArrayList<>(results);
        for (int i = 0; i < replacementBlocks.size(); i++) {
            List<ToolOutput.Block> blocks = replacementBlocks.get(i);
            if (blocks == null) {
                continue;
            }
            ToolExecutionResult result = results.get(i);
            ToolOutput output = ToolOutput.ofBlocks(blocks, result.output().imageParts())
                    .withStatus(result.output().status());
            transformed.set(i, new ToolExecutionResult(
                    result.id(),
                    result.name(),
                    result.argumentsJson(),
                    output,
                    result.elapsedMillis(),
                    result.timedOut()));
        }
        return List.copyOf(transformed);
    }

    private int inlineMcpBlockSize(ToolOutput.Block block) {
        if (block == null) {
            return 0;
        }
        if (block.type() == ToolOutput.BlockType.TEXT && block.text() != null) {
            return block.text().length();
        }
        if (block.type() == ToolOutput.BlockType.STRUCTURED_JSON && block.structuredJson() != null) {
            return structuredContentRenderer.compactLength(block.structuredJson());
        }
        return 0;
    }

    private ToolOutput.Block persistStructuredMcpBlock(ToolInvocation invocation, JsonNode payload, int compactChars) {
        try {
            McpResultPersister persister = persisterFactory.get();
            McpResultPersister.StoredResult stored = persister.persistStructuredJson(safeToolCallFileStem(invocation.id()), payload);
            String relativePath = stored.relativePathForToolRegistry();
            String digest = structuredContentRenderer.renderReferenceDigest(payload);
            String reference = "MCP result reference: type=application/json, size=" + compactChars
                    + " chars, path=" + relativePath
                    + "\n" + digest
                    + "\n" + persister.readHint(relativePath);
            return ToolOutput.Block.mcpResultReference(relativePath, compactChars, "application/json", reference);
        } catch (IOException e) {
            return ToolOutput.Block.text("MCP result reference failed: " + e.getMessage()
                    + "\n\n" + payload.toPrettyString());
        }
    }

    private ToolOutput.Block persistTextMcpBlock(ToolInvocation invocation, String text) {
        try {
            McpResultPersister persister = persisterFactory.get();
            McpResultPersister.StoredResult stored = persister.persistText(safeToolCallFileStem(invocation.id()), text);
            String relativePath = stored.relativePathForToolRegistry();
            String reference = "MCP result reference: type=text/plain, size=" + text.length()
                    + " chars, path=" + relativePath
                    + "\nHead:\n" + textHead(text)
                    + "\nTail:\n" + textTail(text)
                    + "\n" + persister.readHint(relativePath);
            return ToolOutput.Block.mcpResultReference(relativePath, text.length(), "text/plain", reference);
        } catch (IOException e) {
            return ToolOutput.Block.text("MCP text result reference failed: " + e.getMessage()
                    + "\n\n" + text);
        }
    }

    private static String textHead(String text) {
        if (text == null || text.length() <= MCP_TEXT_DIGEST_CHARS) {
            return text == null ? "" : text;
        }
        return text.substring(0, MCP_TEXT_DIGEST_CHARS);
    }

    private static String textTail(String text) {
        if (text == null || text.length() <= MCP_TEXT_DIGEST_CHARS) {
            return "";
        }
        return text.substring(Math.max(0, text.length() - MCP_TEXT_DIGEST_CHARS));
    }

    private static String safeToolCallFileStem(String id) {
        String normalized = id == null || id.isBlank() ? "call" : id.trim();
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() ? "call" : normalized;
    }

    private record McpInlineCandidate(int resultIndex, int blockIndex, int size) {}
}
