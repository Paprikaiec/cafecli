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
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

/** Durable compatible trace field contract, independent of retired writers. */
class TraceCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void finalizedSourceOrderedToolResultsHaveTheCompatibleFields() throws Exception {
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
        var expected = JSON.createArrayNode();
        expected.addObject().put("type", "system").put("text", provider.requests().getFirst().context().systemPrompt());
        expected.addObject().put("type", "user").put("text", "question");
        var assistant = expected.addObject().put("type", "assistant").put("reasoning", "plan");
        assistant.putArray("tool_calls").addObject().put("id", "first").put("name", "first_tool").put("arguments", firstArgs);
        assistant.withArray("tool_calls").addObject().put("id", "second").put("name", "second_tool").put("arguments", secondArgs);
        expected.addObject().put("type", "tool_result").put("tool_call_id", "first").put("content", "final first_tool");
        expected.addObject().put("type", "tool_result").put("tool_call_id", "second").put("content", "final second_tool");
        expected.addObject().put("type", "assistant").put("text", "done");
        var result = expected.addObject().put("type", "result").put("provider", "fake").put("model", "fake-model").put("wall_ms", 0).put("turns", 2);
        result.putObject("tokens").put("in", 4).put("out", 6).put("cache", 1);
        var actual = parse(Files.readString(file));
        ((ObjectNode) actual.getLast()).put("wall_ms", 0);
        assertEquals(expected, JSON.valueToTree(actual));
        assertFalse(Files.readString(file).contains("immediate"), "only final publication belongs to compatible tool results");
    }

    private static List<JsonNode> parse(String jsonl) {
        return jsonl.lines().map(line -> {
            try { return JSON.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
    }
}
