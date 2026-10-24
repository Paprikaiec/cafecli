package com.cafecli.app;

import com.cafecli.harness.SubmitResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveInputCompositionTest {
    @TempDir Path root;

    @Test void localFilesAreExpandedBeforeUserPromptSubmitInTheActualInteractiveComposition() throws Exception {
        Path home = Files.createDirectories(root.resolve("home/.cafecli")).getParent();
        Files.writeString(root.resolve("note.txt"), "LOCAL_FILE_BODY");
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"text\":\"must not run\"}]}");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(home.resolve(".cafecli/hooks.json"), json.writeValueAsString(Map.of("UserPromptSubmit", List.of(
                Map.of("hooks", List.of(Map.of("type", "command", "command",
                        "input=$(cat); case \"$input\" in *LOCAL_FILE_BODY*) printf expanded-local >&2; exit 2;; esac")))))));
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", "script.json",
                "cafecli.hook.user_prompt_submit.enabled", "true"), Map.of(), home, root));
        try (var session = Composition.interactiveSession(config, ignored -> {}, ignored -> {})) {
            assertEquals("expanded-local", assertInstanceOf(SubmitResult.Blocked.class, session.submit("Read @note.txt")).reason());
            session.awaitIdle();
            assertTrue(session.log().isEmpty());
        }
    }
    @Test void resourceLocalAndSubmitGateComposeForOrdinaryBusyAndBlockedInputs() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path home = Files.createDirectories(root.resolve("home/.cafecli")).getParent();
        Files.createDirectories(root.resolve(".cafecli"));
        Files.writeString(root.resolve("note.txt"), "LOCAL_BODY");
        Path capture = root.resolve("submitted.jsonl");
        writeHook(home, "input=$(cat); printf '%s\\n' \"$input\" >> '" + capture
                + "'; case \"$input\" in *BLOCK_ME*) printf blocked-expanded >&2; exit 2;; esac");
        Files.writeString(root.resolve("script.json"), """
                {"responses":[{"toolCalls":[{"id":"gate","name":"mcp__demo__gate","arguments":{}}]}, {"text":"done"}]}
                """);
        try (var server = resourceServer(reads, entered, release)) {
            Files.writeString(root.resolve(".cafecli/mcp.json"), json.writeValueAsString(Map.of("mcpServers",
                    Map.of("demo", Map.of("url", server.url("/mcp").toString())))));
            try (var session = Composition.interactiveSession(config(home), ignored -> {}, ignored -> {})) {
                try {
                    assertInstanceOf(SubmitResult.Queued.class, session.submit("ordinary @demo:file://guide"));
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("busy @demo:file://guide"));
                    assertEquals("blocked-expanded", assertInstanceOf(SubmitResult.Blocked.class,
                            session.submit("BLOCK_ME @demo:file://guide")).reason());
                } finally { release.countDown(); }
                session.awaitIdle();
                var user = session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance)
                        .map(com.cafecli.core.log.UserEntry.class::cast).findFirst().orElseThrow();
                var steering = session.log().stream().filter(com.cafecli.harness.context.Steering.class::isInstance)
                        .map(com.cafecli.harness.context.Steering.class::cast).findFirst().orElseThrow();
                assertTrue(user.message().content().toString().contains("LOCAL_BODY"));
                assertTrue(steering.text().contains("RESOURCE_BODY"));
                assertTrue(steering.text().contains("LOCAL_BODY"));
                assertFalse(session.log().toString().contains("BLOCK_ME"));
                assertEquals(3, reads.get());
                var submissions = Files.readAllLines(capture);
                assertEquals(3, submissions.size());
                for (String submission : submissions) {
                    assertTrue(submission.contains("RESOURCE_BODY")); assertTrue(submission.contains("LOCAL_BODY"));
                }
            }
        }
    }

    @Test void preparationRacingTheFinalRunBoundaryIsNeitherLostNorRepeated() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var releaseRun = new java.util.concurrent.CountDownLatch(1);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Path home = Files.createDirectories(root.resolve("home/.cafecli")).getParent();
        Files.createDirectories(root.resolve(".cafecli"));
        Files.writeString(root.resolve("note.txt"), "LOCAL_BODY");
        Files.writeString(root.resolve("script.json"), "{\"responses\":[{\"text\":\"first\"},{\"text\":\"second\"}]}");
        Path preparing = root.resolve("preparing"), releasePreparation = root.resolve("release-preparation"), capture = root.resolve("submitted.jsonl");
        writeHook(home, "input=$(cat); printf '%s\\n' \"$input\" >> '" + capture
                + "'; case \"$input\" in *RACE*) touch '" + preparing + "'; while [ ! -f '" + releasePreparation
                + "' ]; do sleep 0.01; done;; esac");
        try (var server = resourceServer(reads, new java.util.concurrent.CountDownLatch(0), new java.util.concurrent.CountDownLatch(0))) {
            Files.writeString(root.resolve(".cafecli/mcp.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    Map.of("mcpServers", Map.of("demo", Map.of("url", server.url("/mcp").toString())))));
            try (var session = Composition.interactiveSession(config(home), ignored -> {}, ignored -> {})) {
                var ends = new java.util.concurrent.atomic.AtomicInteger();
                var finishedFirst = new java.util.concurrent.CountDownLatch(1);
                session.subscribeCompletion(result -> finishedFirst.countDown());
                session.subscribe(event -> {
                    if (event instanceof com.cafecli.core.AgentEvent.RunEnd && ends.getAndIncrement() == 0) {
                        entered.countDown(); await(releaseRun);
                    }
                });
                var submission = new java.util.concurrent.atomic.AtomicReference<SubmitResult>();
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread input = Thread.ofVirtual().unstarted(() -> {
                    try { submission.set(session.submit("RACE @demo:file://guide")); }
                    catch (Throwable thrown) { failure.set(thrown); }
                });
                try {
                    session.submit("first"); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    input.start(); waitForFile(preparing);
                    releaseRun.countDown();
                    assertTrue(finishedFirst.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    Files.writeString(releasePreparation, "go");
                    input.join(5000); assertFalse(input.isAlive()); assertNull(failure.get());
                    assertTrue(submission.get() instanceof SubmitResult.Queued || submission.get() instanceof SubmitResult.SteeringAccepted);
                    session.awaitIdle();
                    assertEquals(1, reads.get());
                    assertEquals(2, Files.readAllLines(capture).size());
                    var users = session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance)
                            .map(com.cafecli.core.log.UserEntry.class::cast).toList();
                    assertEquals(2, users.size());
                    assertTrue(users.getLast().message().content().toString().contains("LOCAL_BODY"));
                    assertTrue(users.getLast().message().content().toString().contains("RESOURCE_BODY"));
                } finally {
                    releaseRun.countDown(); Files.writeString(releasePreparation, "go");
                    if (input.getState() != Thread.State.NEW) { input.join(5000); assertFalse(input.isAlive()); }
                }
            }
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static void waitForFile(Path path) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(path));
    }

    private com.cafecli.harness.config.HarnessConfig config(Path home) {
        return ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", "script.json",
                "cafecli.hook.user_prompt_submit.enabled", "true"), Map.of(), home, root));
    }
    private void writeHook(Path home, String command) throws Exception {
        Files.writeString(home.resolve(".cafecli/hooks.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("UserPromptSubmit", List.of(Map.of("hooks", List.of(Map.of("type", "command", "command", command)))))));
    }
    private okhttp3.mockwebserver.MockWebServer resourceServer(java.util.concurrent.atomic.AtomicInteger reads,
            java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) throws Exception {
        var server = new okhttp3.mockwebserver.MockWebServer();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                try {
                    var body = json.readTree(request.getBody().readUtf8());
                    if (!body.has("id")) return new okhttp3.mockwebserver.MockResponse().setResponseCode(202);
                    Object result = switch (body.path("method").asText()) {
                        case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of(), "resources", Map.of()));
                        case "tools/list" -> Map.of("tools", List.of(Map.of("name", "gate", "description", "gate", "inputSchema", Map.of("type", "object"))));
                        case "resources/list" -> Map.of("resources", List.of(Map.of("uri", "file://guide", "name", "Guide")));
                        case "resources/read" -> {
                            reads.incrementAndGet();
                            yield Map.of("contents", List.of(Map.of("uri", "file://guide", "text", "RESOURCE_BODY @note.txt")));
                        }
                        case "tools/call" -> {
                            entered.countDown();
                            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            yield Map.of("content", List.of(Map.of("type", "text", "text", "gate done")));
                        }
                        default -> Map.of();
                    };
                    return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", body.get("id"), "result", result)));
                } catch (Exception e) { return new okhttp3.mockwebserver.MockResponse().setResponseCode(500); }
            }
        });
        server.start();
        return server;
    }

}
