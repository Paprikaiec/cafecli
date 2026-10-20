package com.cafecli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cafecli.llm.LlmClient;
import com.cafecli.tool.ToolOutput;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.tool.ToolExecutionResult;
import com.cafecli.tool.ToolInvocation;
import com.cafecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolRegistry 的 MCP 工具注册 / 反注册 / 调用路由。
 *
 * MCP 工具与内置工具同在 ToolRegistry 的一张工具表里，条目带 MCP 描述符；executeTool
 * 命中 MCP 条目时路由到注册时提供的 invoker 函数。
 */
class McpToolRegistrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersAndRoutesMcpToolToInvoker(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            McpToolDescriptor descriptor = sampleDescriptor();
            registry.registerMcpToolOutput(descriptor, args -> ToolOutput.text("echo:" + args));

            assertTrue(registry.hasTool("mcp__demo__echo"));
            assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("mcp__demo__echo")));
            assertEquals("echo:{\"text\":\"hi\"}", registry.executeTool("mcp__demo__echo", "{\"text\":\"hi\"}"));
        });
    }

    @Test
    void structuredMcpOutputSurvivesBatchExecution(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpToolOutput(sampleDescriptor(), args -> new ToolOutput(
                    "screenshot",
                    List.of(LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png"))));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call-1", "mcp__demo__echo", "{}")));

            assertEquals("screenshot", results.get(0).result());
            assertTrue(results.get(0).hasImageParts());
            assertEquals("image/png", results.get(0).imageParts().get(0).mimeType());
        });
    }

    @Test
    void largeStructuredMcpOutputIsPersistedAndReturnedAsBoundedReference(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            JsonNode payload = largeStructuredPayload();
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args ->
                    ToolOutput.ofBlocks(List.of(ToolOutput.Block.structuredJson(payload)), List.of()));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_1", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.length() < 2_000, modelVisible);
            assertTrue(modelVisible.contains(".cafecli/tool-results/"), modelVisible);
            assertTrue(modelVisible.contains("call_1.json"), modelVisible);
            assertFalse(modelVisible.contains("req-399"), modelVisible);

            Path resultRoot = tempDir.resolve(".cafecli/tool-results");
            List<Path> storedFiles;
            try (var stream = Files.walk(resultRoot)) {
                storedFiles = stream
                        .filter(path -> path.getFileName().toString().equals("call_1.json"))
                        .toList();
            }
            assertEquals(1, storedFiles.size());
            assertEquals(payload, MAPPER.readTree(storedFiles.get(0).toFile()));
        });
    }

    @Test
    void mcpClientStructuredContentPersistsThroughRegistryBatchExecution(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            JsonNode payload = largeStructuredPayload();
            InMemoryTransport transport = new InMemoryTransport()
                    .handle("initialize", p -> MAPPER.createObjectNode())
                    .handle("tools/call", p -> {
                        ObjectNode response = MAPPER.createObjectNode();
                        response.put("isError", false);
                        response.putArray("content").addObject()
                                .put("type", "text")
                                .put("text", "network snapshot");
                        response.set("structuredContent", payload);
                        return response;
                    });
            McpClient client = new McpClient("demo", transport);
            client.initialize();
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> {
                try {
                    return client.callToolOutput("echo", args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_rpc", "mcp__demo__echo", "{\"text\":\"hi\"}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.contains("network snapshot"), modelVisible);
            assertTrue(modelVisible.contains("call_rpc.json"), modelVisible);
            assertFalse(modelVisible.contains("req-399"), modelVisible);
            Path stored = findStoredResult(tempDir, "call_rpc.json");
            assertEquals(payload, MAPPER.readTree(stored.toFile()));
            client.close();
        });
    }

    @Test
    void largeStructuredMcpReferenceIncludesDigestAndRedactsSensitiveValues(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            JsonNode payload = largeSensitiveStructuredPayload();
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args ->
                    ToolOutput.ofBlocks(List.of(ToolOutput.Block.structuredJson(payload)), List.of()));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_digest", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.length() < 4_000, modelVisible);
            assertTrue(modelVisible.contains("Schema:"), modelVisible);
            assertTrue(modelVisible.contains("requestId: string"), modelVisible);
            assertTrue(modelVisible.contains("Samples:"), modelVisible);
            assertTrue(modelVisible.contains("total=400"), modelVisible);
            assertTrue(modelVisible.contains("omitted=397"), modelVisible);
            assertTrue(modelVisible.contains("req-0"), modelVisible);
            assertTrue(modelVisible.contains("req-1"), modelVisible);
            assertTrue(modelVisible.contains("req-2"), modelVisible);
            assertTrue(modelVisible.contains("[REDACTED]"), modelVisible);
            assertFalse(modelVisible.contains("req-399"), modelVisible);
            assertFalse(modelVisible.contains("super-secret-token"), modelVisible);

            String rawFile = Files.readString(findStoredResult(tempDir, "call_digest.json"));
            assertTrue(rawFile.contains("req-399"), rawFile);
            assertTrue(rawFile.contains("super-secret-token"), rawFile);
        });
    }

    @Test
    void largeStructuredMcpReferenceOmitsJqHintWhenUnavailable(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("paicli.mcp.result.jq.available");
        System.setProperty("paicli.mcp.result.jq.available", "false");
        try {
            withAuditDir(tempDir, () -> {
                JsonNode payload = largeStructuredPayload();
                ToolRegistry registry = new ToolRegistry();
                registry.setProjectPath(tempDir.toString());
                registry.registerMcpToolOutput(sampleDescriptor(), args ->
                        ToolOutput.ofBlocks(List.of(ToolOutput.Block.structuredJson(payload)), List.of()));

                List<ToolExecutionResult> results = registry.executeTools(List.of(
                        new ToolInvocation("call_without_structured_query_tool", "mcp__demo__echo", "{}")));

                String modelVisible = results.get(0).result();
                assertFalse(modelVisible.contains("Use jq"), modelVisible);
                assertTrue(modelVisible.contains("grep_code"), modelVisible);
                assertTrue(modelVisible.contains("read_file"), modelVisible);
                assertTrue(modelVisible.contains("offset/limit"), modelVisible);
            });
        } finally {
            restoreSystemProperty("paicli.mcp.result.jq.available", previous);
        }
    }

    @Test
    void smallStructuredMcpOutputStaysInline(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            JsonNode payload = MAPPER.readTree("""
                    {"requests":[{"requestId":"req-small","status":200}]}
                    """);
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args ->
                    ToolOutput.ofBlocks(List.of(ToolOutput.Block.structuredJson(payload)), List.of()));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_small", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.contains("req-small"), modelVisible);
            assertFalse(modelVisible.contains(".cafecli/tool-results/"), modelVisible);
            assertFalse(Files.exists(tempDir.resolve(".cafecli/tool-results")));
        });
    }

    @Test
    void largeMcpTextOutputIsPersistedWithHeadTailDigest(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            String largeText = largeTextPayload();
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> ToolOutput.text(largeText));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_text", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.length() < 4_000, modelVisible);
            assertTrue(modelVisible.contains(".cafecli/tool-results/"), modelVisible);
            assertTrue(modelVisible.contains("call_text.txt"), modelVisible);
            assertTrue(modelVisible.contains("HEAD_MARKER"), modelVisible);
            assertTrue(modelVisible.contains("TAIL_MARKER"), modelVisible);
            assertFalse(modelVisible.contains("MIDDLE_SECRET_MARKER"), modelVisible);

            String rawFile = Files.readString(findStoredResult(tempDir, "call_text.txt"));
            assertEquals(largeText, rawFile);
            assertTrue(rawFile.contains("MIDDLE_SECRET_MARKER"), rawFile);
        });
    }

    @Test
    void splitMcpTextContentIsJoinedBeforeLargeResultReference(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            String first = "SPLIT_HEAD\n" + "a".repeat(13_000);
            String second = "b".repeat(13_000) + "\nSPLIT_TAIL";
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> ToolOutput.ofBlocks(List.of(
                    ToolOutput.Block.text(first),
                    ToolOutput.Block.text(second)
            ), List.of()));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_split_text", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertTrue(modelVisible.length() < 4_000, modelVisible);
            assertTrue(modelVisible.contains("call_split_text.txt"), modelVisible);
            assertTrue(modelVisible.contains("SPLIT_HEAD"), modelVisible);
            assertTrue(modelVisible.contains("SPLIT_TAIL"), modelVisible);

            String rawFile = Files.readString(findStoredResult(tempDir, "call_split_text.txt"));
            assertEquals(first + "\n\n" + second, rawFile);
        });
    }

    @Test
    void smallMcpTextOutputStaysInline(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> ToolOutput.text("small text"));

            List<ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolInvocation("call_text_small", "mcp__demo__echo", "{}")));

            String modelVisible = results.get(0).result();
            assertEquals("small text", modelVisible);
            assertFalse(Files.exists(tempDir.resolve(".cafecli/tool-results")));
        });
    }

    @Test
    void mcpBatchBudgetPersistsLargestInlineResultsUntilUnderBudget(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> ToolOutput.text(batchBudgetText(args)));
            List<ToolInvocation> invocations = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                invocations.add(new ToolInvocation("call_batch_" + i,
                        "mcp__demo__echo",
                        "{\"index\":" + i + "}"));
            }

            List<ToolExecutionResult> results = registry.executeTools(invocations);

            int totalModelChars = results.stream().mapToInt(result -> result.result().length()).sum();
            long storedTextFiles;
            try (var stream = Files.walk(tempDir.resolve(".cafecli/tool-results"))) {
                storedTextFiles = stream.filter(path -> path.getFileName().toString().endsWith(".txt")).count();
            }
            assertTrue(totalModelChars < 125_000, "batch should be reduced under budget, got " + totalModelChars);
            assertTrue(storedTextFiles >= 1, "at least one under-threshold result should be persisted");
            assertTrue(results.stream().anyMatch(result -> result.result().contains(".txt")));
            assertTrue(results.stream().anyMatch(result -> result.result().contains("BATCH_INLINE_")));
        });
    }

    @Test
    void mcpBatchBudgetLeavesUnderBudgetInlineResultsAlone(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.registerMcpToolOutput(sampleDescriptor(), args -> ToolOutput.text(batchBudgetText(args)));
            List<ToolInvocation> invocations = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                invocations.add(new ToolInvocation("call_small_batch_" + i,
                        "mcp__demo__echo",
                        "{\"index\":" + i + "}"));
            }

            List<ToolExecutionResult> results = registry.executeTools(invocations);

            assertTrue(results.stream().allMatch(result -> result.result().contains("BATCH_INLINE_")));
            assertFalse(Files.exists(tempDir.resolve(".cafecli/tool-results")));
        });
    }

    @Test
    void unregisterRemovesMcpToolFromBothViews(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            McpToolDescriptor descriptor = sampleDescriptor();
            registry.registerMcpToolOutput(descriptor, args -> ToolOutput.text("echo:" + args));
            registry.unregisterMcpTool("mcp__demo__echo");

            assertFalse(registry.hasTool("mcp__demo__echo"));
            assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("mcp__demo__echo")));
        });
    }

    @Test
    void invokerExceptionsAreReportedAsToolErrorWithoutCrashingRegistry(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpToolOutput(sampleDescriptor(), args -> {
                throw new RuntimeException("upstream broke");
            });

            String result = registry.executeTool("mcp__demo__echo", "{}");
            assertTrue(result.contains("upstream broke"), "结果应包含 invoker 抛出的错误信息: " + result);
        });
    }

    @Test
    void registerMcpToolRejectsNullArgs(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            assertThrows(NullPointerException.class,
                    () -> registry.registerMcpToolOutput(null, args -> ToolOutput.text("x")));
            assertThrows(NullPointerException.class,
                    () -> registry.registerMcpToolOutput(sampleDescriptor(), null));
        });
    }

    @Test
    void replaceMcpToolsForServerAtomicallyReplacesOnlyThatServer(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpToolOutput(sampleDescriptor("demo", "old"), args -> ToolOutput.text("old"));
            registry.registerMcpToolOutput(sampleDescriptor("other", "keep"), args -> ToolOutput.text("keep"));

            registry.replaceMcpToolOutputsForServer("demo",
                    List.of(sampleDescriptor("demo", "new")),
                    descriptor -> args -> ToolOutput.text("new:" + descriptor.name()));

            assertFalse(registry.hasTool("mcp__demo__old"));
            assertTrue(registry.hasTool("mcp__demo__new"));
            assertTrue(registry.hasTool("mcp__other__keep"));
            assertEquals("new:new", registry.executeTool("mcp__demo__new", "{}"));
        });
    }

    /**
     * 派生前注册在父上的 MCP 工具，子任务 registry 能看见、能调用，且走的是父注册时的同一个 invoker
     * （按引用接管同一份外部连接，不重连）；调用照常记 MCP 审计。
     */
    @Test
    void childRegistryInvokesMcpToolRegisteredOnParentBeforeFork(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry parent = new ToolRegistry();
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            parent.registerMcpToolOutput(sampleDescriptor(), args -> {
                calls.incrementAndGet();
                return ToolOutput.text("echo:" + args);
            });

            ToolRegistry child = parent.createChildRegistry();

            assertTrue(child.hasTool("mcp__demo__echo"));
            LlmClient.Tool definition = child.getToolDefinitions().stream()
                    .filter(t -> t.name().equals("mcp__demo__echo"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Echo input (MCP server: demo, tool: echo)", definition.description());
            assertEquals(sampleDescriptor().inputSchema(), definition.parameters());

            assertEquals("echo:{\"text\":\"hi\"}", child.executeTool("mcp__demo__echo", "{\"text\":\"hi\"}"));
            assertEquals(1, calls.get(), "子任务应调用父注册的同一个 invoker");

            var entries = child.getAuditLog().readRecent(1);
            assertEquals(1, entries.size());
            assertEquals("mcp__demo__echo", entries.get(0).tool());
            assertEquals(com.cafecli.policy.AuditLog.OUTCOME_ALLOW, entries.get(0).outcome());
        });
    }

    private static JsonNode largeStructuredPayload() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (int i = 0; i < 400; i++) {
            ObjectNode request = requests.addObject();
            request.put("requestId", "req-" + i);
            request.put("url", "https://example.test/checkout/" + i + "/" + "x".repeat(80));
            request.put("status", i == 399 ? 500 : 200);
        }
        return root;
    }

    private static JsonNode largeSensitiveStructuredPayload() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (int i = 0; i < 400; i++) {
            ObjectNode request = requests.addObject();
            request.put("requestId", "req-" + i);
            request.put("url", "https://example.test/checkout/" + i + "/" + "x".repeat(80));
            request.put("status", i == 399 ? 500 : 200);
            request.put("token", "super-secret-token");
        }
        return root;
    }

    private static String largeTextPayload() {
        return "HEAD_MARKER\n"
                + "a".repeat(13_000)
                + "\nMIDDLE_SECRET_MARKER\n"
                + "b".repeat(13_000)
                + "\nTAIL_MARKER";
    }

    private static String batchBudgetText(String args) {
        int index = args == null || !args.contains(":") ? 0 : Integer.parseInt(args.replaceAll("\\D+", ""));
        return "BATCH_INLINE_" + index + "\n" + "x".repeat(24_000) + "\nBATCH_TAIL_" + index;
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static Path findStoredResult(Path projectRoot, String fileName) throws Exception {
        try (var stream = Files.walk(projectRoot.resolve(".cafecli/tool-results"))) {
            return stream
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static McpToolDescriptor sampleDescriptor() throws Exception {
        return sampleDescriptor("demo", "echo");
    }

    private static McpToolDescriptor sampleDescriptor(String server, String name) throws Exception {
        return new McpToolDescriptor(
                server,
                name,
                "mcp__" + server + "__" + name,
                "Echo input",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
        );
    }

    private static void withAuditDir(Path tempDir, ThrowingRunnable body) throws Exception {
        String previous = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty("paicli.audit.dir");
            } else {
                System.setProperty("paicli.audit.dir", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
