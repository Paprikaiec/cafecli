package com.cafecli.ai.fake;

import com.cafecli.ai.Context;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.Model;
import com.cafecli.ai.ProviderException;
import com.cafecli.ai.StreamOptions;
import com.cafecli.ai.message.AssistantContent;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.Usage;
import com.cafecli.ai.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A script file, loaded and replayed through the fake provider: what the CLI and the parity harness will feed it. */
class FakeScriptTest {

    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());

    @TempDir
    Path dir;

    @Test
    void aScriptFileIsReplayedOneResponsePerRequest() throws Exception {
        FakeProvider provider = FakeProvider.scripted(FakeScript.load(write("""
                {"responses": [
                  {"thinking": "Need the file.", "text": "Reading it.",
                   "toolCalls": [{"id": "call_1", "name": "read_file", "arguments": {"path": "a.txt"}}],
                   "usage": {"input": 10, "output": 5, "cached": 2}},
                  {"text": "It says hello.", "usage": {"input": 30, "output": 4}},
                  {"text": "Half", "error": {"kind": "overflow", "message": "prompt is too long"}}
                ]}
                """)));

        assertEquals(message(List.of(
                        new ThinkingBlock("Need the file."),
                        new TextBlock("Reading it."),
                        new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                        StopReason.TOOL_USE, new Usage(10, 5, 2)),
                next(provider));
        assertEquals(message(List.of(new TextBlock("It says hello.")), StopReason.END_TURN, new Usage(30, 4, 0)),
                next(provider));
        ProviderException overflow = assertThrows(ProviderException.class, () -> next(provider));
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, overflow.kind());
        assertEquals("prompt is too long", overflow.getMessage());
        assertEquals(message(List.of(new TextBlock("Half")), StopReason.ERROR, Usage.ZERO), overflow.partial());
    }

    @ParameterizedTest
    @CsvSource({
            "retryable, RETRYABLE",
            "overflow, CONTEXT_OVERFLOW",
            "aborted, ABORTED",
            "fatal, FATAL"})
    void everyErrorKindCanBeScripted(String name, ErrorKind kind) throws Exception {
        FakeProvider provider = FakeProvider.scripted(FakeScript.load(write(
                "{\"responses\": [{\"error\": {\"kind\": \"" + name + "\", \"message\": \"scripted\"}}]}")));

        assertEquals(kind, assertThrows(ProviderException.class, () -> next(provider)).kind());
    }

    @Test
    void argumentsWrittenAsAStringAreSentAsThatExactRawText() throws Exception {
        FakeProvider provider = FakeProvider.scripted(FakeScript.load(write("""
                {"responses": [{"toolCalls": [{"id": "c", "name": "read_file", "arguments": "{\\"path\\": \\"a.t"}]}]}
                """)));

        assertEquals(List.of(new ToolCall("c", "read_file", "{\"path\": \"a.t")), next(provider).toolCalls());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"responses\": [{\"text\": \"ok\"}, {\"txt\": \"hi\"}]}                         | responses[1] has an unknown field \"txt\"",
            "{\"responses\": [{}]}                                                         | responses[0]",
            "{\"responses\": {}}                                                           | responses",
            "{\"answers\": []}                                                             | answers",
            "{\"responses\": [{\"text\": 1}]}                                              | responses[0].text",
            "{\"responses\": [{\"toolCalls\": [{\"name\": \"read_file\"}]}]}                | responses[0].toolCalls[0].id",
            "{\"responses\": [{\"toolCalls\": [{\"id\": \" \", \"name\": \"read_file\"}]}]}   | responses[0].toolCalls[0].id",
            "{\"responses\": [{\"toolCalls\": [{\"id\": \"c\", \"name\": \"\"}]}]}           | responses[0].toolCalls[0].name",
            "{\"responses\": [{\"toolCalls\": [{\"id\": \"c\", \"name\": \"x\", \"arguments\": 1}]}]} | responses[0].toolCalls[0].arguments",
            "{\"responses\": [{\"text\": \"a\", \"usage\": {\"input\": -1}}]}               | responses[0].usage.input",
            "{\"responses\": [{\"error\": {\"kind\": \"timeout\"}}]}                         | responses[0].error.kind",
            "{\"responses\": [                                                            | not valid JSON"})
    void anInvalidScriptIsRejectedNamingWhatIsWrongWhere(String json, String where) throws Exception {
        Path script = write(json);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FakeScript.load(script));

        assertTrue(e.getMessage().contains(where), e.getMessage());
    }

    private Path write(String json) throws IOException {
        return Files.writeString(dir.resolve("script.json"), json);
    }

    private static AssistantMessage next(FakeProvider provider) throws ProviderException {
        Context context = new Context("", List.of(UserMessage.text("go")), List.of());
        return provider.stream(MODEL, context, StreamOptions.defaults()).complete();
    }

    private static AssistantMessage message(List<AssistantContent> content, StopReason stopReason, Usage usage) {
        return new AssistantMessage(content, stopReason, usage, "fake", "fake-model");
    }
}
