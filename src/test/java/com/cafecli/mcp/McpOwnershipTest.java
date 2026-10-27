package com.cafecli.mcp;

import com.cafecli.mcp.config.McpConfigLoader;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.mcp.transport.McpTransport;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class McpOwnershipTest {
    @TempDir Path root;

    @Test void clientClosesExplicitlyOwnedDispatchersBeforeTransportOnceEvenWhenCleanupThrows() {
        var order = new ArrayList<String>();
        var primary = new AssertionError("listener close");
        var transportFailure = new IllegalStateException("transport close");
        McpTransport transport = new McpTransport() {
            @Override public void send(JsonNode message) { }
            @Override public void onReceive(Consumer<JsonNode> listener) { }
            @Override public String transportName() { return "stub"; }
            @Override public void close() { order.add("transport"); throw transportFailure; }
        };
        var client = new McpClient("stub", transport);
        client.onNotification(ignored -> {}, () -> order.add("first"));
        client.onNotification(ignored -> {}, () -> { order.add("second"); throw primary; });
        assertSame(primary, assertThrows(AssertionError.class, client::close));
        client.close();
        assertEquals(List.of("second", "first", "transport"), order);
        assertArrayEquals(new Throwable[]{transportFailure}, primary.getSuppressed());
    }

    @Test void failedDiscoveryClosesTheHttpSessionAndDoesNotCloseItAgain() throws Exception {
        try (var server = new MockWebServer()) {
            var deleted = new AtomicInteger();
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getMethod().equals("DELETE")) { deleted.incrementAndGet(); return new MockResponse().setResponseCode(204); }
                String body = request.getBody().readUtf8();
                if (body.contains("\"method\":\"initialize\"")) return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}")
                        .setHeader("Mcp-Session-Id", "owned-session");
                if (body.contains("tools/list")) return json("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"discovery failed\"}}");
                return new MockResponse().setResponseCode(202);
            }});
            server.start();
            Path projectConfig = root.resolve("mcp.json");
            Files.writeString(projectConfig, "{\"mcpServers\":{\"demo\":{\"url\":\"" + server.url("/mcp") + "\"}}}");
            var manager = new McpServerManager(sink(), root, new McpConfigLoader(root.resolve("missing.json"), projectConfig,
                    root, Map.of(), Map.of(), root));
            manager.loadConfiguredServers(); manager.startAll();
            assertEquals(McpServerStatus.ERROR, manager.server("demo").status());
            assertNull(manager.server("demo").client());
            assertEquals(1, deleted.get());
            manager.close(); assertEquals(1, deleted.get());
        }
    }
    @Test void managerAttemptsEveryConnectionAndClearsOwnershipEvenWhenUnregistrationFails() throws Exception {
        Path config = root.resolve("mcp.json");
        Files.writeString(config, "{\"mcpServers\":{\"one\":{\"command\":\"unused\",\"disabled\":true},\"two\":{\"command\":\"unused\",\"disabled\":true}}}");
        var closed = new AtomicInteger();
        var unregistered = new AtomicInteger();
        McpToolSink failing = new McpToolSink() {
            @Override public AuditLog getAuditLog() { return new AuditLog(root.resolve("audit")); }
            @Override public void replaceMcpToolOutputsForServer(String server, List<McpToolDescriptor> tools,
                    Function<McpToolDescriptor, Function<String, ToolOutput>> factory) { }
            @Override public void unregisterMcpTool(String name) { unregistered.incrementAndGet(); throw new AssertionError(name); }
        };
        var manager = new McpServerManager(failing, root, new McpConfigLoader(root.resolve("absent"), config, root, Map.of(), Map.of(), root));
        manager.loadConfiguredServers();
        for (String name : List.of("one", "two")) {
            var client = new McpClient(name, new McpTransport() {
                @Override public void send(JsonNode node) { }
                @Override public void onReceive(Consumer<JsonNode> listener) { }
                @Override public String transportName() { return "stub"; }
                @Override public void close() { closed.incrementAndGet(); }
            });
            manager.server(name).client(client);
            manager.server(name).tools(List.of(new McpToolDescriptor(name, "echo", "mcp__" + name + "__echo", "Echo", null)));
        }
        var failure = assertThrows(AssertionError.class, manager::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(2, unregistered.get()); assertEquals(2, closed.get());
        for (var server : manager.servers()) { assertNull(server.client()); assertTrue(server.tools().isEmpty()); }
        manager.close(); assertEquals(2, closed.get());
    }

    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private McpToolSink sink() { return new McpToolSink() {
        @Override public AuditLog getAuditLog() { return new AuditLog(root.resolve("audit")); }
        @Override public void replaceMcpToolOutputsForServer(String server, List<McpToolDescriptor> tools,
                Function<McpToolDescriptor, Function<String, ToolOutput>> factory) { }
        @Override public void unregisterMcpTool(String name) { }
    }; }
}
