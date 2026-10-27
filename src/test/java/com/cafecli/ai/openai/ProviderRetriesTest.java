package com.cafecli.ai.openai;

import com.cafecli.ai.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRetriesTest {
    private static final Model MODEL = new Model("deepseek-v4-flash", "deepseek", "openai-completions",
            1000000, 8192, Set.of());
    private static final Context CONTEXT = new Context("", List.of(), List.of());
    private static final String SUCCESS = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n";
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "glm", "step", "kimi", "freellmapi"})
    void rateLimitThenSuccessAnnouncesTheNextAttemptAndCompletesNormally(String name) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            int status = requests.incrementAndGet() == 1 ? 429 : 200;
            byte[] bytes = (status == 200 ? SUCCESS : "secret fixture-key").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        List<StreamEvent> events = new ArrayList<>();
        provider(name, url).stream(model(name), CONTEXT, StreamOptions.defaults()).forEach(events::add);
        assertEquals(2, requests.get());
        assertEquals(List.of(new StreamEvent.Retry(2, 3, "HTTP 429")),
                events.stream().filter(StreamEvent.Retry.class::isInstance).toList());
        assertEquals("answer", assertInstanceOf(StreamEvent.Done.class, events.getLast()).message().text());
        assertTrue(events.indexOf(events.stream().filter(StreamEvent.Retry.class::isInstance).findFirst().orElseThrow())
                < events.indexOf(events.stream().filter(StreamEvent.TextStart.class::isInstance).findFirst().orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "glm", "step", "kimi", "freellmapi"})
    void knownGlm1261AndKimiMessageOrAssumedOpenAiContextLengthCodeAreOverflow(String provider) throws Exception {
        // GLM documents code 1261; Kimi documents invalid_request_error with this specific message.
        // DeepSeek, Step and the FreeLLMAPI gateway are assumed to use the OpenAI context_length_exceeded code;
        // no captured production error body is available for those three adapters.
        String error = switch (provider) {
            case "glm" -> "{\"error\":{\"code\":\"1261\",\"message\":\"sensitive body\"}}";
            case "kimi" -> "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Input token length too long\"}}";
            default -> "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"sensitive body\"}}";
        };
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        ProviderException failed = assertThrows(ProviderException.class, () -> provider(provider, url)
                .stream(model(provider), CONTEXT, StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, failed.kind());
        assertEquals("HTTP 400", failed.getMessage());
        assertEquals(1, requests.get(), "overflow recovery belongs to the caller, not adapter retries");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "glm", "step", "kimi", "freellmapi"})
    void serviceFailuresHaveABoundedThreeAttemptBudget(String name) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        List<StreamEvent> events = new ArrayList<>();
        provider(name, url).stream(model(name), CONTEXT, StreamOptions.defaults()).forEach(events::add);
        assertEquals(3, requests.get());
        assertEquals(List.of(new StreamEvent.Retry(2, 3, "HTTP 503"), new StreamEvent.Retry(3, 3, "HTTP 503")),
                events.stream().filter(StreamEvent.Retry.class::isInstance).toList());
        assertEquals(ErrorKind.RETRYABLE, assertInstanceOf(StreamEvent.Failed.class, events.getLast()).kind());
    }

    @Test void aClosedConnectionIsRetriedExplicitlyAndThenSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) { exchange.close(); return; }
            byte[] bytes = SUCCESS.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        List<StreamEvent> events = new ArrayList<>();
        new DeepSeekProvider("fixture-key", url).stream(MODEL, CONTEXT, StreamOptions.defaults()).forEach(events::add);
        assertEquals(2, requests.get());
        assertEquals(List.of(new StreamEvent.Retry(2, 3, "HTTP transport failed")),
                events.stream().filter(StreamEvent.Retry.class::isInstance).toList());
        assertEquals("answer", assertInstanceOf(StreamEvent.Done.class, events.getLast()).message().text());
    }

    @Test void cancellationWakesTheBackoffWaitWithoutSendingAnotherRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        CancellationToken token = new CancellationToken();
        CountDownLatch lastRetry = new CountDownLatch(1);
        CompletableFuture<StreamEvent.Failed> terminal = new CompletableFuture<>();
        Thread consumer = Thread.ofVirtual().start(() -> {
            try (var stream = new DeepSeekProvider("fixture-key", url).stream(MODEL, CONTEXT, new StreamOptions(token))) {
                for (StreamEvent event : stream) {
                    if (event instanceof StreamEvent.Retry retry && retry.attempt() == 3) lastRetry.countDown();
                    if (event instanceof StreamEvent.Failed failed) terminal.complete(failed);
                }
            } catch (Throwable failure) { terminal.completeExceptionally(failure); }
        });
        try {
            assertTrue(lastRetry.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (consumer.getState() != Thread.State.TIMED_WAITING && consumer.isAlive()
                    && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING, consumer.getState(), "consumer must be in the backoff wait");
            assertEquals(2, requests.get());
            token.cancel();
            assertEquals(ErrorKind.ABORTED, terminal.get(300, TimeUnit.MILLISECONDS).kind());
            consumer.join(1000);
            assertFalse(consumer.isAlive());
            assertEquals(2, requests.get());
        } finally { token.cancel(); consumer.join(1000); }
    }

    @Test void malformedSseCannotLeakTheResponseOrCredentialInDiagnostics() throws Exception {
        String url = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "data: {\"choices\":secret_fixture_key}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        ProviderException failed = assertThrows(ProviderException.class, () -> new DeepSeekProvider("secret-fixture-key", url)
                .stream(MODEL, CONTEXT, StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.FATAL, failed.kind());
        assertFalse(failed.getMessage().contains("secret"), failed.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "thinking", "buffered-tool"})
    void brokenBodiesRetryOnlyBeforeContentAndDiscardUnobservedToolChunks(String kind) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String delta = switch (kind) {
            case "thinking" -> "{\"reasoning_content\":\"partial\"}";
            case "buffered-tool" -> "{\"tool_calls\":[{\"index\":0,\"id\":\"old\",\"function\":{\"name\":\"old_tool\",\"arguments\":\"{}\"}}]}";
            default -> "{\"content\":\"partial\"}";
        };
        String url = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean broken = requests.incrementAndGet() == 1;
            byte[] bytes = (broken ? "data: {\"choices\":[{\"delta\":" + delta + "}]}\n\n" : SUCCESS)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length + (broken ? 100 : 0));
            try { exchange.getResponseBody().write(bytes); exchange.getResponseBody().flush(); }
            finally { exchange.close(); }
        });
        List<StreamEvent> events = new ArrayList<>();
        new DeepSeekProvider("fixture-key", url).stream(MODEL, CONTEXT, StreamOptions.defaults()).forEach(events::add);
        if (kind.equals("buffered-tool")) {
            var message = assertInstanceOf(StreamEvent.Done.class, events.getLast()).message();
            assertEquals("answer", message.text());
            assertTrue(message.toolCalls().isEmpty());
            assertEquals(2, requests.get());
            assertEquals(1, events.stream().filter(StreamEvent.Retry.class::isInstance).count());
        } else {
            var failed = assertInstanceOf(StreamEvent.Failed.class, events.getLast());
            assertEquals(ErrorKind.RETRYABLE, failed.kind());
            if (kind.equals("text")) assertEquals("partial", failed.partial().text());
            else assertEquals(List.of(new com.cafecli.ai.message.ThinkingBlock("partial")), failed.partial().content());
            assertEquals(1, requests.get(), "delivered content cannot be replayed without a reset contract");
            assertTrue(events.stream().noneMatch(StreamEvent.Retry.class::isInstance));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"glm", "kimi", "deepseek", "step", "freellmapi"})
    void ordinaryInvalidParametersAndUnknownErrorBodiesRemainFatal(String name) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String error = "{\"error\":{\"code\":\"1210\",\"type\":\"invalid_request_error\",\"message\":\"Invalid parameter fixture-key\"}}";
        String url = serve(exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        ProviderException failed = assertThrows(ProviderException.class, () -> provider(name, url)
                .stream(model(name), CONTEXT, StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.FATAL, failed.kind());
        assertEquals("HTTP 400", failed.getMessage());
        assertEquals(1, requests.get());
    }

    @Test void cancellationAlsoAbortsAProviderErrorBodyRead() throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        String url = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().write("{\"error\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var token = new CancellationToken();
        var terminal = new CompletableFuture<ErrorKind>();
        Thread consumer = Thread.ofVirtual().start(() -> {
            try (var stream = new DeepSeekProvider("fixture-key", url).stream(MODEL, CONTEXT, new StreamOptions(token))) {
                stream.complete(); terminal.completeExceptionally(new AssertionError("expected abortion"));
            } catch (ProviderException failed) { terminal.complete(failed.kind()); }
            catch (Throwable failed) { terminal.completeExceptionally(failed); }
        });
        try {
            assertTrue(headers.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (consumer.getState() != Thread.State.WAITING && consumer.getState() != Thread.State.TIMED_WAITING
                    && consumer.isAlive() && System.nanoTime() < deadline) Thread.yield();
            assertTrue(consumer.getState() == Thread.State.WAITING || consumer.getState() == Thread.State.TIMED_WAITING);
            token.cancel();
            assertEquals(ErrorKind.ABORTED, terminal.get(2, TimeUnit.SECONDS));
            consumer.join(1000); assertFalse(consumer.isAlive());
        } finally { release.countDown(); token.cancel(); consumer.join(1000); }
    }

    @ParameterizedTest
    @CsvSource({"400", "401", "403", "422", "600"})
    void unrecognizedHttpFailuresOutside429And5xxStayFatal(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String url = serve(exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        ProviderException failed = assertThrows(ProviderException.class, () -> new DeepSeekProvider("fixture-key", url)
                .stream(MODEL, CONTEXT, StreamOptions.defaults()).complete());
        assertEquals(ErrorKind.FATAL, failed.kind());
        assertEquals(1, requests.get());
    }

    private static Model model(String provider) {
        return new Model("fixture-model", provider, "openai-completions", 1000000, 8192, Set.of());
    }

    private static Provider provider(String provider, String url) {
        return switch (provider) {
            case "glm" -> new GlmProvider("fixture-key", url, url, HttpTimeouts.DEFAULT);
            case "step" -> new StepProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            case "kimi" -> new KimiProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            case "freellmapi" -> new FreeLlmApiProvider("fixture-key", url, HttpTimeouts.DEFAULT);
            default -> new DeepSeekProvider("fixture-key", url);
        };
    }

    private String serve(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }
}
