package com.cafecli.ai.message;

import com.cafecli.ai.Context;
import com.cafecli.ai.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Messages are embedded in agent-log entries, which must serialize losslessly from day one (resume and fork come
 * later, without a format migration).
 */
class MessageJsonTest {

    private static final TypeReference<List<Message>> MESSAGES = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Message> everyKind = List.of(
            new UserMessage(List.of(
                    new TextBlock("What is in this picture?"),
                    new ImageBlock("iVBORw0KGgo=", "image/png"))),
            new AssistantMessage(List.of(
                    new ThinkingBlock("The user wants a description.", "sig-Zm9v"),
                    new ThinkingBlock("No signature on this one."),
                    new TextBlock("Let me look."),
                    new ToolCall("call_1", "read_file", "{ \"path\" : \"a.txt\" }")),
                    StopReason.TOOL_USE, new Usage(1200, 85, 1024), "deepseek", "deepseek-chat"),
            new ToolResultMessage("call_1", "read_file", List.of(
                    new TextBlock("hello"),
                    new ImageBlock("R0lGODlh", "image/gif")), false),
            new ToolResultMessage("call_2", "read_file", List.of(new TextBlock("no such file")), true),
            new AssistantMessage(List.of(new TextBlock("Half")), StopReason.ABORTED, Usage.ZERO, "p", "m"));

    @Test
    void everyMessageAndBlockTypeSurvivesAJsonRoundTripUnchanged() throws Exception {
        for (Message message : everyKind) {
            String json = mapper.writeValueAsString(message);

            assertEquals(message, mapper.readValue(json, Message.class), json);
        }
        String conversation = mapper.writerFor(MESSAGES).writeValueAsString(everyKind);
        assertEquals(everyKind, mapper.readValue(conversation, MESSAGES), conversation);
    }

    @Test
    void aRequestContextSurvivesAJsonRoundTripUnchanged() throws Exception {
        Context context = new Context("You are Cafecli.", everyKind, List.of(new ToolDefinition("read_file",
                "Reads a file", JsonNodeFactory.instance.objectNode().put("type", "object"))));

        String json = mapper.writeValueAsString(context);

        assertEquals(context, mapper.readValue(json, Context.class), json);
    }

    @Test
    void theTypeIdsAndFieldNamesAreTheLogFormat() throws Exception {
        Message message = new AssistantMessage(List.of(
                new ThinkingBlock("hm", "sig"),
                new TextBlock("Hi"),
                new ToolCall("call_1", "read_file", "{}")), StopReason.TOOL_USE, new Usage(3, 2, 1), "p", "m");
        Message result = new ToolResultMessage("call_1", "read_file",
                List.of(new TextBlock("x"), new ImageBlock("AA==", "image/png")), true);

        assertEquals("{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"hm\",\"signature\":\"sig\"},"
                        + "{\"type\":\"text\",\"text\":\"Hi\"},"
                        + "{\"type\":\"toolCall\",\"id\":\"call_1\",\"name\":\"read_file\",\"arguments\":\"{}\"}],"
                        + "\"stopReason\":\"TOOL_USE\","
                        + "\"usage\":{\"inputTokens\":3,\"outputTokens\":2,\"cachedInputTokens\":1},"
                        + "\"provider\":\"p\",\"model\":\"m\"}",
                mapper.writeValueAsString(message));
        assertEquals("{\"role\":\"toolResult\",\"toolCallId\":\"call_1\",\"toolName\":\"read_file\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"x\"},"
                        + "{\"type\":\"image\",\"base64\":\"AA==\",\"mimeType\":\"image/png\"}],"
                        + "\"isError\":true}",
                mapper.writeValueAsString(result));
        assertEquals("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"q\"}]}",
                mapper.writeValueAsString(UserMessage.text("q")));
    }
}
