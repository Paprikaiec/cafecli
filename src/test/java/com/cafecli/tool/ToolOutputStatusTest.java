package com.cafecli.tool;

import com.cafecli.image.ImagePart;
import com.cafecli.mcp.McpResultPersister;
import com.cafecli.tool.ToolOutput.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Typed result contracts survive independently of any registry or runtime. */
class ToolOutputStatusTest {
    @TempDir Path root;

    @Test void constructorsDefaultToOkAndWithStatusCopiesEverythingElse() {
        assertEquals(Status.OK, ToolOutput.text("x").status());
        assertEquals(Status.OK, new ToolOutput("x", List.of()).status());
        assertEquals(Status.OK, new ToolOutput("x", List.of(), null).status());
        assertEquals(Status.OK, new ToolOutput("x", List.of(), null, null).status());
        assertEquals(Status.OK, ToolOutput.ofBlocks(List.of(ToolOutput.Block.text("x")), List.of()).status());

        ToolOutput original = ToolOutput.ofBlocks(List.of(ToolOutput.Block.text("a"), ToolOutput.Block.text("b")),
                List.of(new ImagePart("aGVsbG8=", "image/png")));
        ToolOutput denied = original.withStatus(Status.DENIED);
        assertEquals(Status.DENIED, denied.status());
        assertFalse(denied.isOk());
        assertEquals(original.text(), denied.text());
        assertEquals(original.blocks(), denied.blocks());
        assertEquals(original.imageParts(), denied.imageParts());
        assertEquals(ToolOutput.text("t").withStatus(Status.TIMEOUT), ToolOutput.failure(Status.TIMEOUT, "t"));
    }

    @Test void largeMcpReferenceRetainsItsProducerStatus() {
        var persister = new McpResultPersister(root, "status", "main");
        var budget = new McpResults(() -> persister);
        var result = budget.reference("call_large", "mcp__demo__echo", ToolOutput.failure(Status.ERROR, "x".repeat(30_000)));
        assertEquals(Status.ERROR, result.status());
        assertTrue(result.blocks().stream().anyMatch(block -> block.type() == ToolOutput.BlockType.MCP_RESULT_REFERENCE));
    }

    @Test void aggregateMcpReferencesRetainEveryProducerStatus() {
        var persister = new McpResultPersister(root, "batch", "main");
        var budget = new McpResults(() -> persister);
        var input = java.util.stream.IntStream.range(0, 6).mapToObj(index -> new ToolExecutionResult(
                "call_" + index, "mcp__demo__echo", "{}", ToolOutput.failure(Status.ERROR, "x".repeat(24_000)), 0, false)).toList();
        var results = budget.batch(input);
        assertTrue(results.stream().anyMatch(result -> result.output().blocks().stream()
                .anyMatch(block -> block.type() == ToolOutput.BlockType.MCP_RESULT_REFERENCE)));
        assertTrue(results.stream().allMatch(result -> result.output().status() == Status.ERROR));
    }
}
