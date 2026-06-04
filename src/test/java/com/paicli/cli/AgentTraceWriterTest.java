package com.paicli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient.Message;
import com.paicli.llm.LlmClient.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentTraceWriterTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void writesConversationAndToolUseAsNdjson() throws IOException {
        List<Message> history = List.of(
                Message.system("SYS PROMPT"),
                Message.user("fix the bug"),
                Message.assistant("", List.of(
                        new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"A.java\"}")))),
                Message.tool("call_1", "file content"),
                Message.assistant("done"));

        StringWriter sw = new StringWriter();
        AgentTraceWriter.write(sw, history, new Agent.TokenUsage(100, 20, 5),
                "deepseek", "deepseek-v4-pro", 1234L);

        String[] lines = sw.toString().strip().split("\n");
        assertEquals(6, lines.length);

        assertEquals("system", M.readTree(lines[0]).get("type").asText());
        assertEquals("user", M.readTree(lines[1]).get("type").asText());

        JsonNode toolUse = M.readTree(lines[2]);
        assertEquals("assistant", toolUse.get("type").asText());
        assertEquals("read_file", toolUse.get("tool_calls").get(0).get("name").asText());
        assertEquals("{\"path\":\"A.java\"}", toolUse.get("tool_calls").get(0).get("arguments").asText());

        JsonNode toolResult = M.readTree(lines[3]);
        assertEquals("tool_result", toolResult.get("type").asText());
        assertEquals("call_1", toolResult.get("tool_call_id").asText());
        assertEquals("file content", toolResult.get("content").asText());

        JsonNode result = M.readTree(lines[5]);
        assertEquals("result", result.get("type").asText());
        assertEquals("deepseek-v4-pro", result.get("model").asText());
        assertEquals(1234, result.get("wall_ms").asLong());
        assertEquals(100, result.get("tokens").get("in").asInt());
        assertEquals(20, result.get("tokens").get("out").asInt());
        assertEquals(5, result.get("tokens").get("cache").asInt());
        assertEquals(2, result.get("turns").asInt());
    }

    @Test
    void emitsReasoningFieldWhenPresent() throws IOException {
        List<Message> history = List.of(Message.assistant("my reasoning", "my answer"));
        StringWriter sw = new StringWriter();
        AgentTraceWriter.write(sw, history, Agent.TokenUsage.zero(), "deepseek", "deepseek-v4-pro", 1L);
        String[] lines = sw.toString().strip().split("\n");
        JsonNode assistant = M.readTree(lines[0]);
        assertEquals("my reasoning", assistant.get("reasoning").asText());
        assertEquals("my answer", assistant.get("text").asText());
    }

    @Test
    void emptyHistoryEmitsOnlyResultLine() throws IOException {
        StringWriter sw = new StringWriter();
        AgentTraceWriter.write(sw, List.of(), Agent.TokenUsage.zero(), "deepseek", "deepseek-v4-pro", 0L);
        String[] lines = sw.toString().strip().split("\n");
        assertEquals(1, lines.length);
        assertEquals("result", M.readTree(lines[0]).get("type").asText());
    }
}
