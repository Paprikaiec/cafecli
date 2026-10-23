package com.cafecli.app;

import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.SubmitResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentsCompositionTest {
    @TempDir Path root;

    @Test void actualCompositionRunsAChildAndReturnsItsFinalResult() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"toolCalls":[{"id":"spawn","name":"spawn_agent","arguments":{"description":"write","prompt":"write child.txt"}}]},
                  {"toolCalls":[{"id":"write","name":"write_file","arguments":{"path":"child.txt","content":"child bytes"}}]},
                  {"text":"child conclusion"},
                  {"text":"parent conclusion"}
                ]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), project));
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (var session = Composition.headlessSession(config)) {
            session.subscribe(events::add);
            var result = assertInstanceOf(SubmitResult.Completed.class, session.submit("delegate"));
            assertEquals("parent conclusion", assertInstanceOf(RunOutcome.Completed.class, result.outcome()).message().text());
            assertEquals("child bytes", Files.readString(project.resolve("child.txt")));
            assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.RunStart && event.agentId().equals("task_1")));
            var spawn = events.stream().filter(event -> event instanceof AgentEvent.ToolEnd end && end.result().message().toolName().equals("spawn_agent"))
                    .map(AgentEvent.ToolEnd.class::cast).findFirst().orElseThrow();
            assertEquals("[task_1 · done]\n\nchild conclusion", ((com.cafecli.ai.message.TextBlock) spawn.result().message().content().getFirst()).text());
        }
    }
    @Test void childFreshnessDoesNotLeakAStatusLineIntoTheParentsTranscript() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"toolCalls":[{"id":"spawn","name":"spawn_agent","arguments":{"prompt":"latest Java release"}}]},
                  {"text":"child conclusion"}, {"text":"parent conclusion"}
                ]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), project));
        List<String> status = new CopyOnWriteArrayList<>();
        try (var session = Composition.headlessSession(config, status::add)) {
            session.submit("delegate");
            assertTrue(status.isEmpty(), "child-only freshness must remain outside the parent transcript: " + status);
        }
    }

    @Test void childMcpResultsUseCanonicalTaskScopeAndStayRetainedWithoutClosingSessionConnections() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String payload = "BEGIN" + "x".repeat(30000) + "END";
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    try {
                        var body = json.readTree(request.getBody().readUtf8());
                        if (!body.has("id")) return new okhttp3.mockwebserver.MockResponse().setResponseCode(202);
                        Object result = switch (body.path("method").asText()) {
                            case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of()));
                            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "Echo", "inputSchema", Map.of("type", "object"))));
                            case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", payload)));
                            default -> Map.of();
                        };
                        return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                                .setBody(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", body.get("id"), "result", result)));
                    } catch (Exception e) { return new okhttp3.mockwebserver.MockResponse().setResponseCode(500); }
                }
            });
            server.start();
            Path project = Files.createDirectories(root.resolve("project"));
            Path configDir = Files.createDirectories(project.resolve(".cafecli"));
            Files.writeString(configDir.resolve("mcp.json"), json.writeValueAsString(Map.of("mcpServers",
                    Map.of("demo", Map.of("url", server.url("/mcp").toString())))));
            Path script = root.resolve("script.json");
            Files.writeString(script, """
                    {"responses":[
                      {"toolCalls":[{"id":"spawn","name":"spawn_agent","arguments":{"prompt":"get data"}}]},
                      {"toolCalls":[{"id":"child-data","name":"mcp__demo__echo"}]},
                      {"text":"child conclusion"},
                      {"toolCalls":[{"id":"parent-data","name":"mcp__demo__echo"}]},
                      {"text":"parent conclusion"}
                    ]}
                    """);
            var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake",
                    "cafecli.fake.script", script.toString()), Map.of(), root.resolve("home"), project));
            List<Path> childFiles = new CopyOnWriteArrayList<>();
            Path resultsRoot = configDir.resolve("tool-results");
            try (var session = Composition.headlessSession(config)) {
                session.subscribe(event -> {
                    if (event instanceof AgentEvent.ToolEnd end && event.agentId().equals("task_1")) {
                        assertTrue(end.result().message().content().toString().contains("MCP result reference:"));
                        try (var files = Files.walk(resultsRoot)) {
                            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
                            childFiles.add(file);
                            assertEquals("task_1", file.getParent().getFileName().toString());
                            assertEquals(payload, Files.readString(file));
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                    }
                });
                session.submit("delegate");
                assertEquals(1, childFiles.size());
                assertTrue(Files.exists(childFiles.getFirst()), "idle child retains its result scope for send_message until reap or session close");
                try (var files = Files.walk(resultsRoot)) {
                    Path main = files.filter(Files::isRegularFile).filter(file -> file.getParent().getFileName().toString().equals("main")).findFirst().orElseThrow();
                    assertEquals("main", main.getParent().getFileName().toString());
                    assertEquals(payload, Files.readString(main));
                }
            }
            try (var files = Files.walk(resultsRoot)) { assertFalse(files.anyMatch(Files::isRegularFile)); }
        }
    }

}
