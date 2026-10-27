package com.cafecli.core.log;

import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.Usage;
import com.cafecli.ai.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent log serializes losslessly from day one (ADR 0020 decision 2), so resume and fork can come later without a
 * format migration — including custom kinds, which core never sees the definitions of.
 */
class AgentLogJsonTest {

    /** A custom kind defined outside core, as the harness defines its own. */
    record MemoryRecall(String query, List<String> facts) implements CustomEntry {
    }

    private final AgentLogJson json = new AgentLogJson(Map.of("memoryRecall", MemoryRecall.class));

    @Test
    void aWholeLogWithACustomKindSurvivesAJsonRoundTripUnchanged() {
        List<LogEntry> log = List.of(
                new UserEntry(new UserMessage(List.of(
                        new TextBlock("What is in a.png?"), new ImageBlock("iVBORw0KGgo=", "image/png")))),
                new MemoryRecall("a.png", List.of("Fixtures are PNG.", "Tests live under src/test.")),
                new AssistantEntry(new AssistantMessage(List.of(
                        new ThinkingBlock("Read it first.", "sig-Zm9v"),
                        new TextBlock("Reading."),
                        new ToolCall("call_1", "read_file", "{ \"path\" : \"a.png\" }")),
                        StopReason.TOOL_USE, new Usage(1200, 85, 1024), "deepseek", "deepseek-chat")),
                new ToolResultEntry(new ToolResultMessage("call_1", "read_file",
                        List.of(new TextBlock("未知工具: read_file")), true)),
                new AssistantEntry(new AssistantMessage(List.of(new TextBlock("It is a logo.")),
                        StopReason.END_TURN, Usage.ZERO, "deepseek", "deepseek-chat")));

        String written = json.write(log);

        assertEquals(log, json.read(written), written);
    }

    @Test
    void theKindIdsAndFieldNamesAreTheLogFormat() {
        List<LogEntry> log = List.of(
                new UserEntry(UserMessage.text("q")),
                new MemoryRecall("q", List.of("f")),
                new AssistantEntry(new AssistantMessage(List.of(new ToolCall("call_1", "read_file", "{}")),
                        StopReason.TOOL_USE, Usage.ZERO, "p", "m")),
                new ToolResultEntry(new ToolResultMessage("call_1", "read_file", List.of(new TextBlock("x")), true)));

        assertEquals("[{\"kind\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"q\"}]}},"
                        + "{\"kind\":\"memoryRecall\",\"query\":\"q\",\"facts\":[\"f\"]},"
                        + "{\"kind\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"toolCall\",\"id\":\"call_1\",\"name\":\"read_file\",\"arguments\":\"{}\"}],"
                        + "\"stopReason\":\"TOOL_USE\","
                        + "\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"cachedInputTokens\":0},"
                        + "\"provider\":\"p\",\"model\":\"m\"}},"
                        + "{\"kind\":\"toolResult\",\"message\":{\"role\":\"toolResult\",\"toolCallId\":\"call_1\","
                        + "\"toolName\":\"read_file\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"isError\":true},\"status\":\"ERROR\",\"details\":null}]",
                json.write(log));
    }

    record JsonCustom(JsonNode value) implements CustomEntry {
    }

    @Test
    void customJsonPayloadsKeepTheirOwnNumericRepresentationWhenToolDetailsUseExactDecimals() throws Exception {
        AgentLogJson json = new AgentLogJson(Map.of("jsonCustom", JsonCustom.class));
        List<LogEntry> log = List.of(new JsonCustom(new ObjectMapper().readTree("{\"score\":1.5}")));

        assertEquals(log, json.read(json.write(log)));
    }

    /** A custom kind nobody registered. */
    record Unregistered(String text) implements CustomEntry {
    }

    @Test
    void writingAKindThatWasNeverRegisteredFailsInsteadOfWritingALogThatCannotBeReadBack() {
        List<LogEntry> log = List.of(new UserEntry(UserMessage.text("hi")), new Unregistered("lost"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> json.write(log));

        assertTrue(failure.getMessage().contains(Unregistered.class.getName()), failure.getMessage());
    }

    @Test
    void aRegistrationThatWouldMakeTheFormatAmbiguousIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentLogJson(Map.of("user", MemoryRecall.class)));
        assertThrows(IllegalArgumentException.class, () -> new AgentLogJson(Map.of(
                "memoryRecall", MemoryRecall.class, "recall", MemoryRecall.class)));
    }
}
