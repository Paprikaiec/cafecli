package com.cafecli.ai.openai;

import com.cafecli.ai.*;
import com.cafecli.ai.message.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Model MODEL = new Model("deepseek-v4-flash", "deepseek", "openai-completions",
            1_000_000, 8192, Set.of(Model.Feature.REASONING, Model.Feature.PROMPT_CACHING,
            Model.Feature.TOOL_CALL_STREAMING));
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void neutralHistoryAndToolsUseTheWireFormatAndThinkingReturnsWithTheNextToolResult() throws Exception {
        String url = serve(fixture("deepseek-tools.sse"));
        var provider = new DeepSeekProvider("fixture-key", url);
        ToolDefinition tool = new ToolDefinition("read_file", "Read a file", JSON.readTree("""
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
                """));
        UserMessage user = UserMessage.text("Read a.txt");
        List<StreamEvent> events = new ArrayList<>();
        try (var stream = provider.stream(MODEL, new Context("You help.", List.of(user), List.of(tool)),
                StreamOptions.defaults())) {
            stream.forEach(events::add);
        }
        assertEquals(new StreamEvent.Start(), events.getFirst());
        var answer = assertInstanceOf(StreamEvent.Done.class, events.getLast()).message();
        assertEquals(List.of(new ThinkingBlock("Need the file."), new TextBlock("Checking."),
                new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")), answer.content());
        assertEquals(StopReason.TOOL_USE, answer.stopReason());
        assertEquals(new Usage(120, 20, 90), answer.usage());
        assertTrue(events.contains(new StreamEvent.ToolCallDelta(2, "{\"path\":")));
        assertTrue(events.contains(new StreamEvent.ToolCallDelta(2, "\"a.txt\"}")));
        assertEquals(JSON.readTree("""
                {"model":"deepseek-v4-flash","stream":true,"messages":[
                  {"role":"system","content":"You help."},{"role":"user","content":"Read a.txt"}],
                 "tools":[{"type":"function","function":{"name":"read_file","description":"Read a file",
                   "parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}]}
                """), requests.getFirst());
        var result = new ToolResultMessage("call_1", "read_file", List.of(new TextBlock("file bytes")), false);
        provider.stream(MODEL, new Context("You help.", List.of(user, answer, result), List.of(tool)),
                StreamOptions.defaults()).complete();
        assertEquals(JSON.readTree("""
                {"role":"assistant","content":"Checking.","reasoning_content":"Need the file.",
                 "tool_calls":[{"id":"call_1","type":"function","function":
                 {"name":"read_file","arguments":"{\\\"path\\\":\\\"a.txt\\\"}"}}]}
                """), requests.get(1).path("messages").get(2));
        assertEquals(JSON.readTree("""
                {"role":"tool","tool_call_id":"call_1","content":"file bytes"}
                """), requests.get(1).path("messages").get(3));
        assertEquals(List.of("Bearer fixture-key", "Bearer fixture-key"), authorizations);
    }

    @Test
    void indexedCallsCanInterleaveBeforeTheirIdsArriveAndContentCannotCorruptTheirArguments() throws Exception {
        var provider = new DeepSeekProvider("fixture-key", serve(fixture("deepseek-interleaved.sse")));
        List<StreamEvent> events = new ArrayList<>();
        try (var stream = provider.stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults())) {
            stream.forEach(events::add);
        }
        var answer = assertInstanceOf(StreamEvent.Done.class, events.getLast()).message();
        assertEquals(List.of(new ThinkingBlock("Plan."), new TextBlock(" \n"),
                new ToolCall("late_read", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolCall("late_write", "write_file", "{\"path\":\"b.txt\",\"content\":\"x\"}")), answer.content());
        assertEquals(new Usage(30, 8, 12), answer.usage());
        assertEquals(List.of(new StreamEvent.ToolCallDelta(2, "{\"path\":"),
                        new StreamEvent.ToolCallDelta(2, "\"a.txt\"}"),
                        new StreamEvent.ToolCallDelta(3, "{\"path\":"),
                        new StreamEvent.ToolCallDelta(3, "\"b.txt\",\"content\":\"x\"}")),
                events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance).toList());
    }

    @Test
    void userAndToolImagesUseDataUrlsWhileToolStatusRemainsInTheNeutralModel() throws Exception {
        var provider = new DeepSeekProvider("fixture-key", serve("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"));
        provider.stream(MODEL, new Context("", List.of(
                new UserMessage(List.of(new TextBlock("look"), new ImageBlock("AQID", "image/jpeg"))),
                new ToolResultMessage("c1", "read_file", List.of(new TextBlock("image"),
                        new ImageBlock("BAUG", "image/png")), true)), List.of()), StreamOptions.defaults()).complete();
        assertEquals(JSON.readTree("""
                [{"role":"user","content":[{"type":"text","text":"look"},
                  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]},
                 {"role":"tool","tool_call_id":"c1","content":[{"type":"text","text":"image"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,BAUG"}}]}]
                """), requests.getFirst().path("messages"));
        assertFalse(requests.getFirst().has("tools"));
        assertFalse(requests.getFirst().has("temperature"));
        assertFalse(requests.getFirst().has("max_tokens"));
    }

    @Test
    void allExistingCachedTokenFieldsProduceCumulativeUsage() throws Exception {
        String url = serve("""
                data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":2,"cached_tokens":10}}
                data: {"choices":[],"usage":{"prompt_cache_hit_tokens":20}}
                data: {"choices":[],"usage":{"input_cache_hit_tokens":30}}
                data: {"choices":[],"usage":{"prompt_tokens_details":{"cached_tokens":40}}}
                data: {"choices":[],"usage":{"input_tokens_details":{"cached_tokens":50}}}
                data: {"choices":[{"message":{"reasoning_details":[{"text":"a"},{"content":"b"}],"content":"ok"},"finish_reason":"stop"}]}
                data: [DONE]
                """);
        List<StreamEvent> events = new ArrayList<>();
        new DeepSeekProvider("fixture-key", url).stream(MODEL, new Context("", List.of(), List.of()),
                StreamOptions.defaults()).forEach(events::add);
        assertEquals(List.of(new Usage(100, 2, 10), new Usage(100, 2, 20), new Usage(100, 2, 30),
                new Usage(100, 2, 40), new Usage(100, 2, 50)), events.stream()
                .filter(StreamEvent.UsageUpdate.class::isInstance).map(StreamEvent.UsageUpdate.class::cast)
                .map(StreamEvent.UsageUpdate::usage).toList());
        assertEquals(List.of(new ThinkingBlock("ab"), new TextBlock("ok")),
                assertInstanceOf(StreamEvent.Done.class, events.getLast()).message().content());
    }

    @Test
    void aBrokenHttpConnectionIsRetryableWithoutRetryingOrLeakingTheCredential() throws Exception {
        String url = serveHandler(exchange -> { exchange.close(); });
        ProviderException error = assertThrows(ProviderException.class, () -> new DeepSeekProvider("secret-key", url)
                .stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.RETRYABLE, error.kind());
        assertFalse(error.getMessage().contains("secret-key"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAbortsBlockedHttpBeforeHeadersAndDuringTheBody(boolean sendHeaders) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch text = new CountDownLatch(1);
        String url = serveHandler(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            reached.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var cancellation = new CancellationToken();
        CompletableFuture<StreamEvent.Failed> terminal = new CompletableFuture<>();
        Thread consumer = Thread.ofVirtual().start(() -> {
            try (var stream = new DeepSeekProvider("fixture-key", url).stream(MODEL,
                    new Context("", List.of(), List.of()), new StreamOptions(cancellation))) {
                for (StreamEvent event : stream) {
                    if (event instanceof StreamEvent.TextDelta) text.countDown();
                    if (event instanceof StreamEvent.Failed failed) terminal.complete(failed);
                }
            } catch (Throwable e) { terminal.completeExceptionally(e); }
        });
        try {
            assertTrue(reached.await(2, TimeUnit.SECONDS));
            if (sendHeaders) assertTrue(text.await(2, TimeUnit.SECONDS));
            // Seeing a delta alone can race the stream's next cancellation check. This consumer has no waits
            // after that callback except HTTP I/O, so wait for its virtual thread to park in the next read.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            Thread.State state = consumer.getState();
            while (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING
                    && consumer.isAlive() && System.nanoTime() < deadline) {
                Thread.yield();
                state = consumer.getState();
            }
            assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING,
                    "HTTP consumer must be blocked before cancellation; state=" + state);
            cancellation.cancel();
            var failed = terminal.get(2, TimeUnit.SECONDS);
            assertEquals(ErrorKind.ABORTED, failed.kind());
            assertEquals(sendHeaders ? "visible" : "", failed.partial().text());
            consumer.join(1000);
            assertFalse(consumer.isAlive(), "cancelled HTTP consumer must exit");
            assertDoesNotThrow(cancellation::cancel);
        } finally {
            release.countDown();
            cancellation.cancel();
            server.stop(0);
            consumer.join(1000);
        }
    }

    @ParameterizedTest
    @CsvSource({"stop,END_TURN", "length,LENGTH", "tool_calls,TOOL_USE", "function_call,TOOL_USE"})
    void finishReasonsKeepTheirNeutralMeaning(String wire, StopReason expected) throws Exception {
        String url = serve("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\""
                + wire + "\"}]}\n\ndata: [DONE]\n");
        assertEquals(expected, new DeepSeekProvider("fixture-key", url)
                .stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults()).complete().stopReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"content_filter", "unknown"})
    void rejectedOrUnknownFinishReasonsFailWithTheDeliveredPartial(String reason) throws Exception {
        String url = serve("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\""
                + reason + "\"}]}\n\ndata: [DONE]\n");
        ProviderException failed = assertThrows(ProviderException.class, () -> new DeepSeekProvider("fixture-key", url)
                .stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.FATAL, failed.kind());
        assertEquals("partial", failed.partial().text());
    }

    @ParameterizedTest
    @CsvSource({"401,FATAL", "429,RETRYABLE", "503,RETRYABLE"})
    void httpFailuresAreTypedAndDoNotEchoTheResponseBody(int status, ErrorKind expected) throws Exception {
        String url = serveHandler(exchange -> {
            byte[] body = "do not log credentials: fixture-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
        });
        ProviderException failed = assertThrows(ProviderException.class, () -> new DeepSeekProvider("fixture-key", url)
                .stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults()).complete());
        assertEquals(expected, failed.kind());
        assertEquals("HTTP " + status, failed.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"data: [DONE]", "data: {broken}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"read_file\"}}]},\"finish_reason\":\"tool_calls\"}]}\ndata: [DONE]"})
    void malformedOrTruncatedStreamsCannotBecomeSuccessfulAnswers(String sse) throws Exception {
        String url = serve(sse);
        ProviderException failed = assertThrows(ProviderException.class, () -> new DeepSeekProvider("fixture-key", url)
                .stream(MODEL, new Context("", List.of(), List.of()), StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.FATAL, failed.kind());
    }

    private String serve(String sse) throws Exception {
        return serveHandler(exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
    }

    private String serveHandler(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    private static String fixture(String name) throws Exception {
        try (var input = DeepSeekProviderTest.class.getResourceAsStream("/ai/" + name)) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
