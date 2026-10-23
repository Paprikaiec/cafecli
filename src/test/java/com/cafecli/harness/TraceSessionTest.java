package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.IterationDecision;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.trace.TraceCapability;
import com.cafecli.prompt.PromptContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

class TraceSessionTest {
    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128000, 8192, Set.of());
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void interruptedSessionCloseWaitsForPendingTraceBytesAndRetainsInterruption() throws Exception {
        Path file = root.resolve("trace.jsonl");
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var outputClosed = new CountDownLatch(1);
        var realWriter = new AtomicReference<Writer>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var trace = new TraceCapability(() -> {
            Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            realWriter.set(out);
            return new Writer() {
                private boolean first = true;
                @Override public void write(char[] bytes, int offset, int length) throws IOException {
                    if (first) {
                        first = false;
                        writing.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test did not release trace output");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                    out.write(bytes, offset, length);
                }
                @Override public void flush() throws IOException { out.flush(); }
                @Override public void close() throws IOException {
                    try { out.close(); } finally { outputClosed.countDown(); }
                }
            };
        });
        Capability interruptBeforeTraceClose = new Capability() { public void attachSession(SessionRegistrar session) {
            session.onClose(() -> Thread.currentThread().interrupt());
        }};
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().text("durable answer")
                .usage(new com.cafecli.ai.message.Usage(5, 2, 1)).build()));
        var session = AgentSession.builder().config(config()).provider(provider).model(MODEL)
                .capabilities(List.of(trace, interruptBeforeTraceClose)).build();
        Thread closer = Thread.ofPlatform().unstarted(() -> {
            try { session.close(); interrupted.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable e) { failure.set(e); }
            finally { closed.countDown(); }
        });
        try {
            run(session, "question");
            closer.start();
            assertTrue(writing.await(5, TimeUnit.SECONDS), "trace output must reach the real writer");
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "session close must wait for pending output despite interruption");
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS), "session close must finish after trace output is released");
            assertNull(failure.get());
            assertTrue(interrupted.get(), "draining output must preserve the closer's interruption");
            var records = records(file);
            ((com.fasterxml.jackson.databind.node.ObjectNode) records.getLast()).put("wall_ms", 0);
            assertEquals(List.of(
                    JSON.valueToTree(Map.of("type", "system", "text", provider.requests().getFirst().context().systemPrompt())),
                    JSON.readTree("{\"type\":\"user\",\"text\":\"question\"}"),
                    JSON.readTree("{\"type\":\"assistant\",\"text\":\"durable answer\"}"),
                    JSON.readTree("{\"type\":\"result\",\"provider\":\"fake\",\"model\":\"fake-model\",\"wall_ms\":0,\"tokens\":{\"in\":5,\"out\":2,\"cache\":1},\"turns\":1}")), records);
            assertTrue(Files.readString(file).endsWith("\n"), "the final JSONL record must be complete");
        } finally {
            release.countDown();
            try {
                session.close();
                if (closer.getState() != Thread.State.NEW) {
                    closer.join(5_000);
                    assertFalse(closer.isAlive(), "test closer must terminate");
                }
                if (realWriter.get() != null)
                    assertTrue(outputClosed.await(5, TimeUnit.SECONDS), "test output writer must close");
            } finally {
                if (realWriter.get() != null) realWriter.get().close();
            }
        }
    }

    @Test void traceKeepsThePromptActuallySentWhenTheFinalIterationInvalidatesItsNextVersion() throws Exception {
        var section = new AtomicReference<>("CURRENT_PROMPT");
        Capability changingPrompt = new Capability() { public void attachAgent(AgentScope scope) {
            scope.promptContext(context -> new PromptContext(context.approvalMode(), context.memoryContext(),
                    section.get(), context.skillIndex(), context.variables()));
            scope.iterationEnd((message, results) -> {
                section.set("FUTURE_PROMPT"); scope.systemPromptInvalidator().run(); return IterationDecision.end();
            });
        }};
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("done")));
        Path trace = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().config(config()).provider(provider).model(MODEL)
                .capabilities(List.of(changingPrompt, new TraceCapability(trace))).build()) {
            run(session, "question");
        }
        String sent = provider.requests().getFirst().context().systemPrompt();
        assertTrue(sent.contains("CURRENT_PROMPT"));
        assertEquals(sent, records(trace).getFirst().path("text").asText(), "trace must not build an unsent future prompt");
    }

    @Test void finalReasoningIsRetainedAndUsageResetsForEachRunWhileHistoryKeepsEarlierMessages() throws Exception {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().thinking("first reasoning").text("first answer")
                        .usage(new com.cafecli.ai.message.Usage(5, 2, 1)).build(),
                FakeResponse.builder().thinking("second reasoning").text("second answer")
                        .usage(new com.cafecli.ai.message.Usage(7, 3, 2)).build()));
        Path trace = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().config(config()).provider(provider).model(MODEL)
                .capabilities(List.of(new TraceCapability(trace))).build()) {
            run(session, "first"); run(session, "second");
        }
        var records = records(trace);
        var answers = records.stream().filter(record -> record.path("type").asText().equals("assistant")).toList();
        assertEquals(List.of("first reasoning", "second reasoning"), answers.stream().map(record -> record.path("reasoning").asText()).toList());
        assertEquals(2, records.getLast().path("turns").asInt());
        assertEquals(JSON.readTree("{\"in\":7,\"out\":3,\"cache\":2}"), records.getLast().path("tokens"));
    }

    @Test void completedUsageSurvivesAFailedRunButTheFailedPartialIsNotACompatibleAssistant() throws Exception {
        var provider = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("missing", "unknown", "{}")
                        .usage(new com.cafecli.ai.message.Usage(80, 40, 12)).build(),
                FakeResponse.builder().text("unretained partial").usage(new com.cafecli.ai.message.Usage(999, 999, 999))
                        .fail(com.cafecli.ai.ErrorKind.FATAL, "provider failed")));
        Path trace = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().config(config()).provider(provider).model(MODEL)
                .capabilities(List.of(new TraceCapability(trace))).build()) {
            assertInstanceOf(com.cafecli.core.RunOutcome.Failed.class, run(session, "fail after a completed response"));
        }
        var records = records(trace);
        assertEquals(1, records.stream().filter(record -> record.path("type").asText().equals("assistant")).count());
        assertFalse(Files.readString(trace).contains("unretained partial"));
        assertEquals(JSON.readTree("{\"in\":80,\"out\":40,\"cache\":12}"), records.getLast().path("tokens"));
    }

    @Test void childMessagesAndUsageNeverBecomeMainTraceIterationsOrTokens() throws Exception {
        var parent = FakeProvider.scripted(List.of(
                FakeResponse.builder().toolCall("spawn", "spawn_agent", "{\"prompt\":\"child\"}")
                        .usage(new com.cafecli.ai.message.Usage(10, 2, 1)).build(),
                FakeResponse.builder().text("parent answer").usage(new com.cafecli.ai.message.Usage(20, 3, 2)).build()));
        var child = FakeProvider.scripted(List.of(FakeResponse.builder().thinking("child reasoning").text("child answer")
                .usage(new com.cafecli.ai.message.Usage(999, 999, 999)).build()));
        var factory = new AgentFactory(new com.cafecli.ai.RoutingProvider(Map.of("fake", parent, "child", child)),
                new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(root.resolve("up"), root.resolve("pp"))),
                java.time.Duration.ofSeconds(90));
        var childModel = new Model("child-model", "child", "fake", 32000, 8192, Set.of());
        var tasks = new com.cafecli.harness.tasks.SubAgentsCapability(factory,
                new AgentProfile(childModel, com.cafecli.prompt.PromptMode.AGENT, List.of(), 8));
        Path trace = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().config(config()).factory(factory).model(MODEL)
                .capabilities(List.of(tasks, new TraceCapability(trace))).build()) {
            run(session, "delegate");
        }
        var records = records(trace);
        assertEquals(2, records.stream().filter(record -> record.path("type").asText().equals("assistant")).count());
        assertFalse(Files.readString(trace).contains("child reasoning"));
        assertEquals(JSON.readTree("{\"in\":30,\"out\":5,\"cache\":3}"), records.getLast().path("tokens"));
        assertEquals(2, records.getLast().path("turns").asInt());
    }

    @Test void preparedImageTraceSurvivesDeletionOfItsOriginalFile() throws Exception {
        Files.write(root.resolve("one.png"), new byte[]{1, 2, 3});
        String expected = com.cafecli.llm.UserMessages.fromInput("@image:one.png", root).content();
        var provider = FakeProvider.responding(request -> {
            try { Files.delete(root.resolve("one.png")); } catch (Exception e) { throw new RuntimeException(e); }
            return FakeResponse.text("seen");
        });
        Path trace = root.resolve("trace.jsonl");
        try (var session = AgentSession.builder().config(config()).provider(provider).model(MODEL)
                .capabilities(List.of(new com.cafecli.harness.images.ImagesCapability(root), new TraceCapability(trace))).build()) {
            run(session, "@image:one.png");
        }
        assertFalse(Files.exists(root.resolve("one.png")));
        var user = records(trace).stream().filter(record -> record.path("type").asText().equals("user")).findFirst().orElseThrow();
        assertEquals(expected, user.path("text").asText());
        assertEquals(1, user.path("image_parts").asInt());
        assertTrue(user.path("text").asText().endsWith("[已附加 1 张图片]"));
    }

    private com.cafecli.harness.config.HarnessConfig config() {
        return ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), root));
    }

    private static List<JsonNode> records(Path file) throws Exception {
        return Files.readAllLines(file).stream().map(line -> {
            try { return JSON.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
    }
}
