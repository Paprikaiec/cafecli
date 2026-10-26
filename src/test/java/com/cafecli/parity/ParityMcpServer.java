package com.cafecli.parity;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local HTTP MCP endpoint shared by both clients; no machine-local scripts or network credentials. */
final class ParityMcpServer implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();
    final AtomicInteger calls = new AtomicInteger();

    ParityMcpServer() throws Exception {
        var responses = ParityScript.JSON.readTree(Path.of("src/test/resources/parity/mcp-responses.json").toFile());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var rpc = ParityScript.JSON.readTree(request.getBody().readUtf8());
                    if (!rpc.has("id")) return new MockResponse().setResponseCode(202);
                    String method = rpc.path("method").asText();
                    if (method.equals("tools/call")) {
                        if (!rpc.path("params").path("name").asText().equals("echo")
                                || !rpc.path("params").path("arguments").path("value").asText().equals("hello"))
                            return new MockResponse().setResponseCode(400);
                        calls.incrementAndGet();
                    }
                    if (!responses.has(method)) return new MockResponse().setResponseCode(400);
                    var result = ParityScript.JSON.createObjectNode().put("jsonrpc", "2.0");
                    result.set("id", rpc.get("id")); result.set("result", responses.get(method));
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(result.toString());
                } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
            }
        });
        server.start();
    }

    byte[] configure(Path project) throws Exception {
        Path config = project.resolve(".cafecli/mcp.json"); Files.createDirectories(config.getParent());
        byte[] expected = ParityScript.JSON.writeValueAsBytes(java.util.Map.of("mcpServers",
                java.util.Map.of("demo", java.util.Map.of("url", server.url("/mcp").toString()))));
        Files.write(config, expected);
        return expected;
    }
    @Override public void close() throws Exception { server.close(); }
}
