package com.cafecli.harness;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.AgentTool;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.AgentLogJson;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.image.ImagePart;
import com.cafecli.tool.Tool;
import com.cafecli.tool.ToolOutput;
import com.cafecli.tool.WorkspaceTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PRD seam 1: session → fake provider, observable log and events, real guarded file leaf. */
class ToolSessionTest {
    private static final Model MODEL = new Model("fake-model", "fake", "fake", 128_000, 8_192, Set.of());
    @TempDir Path root;

    @Test
    void readFileReachesTheSecondRequestAndEveryLogEntryRoundTrips() throws Exception {
        Files.writeString(root.resolve("answer.txt"), "the answer is 42\n");
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("read", "read_file", "{\"path\":\"answer.txt\"}").build(), FakeResponse.text("42")));
        AgentSession session = session(provider, new WorkspaceCapability(root));

        assertEquals("42", assertInstanceOf(RunOutcome.Completed.class, session.submit("read it")).message().text());

        ToolResultMessage result = assertInstanceOf(ToolResultMessage.class,
                provider.requests().get(1).context().messages().get(2));
        assertEquals(List.of(new TextBlock("文件内容:\nthe answer is 42\n")), result.content());
        assertFalse(result.isError());
        assertEquals(List.of("read_file", "write_file", "edit_file", "list_dir", "create_project", "execute_command",
                "glob_files", "grep_code"), provider.requests().get(0).context().tools().stream()
                .map(com.cafecli.ai.ToolDefinition::name).toList());
        AgentLogJson json = new AgentLogJson(Map.of());
        assertEquals(session.log(), json.read(json.write(session.log())));
    }

    @Test
    void twoGuardedReadsOverlapFinishOutOfOrderAndAreLoggedInSourceOrder() throws Exception {
        Files.writeString(root.resolve("one.txt"), "one");
        Files.writeString(root.resolve("two.txt"), "two");
        CountDownLatch bothExecuting = new CountDownLatch(2);
        CountDownLatch secondEnded = new CountDownLatch(1);
        AgentTool leaf = new LeafToolAdapter(WorkspaceTools.readFile(root));
        AgentTool instrumented = new DecoratedTool(leaf) {
            @Override
            public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                      Consumer<ToolResult> progress) throws Exception {
                bothExecuting.countDown();
                assertTrue(bothExecuting.await(2, TimeUnit.SECONDS), "both read_file executions must overlap");
                if (id.equals("first")) {
                    assertTrue(secondEnded.await(2, TimeUnit.SECONDS), "the loop must observe the second completion first");
                }
                return super.execute(id, input, cancellation, progress);
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("first", "read_file", "{\"path\":\"one.txt\"}")
                .toolCall("second", "read_file", "{\"path\":\"two.txt\"}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, tools(instrumented));
        List<String> ends = new ArrayList<>();
        Thread loop = Thread.currentThread();
        session.subscribe(event -> {
            assertSame(loop, Thread.currentThread(), "events belong to the loop thread");
            if (event instanceof AgentEvent.ToolEnd end) {
                ends.add(end.result().message().toolCallId());
                if (end.result().message().toolCallId().equals("second")) secondEnded.countDown();
            }
        });

        session.submit("read both");

        assertEquals(List.of("second", "first"), ends);
        List<ToolResultEntry> results = session.log().stream().filter(ToolResultEntry.class::isInstance)
                .map(ToolResultEntry.class::cast).toList();
        assertEquals(List.of("first", "second"), results.stream().map(r -> r.message().toolCallId()).toList());
        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK), results.stream().map(ToolResultEntry::status).toList());
        assertEquals(List.of(new TextBlock("文件内容:\none")), results.get(0).message().content());
        AgentLogJson json = new AgentLogJson(Map.of());
        assertEquals(session.log(), json.read(json.write(session.log())));
    }

    @Test
    void everyPreflightFinishesInSourceOrderBeforeAnyStartAndBlockedCallsDoNotExecute() {
        List<String> sequence = new ArrayList<>();
        Thread loop = Thread.currentThread();
        AgentTool leaf = new LeafToolAdapter(WorkspaceTools.readFile(root));
        AgentTool observed = new DecoratedTool(leaf) {
            @Override public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                                 Consumer<ToolResult> progress) {
                assertEquals(List.of("first", "blocked", "third"), sequence);
                progress.accept(new ToolResult(List.of(new TextBlock("working " + id)), null, ToolResult.Status.OK));
                return new ToolResult(List.of(new TextBlock(id)), null, ToolResult.Status.OK);
            }
        };
        List<String> after = new ArrayList<>();
        Capability preflight = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> {
                    assertSame(loop, Thread.currentThread());
                    sequence.add(call.id());
                    return call.id().equals("blocked") ? new ToolResult(List.of(new TextBlock("hook says stop")),
                            null, ToolResult.Status.BLOCKED) : null;
                });
                scope.afterToolCall((call, result) -> {
                    assertSame(loop, Thread.currentThread());
                    after.add(call.id());
                    return result;
                });
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("first", "read_file", "{}")
                .toolCall("blocked", "read_file", "{}")
                .toolCall("third", "read_file", "{}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, tools(observed), preflight);
        List<AgentEvent> events = new ArrayList<>();
        session.subscribe(event -> {
            assertSame(loop, Thread.currentThread());
            if (event instanceof AgentEvent.ToolStart) assertEquals(List.of("first", "blocked", "third"), sequence);
            events.add(event);
        });

        session.submit("go");

        assertEquals(Set.of("first", "blocked", "third"), Set.copyOf(after));
        assertEquals(List.of("first", "third"), events.stream().filter(AgentEvent.ToolUpdate.class::isInstance)
                .map(AgentEvent.ToolUpdate.class::cast).map(update -> update.call().id()).sorted().toList());
        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.BLOCKED, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
    }

    @Test
    void guardsDenyOutsideAndSymlinkReadsWithoutLeakingVictimBytesEvenAfterPermittingPreflight() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path victim = root.resolve("victim.txt");
        Files.writeString(victim, "TOP SECRET NOT FOR MODEL");
        Files.createSymbolicLink(project.resolve("escape"), victim);
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("outside", "read_file", "{\"path\":\"../victim.txt\"}")
                .toolCall("symlink", "read_file", "{\"path\":\"escape\"}").build(), FakeResponse.text("denied")));
        Capability permit = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.beforeToolCall((call, token) -> null);
            }
        };
        AgentSession session = session(provider, project, permit, new WorkspaceCapability(project));

        session.submit("read outside");

        assertEquals(List.of(ToolResult.Status.DENIED, ToolResult.Status.DENIED),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertTrue(results(session).stream().allMatch(result -> result.message().isError()));
        assertFalse(provider.requests().get(1).context().messages().toString().contains("TOP SECRET NOT FOR MODEL"));
        AgentLogJson json = new AgentLogJson(Map.of());
        assertFalse(json.write(session.log()).contains("TOP SECRET NOT FOR MODEL"));
        assertEquals(session.log(), json.read(json.write(session.log())));
        assertEquals("TOP SECRET NOT FOR MODEL", Files.readString(victim));
    }

    @Test
    void everyLeafStatusAndTextImageAndStructuredBlocksSurviveWithoutReadingTextPrefixes() throws Exception {
        JsonNode structured = new ObjectMapper().readTree("{\"meaning\":42}");
        List<ToolOutput.Block> blocks = List.of(ToolOutput.Block.text("工具执行失败: still just text"),
                ToolOutput.Block.structuredJson(structured));
        Tool leaf = new Tool("sample", "sample", new ObjectMapper().readTree("{\"type\":\"object\"}"), input ->
                new ToolOutput("raw leaf text", List.of(new ImagePart("aW1hZ2U=", "image/png")), blocks,
                        ToolOutput.Status.valueOf(input.path("status").asText())));
        FakeResponse.Builder calls = FakeResponse.builder();
        for (String status : List.of("OK", "ERROR", "DENIED", "BLOCKED", "CANCELLED", "TIMEOUT")) {
            calls.toolCall(status, "sample", "{\"status\":\"" + status + "\"}");
        }
        FakeProvider provider = FakeProvider.scripted(List.of(calls.build(), FakeResponse.text("done")));
        AgentSession session = session(provider, tools(new LeafToolAdapter(leaf)));

        session.submit("exercise statuses");

        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.ERROR, ToolResult.Status.DENIED,
                ToolResult.Status.BLOCKED, ToolResult.Status.CANCELLED, ToolResult.Status.TIMEOUT),
                results(session).stream().map(ToolResultEntry::status).toList());
        for (ToolResultEntry result : results(session)) {
            assertEquals(List.of(new TextBlock("工具执行失败: still just text\n\n{\n  \"meaning\" : 42\n}"),
                    new ImageBlock("aW1hZ2U=", "image/png")), result.message().content());
            assertEquals(result.status() != ToolResult.Status.OK, result.message().isError());
            assertEquals("raw leaf text", result.details().path("text").asText());
            assertEquals("STRUCTURED_JSON", result.details().path("blocks").get(1).path("type").asText());
            assertEquals(42, result.details().path("blocks").get(1).path("structuredJson").path("meaning").asInt());
            assertEquals("aW1hZ2U=", result.details().path("imageParts").get(0).path("base64").asText());
        }
        AgentLogJson json = new AgentLogJson(Map.of());
        assertEquals(session.log(), json.read(json.write(session.log())));
    }

    @Test
    void cancellingWhileWorkersAreRunningClosesEveryCallAndTheNextRequestHasTheCompleteBatch() throws Exception {
        AgentTool cooperative = new DecoratedTool(new LeafToolAdapter(WorkspaceTools.readFile(root))) {
            @Override public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                                 Consumer<ToolResult> progress) throws Exception {
                CountDownLatch stop = new CountDownLatch(1);
                try (var ignored = cancellation.onCancel(stop::countDown)) {
                    progress.accept(new ToolResult(List.of(new TextBlock("running")), null, ToolResult.Status.OK));
                    assertTrue(stop.await(2, TimeUnit.SECONDS));
                }
                return new ToolResult(List.of(new TextBlock("cancelled")), null, ToolResult.Status.CANCELLED);
            }
        };
        FakeResponse.Builder batch = FakeResponse.builder();
        for (int i = 0; i < 6; i++) batch.toolCall("call" + i, "read_file", "{}");
        FakeProvider provider = FakeProvider.scripted(List.of(batch.build(), FakeResponse.text("next run")));
        AgentSession session = session(provider, tools(cooperative));
        List<AgentEvent> events = new ArrayList<>();
        session.subscribe(event -> {
            events.add(event);
            if (event instanceof AgentEvent.ToolUpdate) session.cancel();
        });

        assertInstanceOf(RunOutcome.Cancelled.class, session.submit("cancel these"));
        assertEquals(1, provider.requests().size());
        assertEquals(List.of("call0", "call1", "call2", "call3", "call4", "call5"),
                results(session).stream().map(result -> result.message().toolCallId()).toList());
        assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.CANCELLED));
        assertEquals(6, events.stream().filter(AgentEvent.ToolEnd.class::isInstance).count());
        AgentLogJson json = new AgentLogJson(Map.of());
        assertEquals(session.log(), json.read(json.write(session.log())));

        assertInstanceOf(RunOutcome.Completed.class, session.submit("again"));
        assertEquals(6, provider.requests().get(1).context().messages().stream()
                .filter(ToolResultMessage.class::isInstance).count());
    }

    @Test
    void aBatchExecutesAtMostFourCallsAtOnceAndDoesNotLoseQueuedCalls() throws Exception {
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch fifthStarted = new CountDownLatch(1);
        Semaphore release = new Semaphore(0);
        AtomicInteger entries = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AgentTool gated = new DecoratedTool(new LeafToolAdapter(WorkspaceTools.readFile(root))) {
            @Override public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                                 Consumer<ToolResult> progress) throws Exception {
                int number = entries.incrementAndGet();
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                fourStarted.countDown();
                if (number == 5) fifthStarted.countDown();
                try {
                    assertTrue(release.tryAcquire(2, TimeUnit.SECONDS), "worker was never released");
                    return new ToolResult(List.of(new TextBlock(id)), null, ToolResult.Status.OK);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        FakeResponse.Builder batch = FakeResponse.builder();
        for (int i = 0; i < 6; i++) batch.toolCall("call" + i, "read_file", "{}");
        AgentSession session = session(FakeProvider.scripted(List.of(batch.build(), FakeResponse.text("done"))), tools(gated));
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> session.submit("six calls"));
        try {
            assertTrue(fourStarted.await(2, TimeUnit.SECONDS), "four workers must be able to overlap");
            assertFalse(fifthStarted.await(100, TimeUnit.MILLISECONDS), "the fifth call must remain queued");
        } finally {
            release.release(6);
        }
        assertInstanceOf(RunOutcome.Completed.class, run.get(3, TimeUnit.SECONDS));
        assertEquals(4, peak.get());
        assertEquals(6, entries.get());
        assertEquals(6, results(session).size());
        assertTrue(results(session).stream().allMatch(result -> result.status() == ToolResult.Status.OK));
    }

    @Test
    void unknownMalformedAndThrowingCallsEachHaveAResultAndSuccessfulSiblingsStillReachTheModel() {
        AgentTool leaf = new DecoratedTool(new LeafToolAdapter(WorkspaceTools.readFile(root))) {
            @Override public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                                 Consumer<ToolResult> progress) {
                if (id.equals("throws")) throw new IllegalArgumentException("boom");
                return new ToolResult(List.of(new TextBlock("ok")), null, ToolResult.Status.OK);
            }
        };
        FakeProvider provider = FakeProvider.scripted(List.of(FakeResponse.builder()
                .toolCall("unknown", "absent", "{}")
                .toolCall("malformed", "read_file", "{")
                .toolCall("throws", "read_file", "{}")
                .toolCall("good", "read_file", "{}").build(), FakeResponse.text("done")));
        AgentSession session = session(provider, tools(leaf));

        assertInstanceOf(RunOutcome.Completed.class, session.submit("mixed batch"));

        assertEquals(List.of(ToolResult.Status.ERROR, ToolResult.Status.ERROR, ToolResult.Status.ERROR,
                ToolResult.Status.OK), results(session).stream().map(ToolResultEntry::status).toList());
        assertEquals(List.of(new TextBlock("未知工具: absent")), results(session).get(0).message().content());
        assertEquals(List.of(new TextBlock("工具执行失败: boom")), results(session).get(2).message().content());
        assertEquals(4, provider.requests().get(1).context().messages().stream()
                .filter(ToolResultMessage.class::isInstance).count());
        AgentLogJson json = new AgentLogJson(Map.of());
        assertEquals(session.log(), json.read(json.write(session.log())));
    }

    private static List<ToolResultEntry> results(AgentSession session) {
        return session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
    }

    private static Capability tools(AgentTool... tools) {
        return new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                for (AgentTool tool : tools) scope.tool(tool);
            }
        };
    }

    private static class DecoratedTool implements AgentTool {
        private final AgentTool delegate;
        DecoratedTool(AgentTool delegate) { this.delegate = delegate; }
        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public JsonNode parameters() { return delegate.parameters(); }
        @Override public ToolResult execute(String id, JsonNode input, CancellationToken cancellation,
                                            Consumer<ToolResult> progress) throws Exception {
            return delegate.execute(id, input, cancellation, progress);
        }
    }

    private AgentSession session(FakeProvider provider, Capability... capabilities) {
        return session(provider, root, capabilities);
    }

    private AgentSession session(FakeProvider provider, Path project, Capability... capabilities) {
        return AgentSession.builder().provider(provider).model(MODEL)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), project)))
                .capabilities(List.of(capabilities)).build();
    }
}
