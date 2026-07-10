package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResultReferencingMeasurementTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void checkoutRegressionFixtureReducesHotContextAndKeepsRootCauseRecoverable(@TempDir Path tempDir) throws Exception {
        ObjectNode network = networkFixture();
        ObjectNode trace = traceFixture();
        String domSnapshot = "DOM_HEAD\n" + "d".repeat(55_000) + "\nDOM_TAIL";
        String lighthouse = "LIGHTHOUSE_HEAD\n" + "l".repeat(35_000) + "\nLIGHTHOUSE_TAIL";
        long baselineChars = domSnapshot.length()
                + lighthouse.length()
                + network.toPrettyString().length()
                + trace.toPrettyString().length();

        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/call", params -> toolResult(params.path("name").asText(), domSnapshot, lighthouse, network, trace));
        McpClient client = new McpClient("chrome-devtools", transport);
        client.initialize();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registerTool(registry, "take_snapshot", args -> client.callToolOutput("take_snapshot", args));
        registerTool(registry, "list_network_requests", args -> client.callToolOutput("list_network_requests", args));
        registerTool(registry, "trace_insight", args -> client.callToolOutput("trace_insight", args));
        registerTool(registry, "lighthouse_report", args -> client.callToolOutput("lighthouse_report", args));

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_dom", "mcp__chrome-devtools__take_snapshot", "{}"),
                new ToolRegistry.ToolInvocation("call_network", "mcp__chrome-devtools__list_network_requests", "{}"),
                new ToolRegistry.ToolInvocation("call_trace", "mcp__chrome-devtools__trace_insight", "{}"),
                new ToolRegistry.ToolInvocation("call_lighthouse", "mcp__chrome-devtools__lighthouse_report", "{}")
        ));

        long referencedChars = results.stream().mapToLong(result -> result.result().length()).sum();
        double reduction = 1.0 - ((double) referencedChars / (double) baselineChars);
        System.out.printf("MCP result referencing fixture: baseline=%d chars, referenced=%d chars, reduction=%.2f%%%n",
                baselineChars, referencedChars, reduction * 100.0);

        assertTrue(reduction >= 0.70, "expected >=70% hot-context reduction, got " + reduction);
        assertTrue(results.stream().anyMatch(result -> result.result().contains("call_network.json")));
        assertTrue(results.stream().noneMatch(result -> result.result().contains("CHECKOUT_TIMEOUT")));

        String grep = registry.executeTool("grep_code",
                "{\"pattern\":\"CHECKOUT_TIMEOUT\",\"path\":\".paicli/tool-results/"
                        + registry.getSessionId() + "/main\",\"max_results\":5}");
        assertTrue(grep.contains("call_network.json"), grep);
        assertTrue(grep.contains("CHECKOUT_TIMEOUT"), grep);

        Path networkFile = findStoredResult(tempDir, "call_network.json");
        assertTrue(Files.readString(networkFile).contains("req-450"));
        client.close();
    }

    private static JsonNode toolResult(String toolName, String domSnapshot, String lighthouse,
                                       ObjectNode network, ObjectNode trace) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("isError", false);
        if ("take_snapshot".equals(toolName)) {
            result.putArray("content").addObject().put("type", "text").put("text", domSnapshot);
        } else if ("lighthouse_report".equals(toolName)) {
            result.putArray("content").addObject().put("type", "text").put("text", lighthouse);
        } else if ("list_network_requests".equals(toolName)) {
            result.putArray("content").addObject().put("type", "text").put("text", "network requests");
            result.set("structuredContent", network);
        } else if ("trace_insight".equals(toolName)) {
            result.putArray("content").addObject().put("type", "text").put("text", "trace insight");
            result.set("structuredContent", trace);
        }
        return result;
    }

    private static ObjectNode networkFixture() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (int i = 0; i < 520; i++) {
            ObjectNode request = requests.addObject();
            request.put("requestId", "req-" + i);
            request.put("traceId", "trace-" + i);
            request.put("url", "https://shop.test/checkout/" + i + "/" + "x".repeat(60));
            request.put("status", i == 450 ? 504 : 200);
            request.put("errorCode", i == 450 ? "CHECKOUT_TIMEOUT" : "");
            request.put("authorization", "Bearer raw-network-token");
        }
        return root;
    }

    private static ObjectNode traceFixture() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode frames = root.putArray("frames");
        for (int i = 0; i < 320; i++) {
            ObjectNode frame = frames.addObject();
            frame.put("frameId", "frame-" + i);
            frame.put("durationMs", i == 280 ? 1900 : 12);
            frame.put("label", "checkout-render-" + i + "-" + "y".repeat(40));
        }
        return root;
    }

    private static void registerTool(ToolRegistry registry, String name, ThrowingInvoker invoker) throws Exception {
        registry.registerMcpToolOutput(descriptor(name), args -> {
            try {
                return invoker.invoke(args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static McpToolDescriptor descriptor(String name) throws Exception {
        return new McpToolDescriptor(
                "chrome-devtools",
                name,
                "mcp__chrome-devtools__" + name,
                name,
                MAPPER.readTree("{\"type\":\"object\"}")
        );
    }

    private static Path findStoredResult(Path projectRoot, String fileName) throws Exception {
        try (var stream = Files.walk(projectRoot.resolve(".paicli/tool-results"))) {
            return stream
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @FunctionalInterface
    private interface ThrowingInvoker {
        com.paicli.tool.ToolOutput invoke(String args) throws Exception;
    }
}
