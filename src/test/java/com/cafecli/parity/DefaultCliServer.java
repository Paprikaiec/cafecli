package com.cafecli.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Local wire boundary, serving both actual Step adapters; request keys keep children independent of MAIN. */
final class DefaultCliServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonNode script;
    private volatile String activeArm;
    private final Map<String, List<JsonNode>> rpc = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> requests = new ConcurrentHashMap<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    DefaultCliServer(JsonNode script) throws Exception {
        this.script = script;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    void activate(String arm) { activeArm = arm; }
    List<JsonNode> rpc(String arm) { return List.copyOf(rpc.getOrDefault(arm, List.of())); }
    String mcpUrl() { return baseUrl("mcp"); }
    String baseUrl(String arm) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + arm; }
    List<JsonNode> requests(String arm, String route) { return List.copyOf(requests.getOrDefault(arm + "/" + route, List.of())); }
    void assertHealthy() { org.junit.jupiter.api.Assertions.assertEquals(List.of(), failures); }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            if (exchange.getRequestMethod().equals("DELETE")) { exchange.sendResponseHeaders(202, -1); return; }
            JsonNode request = ParityScript.JSON.readTree(exchange.getRequestBody());
            if (exchange.getRequestURI().getPath().equals("/mcp")) { mcp(exchange, request); return; }
            org.junit.jupiter.api.Assertions.assertEquals("Bearer local-fixture-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String arm = exchange.getRequestURI().getPath().split("/")[1];
            String firstUser = "";
            for (JsonNode message : request.path("messages")) if (message.path("role").asText().equals("user")) { firstUser = message.path("content").asText(); break; }
            String route = script.path("routes").path(firstUser).asText("main");
            var recorded = requests.computeIfAbsent(arm + "/" + route, ignored -> new CopyOnWriteArrayList<>());
            int index;
            synchronized (recorded) { index = recorded.size(); recorded.add(request.deepCopy()); }
            JsonNode response = script.path("responses").path(route).path(index);
            if (response.isMissingNode()) throw new AssertionError("missing " + arm + "/" + route + " response " + index);
            ObjectNode delta = ParityScript.JSON.createObjectNode();
            if (response.has("text")) delta.put("content", response.path("text").asText());
            if (response.has("thinking")) delta.put("reasoning_content", response.path("thinking").asText());
            if (response.has("toolCalls")) {
                var calls = delta.putArray("tool_calls"); int callIndex = 0;
                for (JsonNode call : response.path("toolCalls")) {
                    var value = calls.addObject().put("index", callIndex++).put("id", call.path("id").asText()).put("type", "function");
                    value.putObject("function").put("name", call.path("name").asText()).put("arguments", call.path("arguments").toString());
                }
            }
            JsonNode packet = ParityScript.JSON.valueToTree(Map.of("choices", List.of(Map.of("index", 0, "delta", delta,
                    "finish_reason", response.has("toolCalls") ? "tool_calls" : "stop")), "usage", Map.of(
                    "prompt_tokens", route.equals("main") ? 3 : 99, "completion_tokens", route.equals("main") ? 2 : 55,
                    "prompt_tokens_details", Map.of("cached_tokens", route.equals("main") ? 1 : 12))));
            byte[] bytes = ("data: " + packet + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Throwable failure) { failures.add(failure); }
    }
    private void mcp(HttpExchange exchange, JsonNode request) throws Exception {
        rpc.computeIfAbsent(activeArm, ignored -> new CopyOnWriteArrayList<>()).add(request.deepCopy());
        if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
        Object result = switch (request.path("method").asText()) {
            case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of(), "resources", Map.of()),
                    "serverInfo", Map.of("name", "parity-fixture", "version", "1"));
            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "Echo fixture", "inputSchema", Map.of(
                    "type", "object", "properties", Map.of("value", Map.of("type", "string")), "required", List.of("value")))));
            case "resources/list" -> Map.of("resources", List.of(Map.of("uri", "file://guide", "name", "Guide", "description", "Fixture resource", "mimeType", "text/plain")));
            case "resources/read" -> {
                org.junit.jupiter.api.Assertions.assertEquals("file://guide", request.path("params").path("uri").asText());
                yield Map.of("contents", List.of(Map.of("uri", "file://guide", "mimeType", "text/plain", "text", "RESOURCE FIXTURE BODY")));
            }
            case "tools/call" -> {
                org.junit.jupiter.api.Assertions.assertEquals("echo", request.path("params").path("name").asText());
                org.junit.jupiter.api.Assertions.assertEquals("hello", request.path("params").path("arguments").path("value").asText());
                yield Map.of("content", List.of(Map.of("type", "text", "text", "MCP fixture hello")), "isError", false);
            }
            default -> throw new AssertionError("unexpected MCP method " + request);
        };
        byte[] bytes = ParityScript.JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); workers.close(); }
}
