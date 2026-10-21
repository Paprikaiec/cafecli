package com.cafecli.harness;

import com.cafecli.ai.ModelRegistry;
import com.cafecli.ai.openai.DeepSeekProvider;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The real adapter and existing workspace capability meet at the session seam; the HTTP peer stays local. */
class DeepSeekSessionTest {
    @TempDir Path root;

    @Test
    void aConfiguredModelReadsARealFileAndReceivesItsThinkingAndToolResultOnTheNextIteration() throws Exception {
        Path home = Files.createDirectory(root.resolve("home"));
        Path project = Files.createDirectory(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "forty-two");
        Files.writeString(Files.createDirectory(home.resolve(".cafecli")).resolve("config.json"), """
                {"defaultProvider":"deepseek","providers":{"deepseek":{"model":"private-deepseek"}},
                 "models":[{"provider":"deepseek","id":"private-deepseek","contextWindow":50000}]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of("DEEPSEEK_API_KEY", "fixture-key"), home, project));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String sse = requests.size() == 1 ? """
                    data: {"choices":[{"delta":{"reasoning_content":"Read the answer file."}}]}
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"read_1","function":{"name":"read_file","arguments":"{\\"path\\":\\"answer.txt\\"}"}}]},"finish_reason":"tool_calls"}]}
                    data: [DONE]
                    """ : """
                    data: {"choices":[{"delta":{"content":"The file says forty-two."},"finish_reason":"stop"}]}
                    data: [DONE]
                    """;
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            var selection = config.provider();
            var session = AgentSession.builder().config(config)
                    .provider(new DeepSeekProvider(selection.apiKey(), "http://127.0.0.1:" + server.getAddress().getPort()))
                    .model(new ModelRegistry(selection.modelOverrides()).resolve(selection.name(), selection.model()))
                    .capabilities(List.of(new WorkspaceCapability(project))).build();
            var outcome = assertInstanceOf(RunOutcome.Completed.class, session.submit("Read answer.txt"));
            assertEquals("The file says forty-two.", outcome.message().text());
            assertEquals("deepseek", outcome.message().provider());
            assertEquals("private-deepseek", outcome.message().model());
            assertEquals(2, requests.size());
            assertEquals("private-deepseek", requests.getFirst().path("model").asText());
            assertEquals("read_file", requests.getFirst().path("tools").get(0).path("function").path("name").asText());
            JsonNode messages = requests.get(1).path("messages");
            JsonNode assistant = messages.get(messages.size() - 2);
            assertEquals("assistant", assistant.path("role").asText());
            assertEquals("Read the answer file.", assistant.path("reasoning_content").asText());
            assertEquals("read_1", assistant.path("tool_calls").get(0).path("id").asText());
            JsonNode result = messages.get(messages.size() - 1);
            assertEquals("tool", result.path("role").asText());
            assertEquals("read_1", result.path("tool_call_id").asText());
            assertTrue(result.path("content").asText().contains("forty-two"));
            assertInstanceOf(AssistantEntry.class, session.log().get(1));
            assertInstanceOf(ToolResultEntry.class, session.log().get(2));
            assertEquals("forty-two", Files.readString(project.resolve("answer.txt")));
        } finally {
            server.stop(0);
        }
    }
}
