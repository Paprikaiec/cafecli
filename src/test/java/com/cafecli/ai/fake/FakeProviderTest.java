package com.cafecli.ai.fake;

import com.cafecli.ai.AssistantStream;
import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.Model;
import com.cafecli.ai.ProviderException;
import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.StreamEvent.Done;
import com.cafecli.ai.StreamEvent.Failed;
import com.cafecli.ai.StreamEvent.Retry;
import com.cafecli.ai.StreamEvent.Start;
import com.cafecli.ai.StreamEvent.TextDelta;
import com.cafecli.ai.StreamEvent.TextEnd;
import com.cafecli.ai.StreamEvent.TextStart;
import com.cafecli.ai.StreamEvent.ThinkingDelta;
import com.cafecli.ai.StreamEvent.ThinkingEnd;
import com.cafecli.ai.StreamEvent.ThinkingStart;
import com.cafecli.ai.StreamEvent.ToolCallDelta;
import com.cafecli.ai.StreamEvent.ToolCallEnd;
import com.cafecli.ai.StreamEvent.ToolCallStart;
import com.cafecli.ai.StreamEvent.UsageUpdate;
import com.cafecli.ai.StreamOptions;
import com.cafecli.ai.ToolDefinition;
import com.cafecli.ai.message.AssistantContent;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.ToolCall;
import com.cafecli.ai.message.Usage;
import com.cafecli.ai.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The provider seam, driven through the fake: what a consumer of {@code Provider.stream} sees. */
class FakeProviderTest {

    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());

    @Test
    void aTextAnswerStreamsAsOneTextBlockInSeveralDeltasBetweenStartAndDone() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello, world")));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("hi"), StreamOptions.defaults()));

        assertEquals(List.of(
                new Start(),
                new TextStart(0),
                new TextDelta(0, "Hell"),
                new TextDelta(0, "o, w"),
                new TextDelta(0, "orld"),
                new TextEnd(0, new TextBlock("Hello, world")),
                new Done(answer(new TextBlock("Hello, world"), StopReason.END_TURN))), events);
    }

    @Test
    void aThinkingAnswerStreamsTheThinkingBlockBeforeTheTextBlock() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().thinking("Let me see").text("Yes").build()));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("hi"), StreamOptions.defaults()));

        assertEquals(List.of(
                new Start(),
                new ThinkingStart(0),
                new ThinkingDelta(0, "Let "),
                new ThinkingDelta(0, "me "),
                new ThinkingDelta(0, "see"),
                new ThinkingEnd(0, new ThinkingBlock("Let me see")),
                new TextStart(1),
                new TextDelta(1, "Y"),
                new TextDelta(1, "e"),
                new TextDelta(1, "s"),
                new TextEnd(1, new TextBlock("Yes")),
                new Done(answer(List.of(new ThinkingBlock("Let me see"), new TextBlock("Yes")), StopReason.END_TURN))),
                events);
    }

    @Test
    void aThinkingSignatureReachesTheThinkingEndAndTheFinalMessage() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().thinking("Hmm").thinkingSignature("sig-Zm9v").text("Ok").build()));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("hi"), StreamOptions.defaults()));

        ThinkingBlock signed = new ThinkingBlock("Hmm", "sig-Zm9v");
        assertEquals(List.of(
                new Start(),
                new ThinkingStart(0),
                new ThinkingDelta(0, "H"),
                new ThinkingDelta(0, "m"),
                new ThinkingDelta(0, "m"),
                new ThinkingEnd(0, signed),
                new TextStart(1),
                new TextDelta(1, "O"),
                new TextDelta(1, "k"),
                new TextEnd(1, new TextBlock("Ok")),
                new Done(answer(List.of(signed, new TextBlock("Ok")), StopReason.END_TURN))), events);
    }

    @Test
    void aSignatureWithNoThinkingBlockOpenFailsTheStream() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Ok").thinkingSignature("sig-Zm9v").build()));

        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.stream(MODEL, ask("hi"), StreamOptions.defaults()).complete());

        assertEquals(ErrorKind.FATAL, e.kind());
        assertTrue(e.getMessage().contains("no thinking block is open"), e.getMessage());
    }

    @Test
    void aToolCallAnswerStreamsItsRawArgumentsAsDeltasAndStopsForToolUse() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}").build()));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("read a.txt"), StreamOptions.defaults()));

        ToolCall call = new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}");
        assertEquals(List.of(
                new Start(),
                new ToolCallStart(0, "call_1", "read_file"),
                new ToolCallDelta(0, "{\"path"),
                new ToolCallDelta(0, "\":\"a."),
                new ToolCallDelta(0, "txt\"}"),
                new ToolCallEnd(0, call),
                new Done(answer(List.of(call), StopReason.TOOL_USE))), events);
    }

    @Test
    void completeDrainsTheStreamIntoTheFinalAssistantMessage() throws ProviderException {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .thinking("Two files.")
                .text("Reading both.")
                .toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")
                .toolCall("call_2", "read_file", "{\"path\":\"b.txt\"}")
                .build()));

        AssistantMessage message = provider.stream(MODEL, ask("read a and b"), StreamOptions.defaults()).complete();

        assertEquals(answer(List.of(
                new ThinkingBlock("Two files."),
                new TextBlock("Reading both."),
                new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolCall("call_2", "read_file", "{\"path\":\"b.txt\"}")), StopReason.TOOL_USE), message);
    }

    @ParameterizedTest
    @CsvSource({
            "RETRYABLE, ERROR",
            "CONTEXT_OVERFLOW, ERROR",
            "ABORTED, ABORTED",
            "FATAL, ERROR"})
    void aScriptedErrorReachesCompleteAsAnExceptionOfItsKindCarryingThePartial(ErrorKind kind, StopReason partialStop) {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Half an answer").fail(kind, "scripted failure")));

        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.stream(MODEL, ask("hi"), StreamOptions.defaults()).complete());

        assertEquals(kind, e.kind());
        assertEquals("scripted failure", e.getMessage());
        assertEquals(answer(new TextBlock("Half an answer"), partialStop), e.partial());
    }

    @Test
    void anErrorCutsTheOpenBlockShortAndIsTheLastEvent() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Half").fail(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long")));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("hi"), StreamOptions.defaults()));

        assertEquals(List.of(
                new Start(),
                new TextStart(0),
                new TextDelta(0, "Ha"),
                new TextDelta(0, "l"),
                new TextDelta(0, "f"),
                new Failed(ErrorKind.CONTEXT_OVERFLOW, "prompt is too long",
                        answer(new TextBlock("Half"), StopReason.ERROR))), events);
    }

    @Test
    void cancellingMidStreamEndsItWithAnAbortedErrorCarryingThePartialAnswer() {
        CancellationToken cancellation = new CancellationToken();
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("Hello, world")));
        Iterator<StreamEvent> stream = provider.stream(MODEL, ask("hi"), new StreamOptions(cancellation)).iterator();
        List<StreamEvent> events = new ArrayList<>(List.of(stream.next(), stream.next(), stream.next()));

        cancellation.cancel();
        stream.forEachRemaining(events::add);

        assertEquals(List.of(
                new Start(),
                new TextStart(0),
                new TextDelta(0, "Hell"),
                new Failed(ErrorKind.ABORTED, "request cancelled", answer(new TextBlock("Hell"), StopReason.ABORTED))),
                events);
    }

    @Test
    void everyRequestIsRecordedInOrderAsASnapshotTheCallerCannotChangeAfterwards() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("one"), FakeResponse.text("two")));
        Model otherModel = new Model("other-model", "fake", "fake", 64_000, 4_096, Set.of(Model.Feature.REASONING));
        List<Message> conversation = new ArrayList<>(List.of(UserMessage.text("first")));
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        Context first = new Context("system", conversation, List.of(new ToolDefinition("read_file", "Reads", schema)));

        drain(provider.stream(MODEL, first, StreamOptions.defaults()));
        conversation.add(UserMessage.text("added later"));
        schema.put("type", "changed later");
        drain(provider.stream(otherModel, ask("second"), StreamOptions.defaults()));

        ObjectNode recordedSchema = JsonNodeFactory.instance.objectNode().put("type", "object");
        assertEquals(List.of(
                new FakeProvider.Request(MODEL, new Context("system", List.of(UserMessage.text("first")),
                        List.of(new ToolDefinition("read_file", "Reads", recordedSchema)))),
                new FakeProvider.Request(otherModel, ask("second"))), provider.requests());
    }

    @Test
    void aFixedScriptThatRunsOutEndsTheNextStreamWithAFatalErrorSayingSo() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.text("the only answer")));
        drain(provider.stream(MODEL, ask("one"), StreamOptions.defaults()));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("two"), StreamOptions.defaults()));

        assertEquals(List.of(
                new Start(),
                new Failed(ErrorKind.FATAL,
                        "the fake provider's script ran out: request 2 has no response (the script has 1)",
                        answer(List.of(), StopReason.ERROR))), events);
        assertEquals(2, provider.requests().size(), "the unanswered request is still recorded");
    }

    @Test
    void aRespondingProviderChoosesEachAnswerFromTheRequestItReceives() throws ProviderException {
        FakeProvider provider = FakeProvider.responding(request -> request.context().messages().size() == 1
                ? FakeResponse.builder().toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}").build()
                : FakeResponse.text("answered after " + request.context().messages().size() + " messages"));

        AssistantMessage first = provider.stream(MODEL, ask("read a.txt"), StreamOptions.defaults()).complete();
        Context followUp = new Context("You are a test.", List.of(UserMessage.text("read a.txt"), first,
                new ToolResultMessage("call_1", "read_file", List.of(new TextBlock("hello")), false)), List.of());
        AssistantMessage second = provider.stream(MODEL, followUp, StreamOptions.defaults()).complete();

        assertEquals(StopReason.TOOL_USE, first.stopReason());
        assertEquals("answered after 3 messages", second.text());
    }

    @Test
    void aResponderThatThrowsFailsTheStreamRatherThanTheCaller() {
        FakeProvider provider = FakeProvider.responding(request -> {
            throw new IllegalStateException("no answer for this request");
        });

        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.stream(MODEL, ask("hi"), StreamOptions.defaults()).complete());

        assertEquals(ErrorKind.FATAL, e.kind());
        assertTrue(e.getMessage().contains("no answer for this request"), e.getMessage());
    }

    @Test
    void retryAndUsageEventsArriveWhereScriptedAndTheLastUsageIsTheMessages() {
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .retry(1, 3, "429 Too Many Requests")
                .usage(new Usage(120, 0, 100))
                .text("Hi")
                .usage(new Usage(120, 7, 100))
                .build()));

        List<StreamEvent> events = drain(provider.stream(MODEL, ask("hi"), StreamOptions.defaults()));

        assertEquals(List.of(
                new Start(),
                new Retry(1, 3, "429 Too Many Requests"),
                new UsageUpdate(new Usage(120, 0, 100)),
                new TextStart(0),
                new TextDelta(0, "H"),
                new TextDelta(0, "i"),
                new UsageUpdate(new Usage(120, 7, 100)),
                new TextEnd(0, new TextBlock("Hi")),
                new Done(new AssistantMessage(List.of(new TextBlock("Hi")), StopReason.END_TURN,
                        new Usage(120, 7, 100), "fake", "fake-model"))), events);
    }

    @Test
    void anExplicitStopReasonReplacesTheDerivedOne() throws ProviderException {
        FakeProvider provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().text("Cut off mid-sent").stopReason(StopReason.LENGTH).build()));

        AssistantMessage message = provider.stream(MODEL, ask("hi"), StreamOptions.defaults()).complete();

        assertEquals(answer(new TextBlock("Cut off mid-sent"), StopReason.LENGTH), message);
    }

    private static Context ask(String question) {
        return new Context("You are a test.", List.of(UserMessage.text(question)), List.of());
    }

    private static AssistantMessage answer(TextBlock block, StopReason stopReason) {
        return answer(List.of(block), stopReason);
    }

    private static AssistantMessage answer(List<AssistantContent> content, StopReason stopReason) {
        return new AssistantMessage(content, stopReason, Usage.ZERO, "fake", "fake-model");
    }

    private static List<StreamEvent> drain(AssistantStream stream) {
        List<StreamEvent> events = new ArrayList<>();
        try (stream) {
            for (StreamEvent event : stream) {
                events.add(event);
            }
        }
        return events;
    }
}
