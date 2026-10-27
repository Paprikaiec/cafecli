package com.cafecli.app;

import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryStatusCompositionTest {
    @TempDir Path root;

    @Test void aChildRetryRemainsTypedButDoesNotLeakIntoMainHeadlessStatus() {
        var provider = com.cafecli.ai.fake.FakeProvider.responding(request -> {
            if (request.model().id().equals("child")) return com.cafecli.ai.fake.FakeResponse.builder()
                    .retry(2, 3, "child private retry").text("child answer").build();
            if (request.context().messages().size() == 1) return com.cafecli.ai.fake.FakeResponse.builder()
                    .toolCall("spawn", "spawn_agent", "{\"description\":\"child\",\"prompt\":\"work\"}").build();
            return com.cafecli.ai.fake.FakeResponse.text("parent answer");
        });
        var factory = new com.cafecli.harness.AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(
                new com.cafecli.prompt.PromptRepository(root.resolve("user-prompts"), root.resolve("project-prompts"))),
                java.time.Duration.ofSeconds(5));
        var child = new com.cafecli.harness.AgentProfile(model("child"), com.cafecli.prompt.PromptMode.AGENT, List.of(), 3);
        var tasks = new com.cafecli.harness.tasks.SubAgentsCapability(factory, child);
        List<String> statuses = new CopyOnWriteArrayList<>();
        List<com.cafecli.core.AgentEvent> events = new CopyOnWriteArrayList<>();
        try (var session = com.cafecli.harness.AgentSession.builder().config(config()).factory(factory).model(model("main"))
                .capabilities(List.of(new HeadlessRetryStatus(statuses::add), tasks)).build()) {
            session.subscribe(events::add);
            session.submit("delegate");
        }
        assertTrue(events.stream().anyMatch(event -> event instanceof com.cafecli.core.AgentEvent.Retry
                && !event.agentId().equals("main")));
        assertTrue(statuses.isEmpty());
    }

    @Test void interruptedSessionCloseStillDrainsAcceptedStatusAndRestoresInterruption() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), closing = new CountDownLatch(1);
        List<String> statuses = new CopyOnWriteArrayList<>();
        var provider = com.cafecli.ai.fake.FakeProvider.scripted(List.of(com.cafecli.ai.fake.FakeResponse.builder()
                .retry(2, 3, "HTTP 429").text("answer").build()));
        var session = com.cafecli.harness.AgentSession.builder().config(config()).provider(provider).model(model("main"))
                .capabilities(List.of(new HeadlessRetryStatus(text -> {
                    writing.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    statuses.add(text);
                }))).build();
        CompletableFuture<Boolean> closed = null;
        try {
            session.submit("hello");
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            closed = CompletableFuture.supplyAsync(() -> {
                Thread.currentThread().interrupt(); closing.countDown();
                session.close(); return Thread.interrupted();
            });
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            var pending = closed;
            assertThrows(TimeoutException.class, () -> pending.get(150, TimeUnit.MILLISECONDS), "close must wait for accepted output");
        } finally {
            release.countDown();
            try { if (closed != null) assertTrue(closed.get(3, TimeUnit.SECONDS)); }
            finally { session.close(); }
        }
        assertEquals(List.of("Retrying model request (attempt 2/3): HTTP 429"), statuses);
    }

    @Test void aStatusSinkFailureIsReportedAtCloseAfterModelWorkCompletes() {
        var provider = com.cafecli.ai.fake.FakeProvider.scripted(List.of(com.cafecli.ai.fake.FakeResponse.builder()
                .retry(2, 3, "HTTP 429").text("answer").build()));
        var session = com.cafecli.harness.AgentSession.builder().config(config()).provider(provider).model(model("main"))
                .capabilities(List.of(new HeadlessRetryStatus(text -> { throw new IllegalArgumentException("output unavailable"); }))).build();
        try {
            session.submit("hello");
            assertEquals(2, session.log().size(), "output failure must not cancel model work");
            assertEquals("retry status output failed", assertThrows(IllegalStateException.class, session::close).getMessage());
        } finally { session.close(); }
    }

    private com.cafecli.harness.config.HarnessConfig config() {
        return ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root));
    }
    private static com.cafecli.ai.Model model(String id) {
        return new com.cafecli.ai.Model(id, "fake", "fake", 128000, 8192, java.util.Set.of());
    }

    @Test void headlessRetryStatusIsVisibleWhileTheRetriedRequestIsStillRunningAndDrainsBeforeClose() throws Exception {
        CountDownLatch visible = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> statuses = new CopyOnWriteArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                byte[] bytes = "sensitive fixture-key".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
                return;
            }
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            Path file = Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
            Files.writeString(file, "{\"defaultProvider\":\"step\",\"providers\":{\"step\":{\"apiKey\":\"fixture-key\",\"baseUrl\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "\"}}}");
            var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled", "false",
                    "cafecli.lsp.enabled", "false"), Map.of(), root.resolve("home"), root));
            try (var session = Composition.headlessSession(config, text -> { statuses.add(text); visible.countDown(); })) {
                var run = CompletableFuture.supplyAsync(() -> session.submit("hello"));
                try {
                    assertTrue(visible.await(2, TimeUnit.SECONDS), "retry must be visible before the final answer");
                    assertFalse(run.isDone());
                } finally { release.countDown(); }
                run.get(5, TimeUnit.SECONDS);
            }
            assertEquals(List.of("Retrying model request (attempt 2/3): HTTP 429"), statuses);
            assertEquals(2, requests.get());
        } finally { release.countDown(); server.stop(0); }
    }
}
