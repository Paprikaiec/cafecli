package com.cafecli.harness;

import com.cafecli.harness.mcp.McpCapability;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import com.cafecli.harness.permission.*;
import com.cafecli.policy.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpCapabilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
    @TempDir Path root;
    private String payload = "hello";
    private boolean resources;
    private com.fasterxml.jackson.databind.JsonNode structured;
    private final List<Capability> extraCapabilities = new ArrayList<>();
    private final java.util.concurrent.CountDownLatch toolCompleted = new java.util.concurrent.CountDownLatch(1);
    private boolean notifyChange;
    private boolean blockCall;
    private final java.util.concurrent.CountDownLatch callEntered = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch releaseCall = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger promptBuilds = new java.util.concurrent.atomic.AtomicInteger();

    @Test void configuredToolsRoundTripAndBusinessErrorsKeepReturnedProvenance() throws Exception {
        try (MockWebServer server = server()) {
            FakeProvider provider = FakeProvider.scripted(List.of(
                    FakeResponse.builder().toolCall("one", "mcp__demo__echo", "{\"value\":\"hello\"}").build(),
                    FakeResponse.text("done")));
            try (AgentSession session = session(provider, server)) {
                assertInstanceOf(RunOutcome.Completed.class, run(session, "echo"));
                assertTrue(provider.requests().getFirst().context().tools().stream().anyMatch(t -> t.name().equals("mcp__demo__echo")));
                ToolResultEntry result = session.log().stream().filter(ToolResultEntry.class::isInstance)
                        .map(ToolResultEntry.class::cast).findFirst().orElseThrow();
                assertEquals(ToolResult.Status.ERROR, result.status());
                assertEquals(ToolExecution.Kind.RETURNED, ToolExecution.from(new ToolResult(result.message().content(), result.details(), result.status())).kind());
                assertTrue(provider.requests().get(1).context().messages().stream().filter(ToolResultMessage.class::isInstance)
                        .map(ToolResultMessage.class::cast).anyMatch(m -> m.content().toString().contains("hello")));
            }
        }
    }

    @Test void largeTextIsReferencedUnderTheAgentScopeAndClearRemovesItsPayload() throws Exception {
        payload = "BEGIN\n" + "x".repeat(30000) + "\nEND";
        try (MockWebServer server = server()) {
            var provider = FakeProvider.scripted(List.of(
                    FakeResponse.builder().toolCall("large", "mcp__demo__echo", "{}").build(), FakeResponse.text("done")));
            try (var session = session(provider, server)) {
                run(session, "large");
                String visible = provider.requests().get(1).context().messages().toString();
                assertTrue(visible.contains("MCP result reference:"), visible.substring(0, Math.min(200, visible.length())));
                assertFalse(visible.contains("x".repeat(2000)));
                Path file;
                try (var files = Files.walk(root.resolve("project/.cafecli/tool-results"))) {
                    file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
                }
                assertTrue(file.getParent().getFileName().toString().equals("main"));
                assertEquals("MCP 工具返回错误: " + payload, Files.readString(file));
                session.dispatch("/clear");
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test void resourcesMentionsAndCommandsUseTheConnectedSession() throws Exception {
        resources = true;
        try (var server = server()) {
            var provider = FakeProvider.responding(request -> FakeResponse.text("done"));
            try (var session = session(provider, server)) {
                assertTrue(session.dispatch("/mcp").orElseThrow().contains("demo"));
                assertTrue(session.dispatch("/mcp resources demo").orElseThrow().contains("file://guide"));
                assertTrue(session.dispatch("/mcp prompts demo").orElseThrow().contains("Review"));
                assertTrue(session.dispatch("/mcp logs demo").orElseThrow().contains("暂无"));
                run(session, "read @demo:file://guide");
                var request = provider.requests().getFirst().context();
                assertTrue(request.systemPrompt().contains("@demo:file://guide"));
                assertTrue(request.messages().toString().contains("RESOURCE BODY"));
                assertTrue(session.dispatch("/mcp disable demo").orElseThrow().contains("禁用"));
                run(session, "disabled");
                assertEquals(List.of(), provider.requests().get(1).context().tools());
                assertFalse(provider.requests().get(1).context().systemPrompt().contains("@demo:file://guide"));
                assertTrue(session.dispatch("/mcp enable demo").orElseThrow().contains("启用"));
                assertTrue(session.dispatch("/mcp restart demo").orElseThrow().contains("重启"));
            }
            try (var files = Files.walk(root.resolve("audit"))) {
                assertTrue(files.filter(Files::isRegularFile).map(path -> {
                    try { return Files.readString(path); } catch (Exception e) { throw new RuntimeException(e); }
                }).anyMatch(text -> text.contains("mention") && text.contains("mcp__demo__read_resource")));
            }
        }
    }

    @Test void listChangedMakesNewToolExecutableNextIterationAndCoalescesPromptRebuilds() throws Exception {
        resources = true; notifyChange = true;
        try (var server = server()) {
            var provider = FakeProvider.scripted(List.of(
                    FakeResponse.builder().toolCall("change", "mcp__demo__echo", "{}").build(),
                    FakeResponse.builder().toolCall("new", "mcp__demo__fresh", "{}").build(),
                    FakeResponse.text("done"), FakeResponse.text("again")));
            try (var session = session(provider, server)) {
                session.subscribe(event -> {
                    if (event instanceof com.cafecli.core.AgentEvent.ToolEnd end
                            && end.result().message().toolCallId().equals("change")) {
                        var command = session.commands().stream().filter(c -> c.name().equals("/mcp")).findFirst().orElseThrow();
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        while (!command.handler().apply(session, "").contains("4 tools") && System.nanoTime() < deadline) {
                            Thread.yield();
                        }
                        assertTrue(command.handler().apply(session, "").contains("4 tools"), "notification refresh never completed");
                    }
                });
                run(session, "change tools");
                assertTrue(provider.requests().get(1).context().tools().stream().anyMatch(t -> t.name().equals("mcp__demo__fresh")));
                var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                        .filter(e -> e.message().toolCallId().equals("new")).findFirst().orElseThrow();
                assertTrue(result.message().content().toString().contains("hello"));
                assertEquals(2, promptBuilds.get(), "one initial build and one coalesced rebuild");
                run(session, "again");
                assertEquals(provider.requests().get(1).context().systemPrompt(), provider.requests().get(3).context().systemPrompt());
                assertEquals(2, promptBuilds.get(), "ordinary runs reuse the prompt");
            }
        }
    }

    @Test void aggregateBudgetKeepsImmediateCompletionsAndPublishesFinalReferencesInSourceOrder() throws Exception {
        payload = "z".repeat(20000);
        try (var server = server()) {
            var response = FakeResponse.builder();
            for (int i = 0; i < 7; i++) response.toolCall("batch" + i, "mcp__demo__echo", "{\"wait\":" + (i == 6) + "}");
            var provider = FakeProvider.scripted(List.of(response.build(), FakeResponse.text("done")));
            try (var session = session(provider, server)) {
                var completions = new ArrayList<ToolResultEntry>();
                var publications = new ArrayList<com.cafecli.core.AgentEvent.ToolBatchEnd>();
                session.subscribe(event -> {
                    if (event instanceof com.cafecli.core.AgentEvent.ToolEnd end) {
                        completions.add(end.result()); toolCompleted.countDown();
                    } else if (event instanceof com.cafecli.core.AgentEvent.ToolBatchEnd end) publications.add(end);
                });
                run(session, "batch");
                assertEquals(7, completions.size());
                assertEquals(1, publications.size());
                var published = publications.getFirst().results();
                assertEquals(java.util.stream.IntStream.range(0, 7).mapToObj(i -> "batch" + i).toList(),
                        published.stream().map(e -> e.message().toolCallId()).toList());
                assertEquals(published, session.log().stream().filter(ToolResultEntry.class::isInstance).toList());
                assertEquals(published.stream().map(ToolResultEntry::message).toList(), provider.requests().get(1).context()
                        .messages().stream().filter(ToolResultMessage.class::isInstance).toList());
                assertTrue(published.stream().anyMatch(e -> e.message().content().toString().contains("MCP result reference:")));
                for (var entry : published) {
                    assertEquals(ToolResult.Status.ERROR, entry.status());
                    var completion = completions.stream().filter(e -> e.message().toolCallId().equals(entry.message().toolCallId())).findFirst().orElseThrow();
                    assertEquals(completion.details().get("execution"), entry.details().get("execution"));
                }
            }
        }
    }

    @Test void structuredPayloadPersistsExactlyAndDigestRedactsSensitiveKeys() throws Exception {
        structured = JSON.createObjectNode().put("token", "private-token").put("data", "s".repeat(30000));
        try (var server = server()) {
            var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("json", "mcp__demo__echo", "{}").build(), FakeResponse.text("done")));
            Path payloadFile;
            try (var session = session(provider, server)) {
                run(session, "json");
                String visible = provider.requests().get(1).context().messages().toString();
                assertTrue(visible.contains("MCP result reference:"));
                assertFalse(visible.contains("private-token"));
                try (var files = Files.walk(root.resolve("project/.cafecli/tool-results"))) {
                    payloadFile = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
                }
                assertEquals(structured, JSON.readTree(payloadFile.toFile()));
            }
            assertFalse(Files.exists(payloadFile), "session close releases agent results");
            assertFalse(Files.exists(payloadFile.getParent().getParent()), "session close removes the session result directory");
        }
    }

    @Test void profilesOwnIsolatedResultScopesAndUnattachedAgentsHaveNoMcpToolsOrIndex() throws Exception {
        payload = "q".repeat(30000); resources = true;
        try (var server = server()) {
            var provider = FakeProvider.scripted(List.of(
                    FakeResponse.builder().toolCall("same", "mcp__demo__echo", "{}").build(), FakeResponse.text("first"),
                    FakeResponse.builder().toolCall("same", "mcp__demo__echo", "{}").build(), FakeResponse.text("second"), FakeResponse.text("none")));
            var config = config(server);
            var permissions = PermissionCapability.headless();
            var audit = new AuditCapability(permissions, new AuditLog(root.resolve("audit")));
            var mcp = new McpCapability(config.mcp(), audit);
            var events = new SessionEvents();
            var registrar = new SessionContributions(events);
            audit.attachSession(registrar); mcp.attachSession(registrar); registrar.close();
            try {
                var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                        config.homes().user().resolve("prompts"), config.homes().project().resolve("prompts"))),
                        List.of(mcp), events, java.time.Duration.ofSeconds(90));
                var profile = new AgentProfile(MODEL, com.cafecli.prompt.PromptMode.AGENT, List.of(mcp), 5);
                var first = factory.create("first", profile);
                var second = factory.create("second", profile);
                try {
                    first.run(com.cafecli.ai.message.UserMessage.text("go"), new com.cafecli.ai.CancellationToken());
                    second.run(com.cafecli.ai.message.UserMessage.text("go"), new com.cafecli.ai.CancellationToken());
                    Path firstFile; Path secondFile;
                    try (var files = Files.walk(root.resolve("project/.cafecli/tool-results"))) {
                        var payloads = files.filter(Files::isRegularFile).toList();
                        firstFile = payloads.stream().filter(p -> p.getParent().getFileName().toString().equals("first")).findFirst().orElseThrow();
                        secondFile = payloads.stream().filter(p -> p.getParent().getFileName().toString().equals("second")).findFirst().orElseThrow();
                    }
                    assertEquals(registrar.sessionId(), firstFile.getParent().getParent().getFileName().toString());
                    assertFalse(provider.requests().get(2).context().messages().toString().contains(firstFile.getParent().getFileName().toString()));
                    first.close(); first.close();
                    assertFalse(Files.exists(firstFile)); assertTrue(Files.exists(secondFile));
                    second.close(); assertFalse(Files.exists(secondFile));
                    try (var absent = factory.create("absent", new AgentProfile(MODEL, com.cafecli.prompt.PromptMode.AGENT, List.of(), 5))) {
                        absent.run(com.cafecli.ai.message.UserMessage.text("none"), new com.cafecli.ai.CancellationToken());
                        assertEquals(List.of(), provider.requests().get(4).context().tools());
                        assertFalse(provider.requests().get(4).context().systemPrompt().contains("@demo:file://guide"));
                    }
                } finally { first.close(); second.close(); }
            } finally { registrar.cleanup(); }
        }
    }

    @Test void failedClearAssemblyNeverDeletesTheStillActiveMainScopesPayload() throws Exception {
        payload = "t".repeat(30000);
        var attached = new java.util.concurrent.atomic.AtomicInteger();
        extraCapabilities.add(new Capability() { @Override public void attachAgent(AgentScope agent) {
            if (attached.incrementAndGet() == 2) throw new IllegalStateException("replacement rejected");
        }});
        try (var server = server()) {
            var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("retained", "mcp__demo__echo", "{}").build(),
                    FakeResponse.text("done"), FakeResponse.text("still usable")));
            try (var session = session(provider, server)) {
                run(session, "first");
                Path file;
                try (var files = Files.walk(root.resolve("project/.cafecli/tool-results"))) {
                    file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
                }
                String bytes = Files.readString(file);
                assertThrows(IllegalStateException.class, session::clear);
                assertEquals(bytes, Files.readString(file));
                run(session, "again");
                assertTrue(provider.requests().get(2).context().messages().toString().contains("retained"));
            }
        }
    }

    @Test void closeCancelsAnActiveCallAndItsLateReplyCannotRecreateResultFiles() throws Exception {
        blockCall = true; payload = "late".repeat(10000);
        try (var server = server()) {
            var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("late", "mcp__demo__echo", "{}").build()));
            var session = session(provider, server);
            try {
                var running = java.util.concurrent.CompletableFuture.supplyAsync(() -> run(session, "go"));
                assertTrue(callEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                session.close();
                assertInstanceOf(RunOutcome.Cancelled.class, running.get(5, java.util.concurrent.TimeUnit.SECONDS));
                releaseCall.countDown();
                Path results = root.resolve("project/.cafecli/tool-results");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500);
                do {
                    if (Files.exists(results)) {
                        try (var files = Files.walk(results)) {
                            assertFalse(files.anyMatch(Files::isRegularFile), "a late MCP result survived session close");
                        }
                    }
                    Thread.sleep(10);
                } while (System.nanoTime() < deadline);
            } finally { releaseCall.countDown(); session.close(); }
        }
    }

    private HarnessConfig config(MockWebServer server) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path configDir = Files.createDirectories(project.resolve(".cafecli"));
        Files.writeString(configDir.resolve("mcp.json"), "{\"mcpServers\":{\"demo\":{\"url\":\"" + server.url("/mcp") + "\"}}}");
        return ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), home, project));
    }
    private AgentSession session(FakeProvider provider, MockWebServer server) throws Exception {
        HarnessConfig config = config(server);
        PermissionCapability permissions = PermissionCapability.headless();
        AuditCapability audit = new AuditCapability(permissions, new AuditLog(root.resolve("audit")));
        var capabilities = new ArrayList<Capability>(List.of(new SessionCapability(), permissions, audit, new McpCapability(config.mcp(), audit),
                new Capability() { @Override public void attachAgent(AgentScope agent) {
                    agent.promptContext(context -> { promptBuilds.incrementAndGet(); return context; });
                } }));
        capabilities.addAll(extraCapabilities);
        return AgentSession.builder().config(config).model(MODEL).provider(provider).capabilities(capabilities).build();
    }
    private static RunOutcome run(AgentSession session, String input) {
        return assertInstanceOf(SubmitResult.Completed.class, session.submit(input)).outcome();
    }
    private MockWebServer server() throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var body = JSON.readTree(request.getBody().readUtf8());
                    if (!body.has("id")) return new MockResponse().setResponseCode(202);
                    if (blockCall && body.path("method").asText().equals("tools/call")) {
                        callEntered.countDown();
                        if (!releaseCall.await(5, java.util.concurrent.TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    }
                    if (body.path("method").asText().equals("tools/call") && body.path("params").path("arguments").path("wait").asBoolean()) {
                        if (!toolCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    }
                    String result = switch (body.path("method").asText()) {
                        case "initialize" -> "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}" + (resources ? ",\"resources\":{}" : "") + "}}";
                        case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}" +
                                (changed.get() ? ",{\"name\":\"fresh\",\"description\":\"Fresh tool\",\"inputSchema\":{\"type\":\"object\"}}" : "") + "]}";
                        case "tools/call" -> structured == null
                                ? "{\"content\":[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(payload) + "}],\"isError\":true}"
                                : "{\"content\":[],\"structuredContent\":" + structured + "}";
                        case "resources/list" -> "{\"resources\":[{\"uri\":\"file://guide\",\"name\":\"Guide\",\"mimeType\":\"text/plain\"}]}";
                        case "resources/read" -> "{\"contents\":[{\"uri\":\"file://guide\",\"mimeType\":\"text/plain\",\"text\":\"RESOURCE BODY\"}]}";
                        case "prompts/list" -> "{\"prompts\":[{\"name\":\"review\",\"title\":\"Review\"}]}";
                        default -> "{}";
                    };
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + body.path("id") + ",\"result\":" + result + "}";
                    if (notifyChange && body.path("method").asText().equals("tools/call") && changed.compareAndSet(false, true)) {
                        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n" +
                                "data: " + response + "\n\n");
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(response);
                } catch (Exception e) { return new MockResponse().setResponseCode(500); }
            }
        });
        server.start(); return server;
    }
}
