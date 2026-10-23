package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.Usage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.trace.TraceCapability;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.llm.LlmClient;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary v1 writer reference, alongside durable neutral trace assertions elsewhere. */
class TraceCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void finalizedSourceOrderedToolResultsHaveExactlyTheLegacyWritersCompatibleFields() throws Exception {
        String firstArgs = "{ \"path\" : \"one\" }";
        String secondArgs = "{\"path\":\"two\"}";
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("plan")
                .toolCall("first", "first_tool", firstArgs).toolCall("second", "second_tool", secondArgs)
                .usage(new Usage(1, 2, 0)).build(), FakeResponse.builder().text("done").usage(new Usage(3, 4, 1)).build()));
        Capability tools = new Capability() { public void attachAgent(AgentScope scope) {
            for (String name : List.of("first_tool", "second_tool")) scope.tool(new LeafToolAdapter(new Tool(name,
                    "fixture", JSON.createObjectNode(), input -> ToolOutput.text("immediate " + name))));
            scope.toolBatchResults(results -> results.stream().map(result -> new ToolResultEntry(
                    new ToolResultMessage(result.message().toolCallId(), result.message().toolName(),
                            List.of(new TextBlock("final " + result.message().toolName())), false), result.status(), result.details())).toList());
        }};
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root));
        Path file = root.resolve("trace.jsonl"); var events = new CopyOnWriteArrayList<AgentEvent>();
        try (var session = AgentSession.builder().config(config).provider(provider)
                .model(new Model("fake-model", "fake", "fake", 128000, 8192, Set.of()))
                .capabilities(List.of(tools, new TraceCapability(file))).build()) {
            session.subscribe(events::add); run(session, "question");
        }
        assertEquals(2, events.stream().filter(AgentEvent.ToolEnd.class::isInstance).count());
        assertEquals(1, events.stream().filter(AgentEvent.ToolBatchEnd.class::isInstance).count());
        assertTrue(events.stream().filter(AgentEvent.ToolEnd.class::isInstance).map(AgentEvent.ToolEnd.class::cast)
                .allMatch(end -> end.result().message().content().toString().contains("immediate")));
        var legacy = List.of(LlmClient.Message.system(provider.requests().getFirst().context().systemPrompt()),
                LlmClient.Message.user("question"), LlmClient.Message.assistant("plan", "", List.of(
                        new LlmClient.ToolCall("first", new LlmClient.ToolCall.Function("first_tool", firstArgs)),
                        new LlmClient.ToolCall("second", new LlmClient.ToolCall.Function("second_tool", secondArgs)))),
                LlmClient.Message.tool("first", "final first_tool"), LlmClient.Message.tool("second", "final second_tool"),
                LlmClient.Message.assistant("done"));
        var expected = new StringWriter();
        com.cafecli.cli.AgentTraceWriter.write(expected, legacy, new com.cafecli.agent.Agent.TokenUsage(4, 6, 1),
                "fake", "fake-model", 0);
        var actual = parse(Files.readString(file));
        ((ObjectNode) actual.getLast()).put("wall_ms", 0);
        assertEquals(parse(expected.toString()), actual);
        assertFalse(Files.readString(file).contains("immediate"), "only final publication belongs to compatible tool results");
    }

    private static List<JsonNode> parse(String jsonl) {
        return jsonl.lines().map(line -> {
            try { return JSON.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
    }
}
