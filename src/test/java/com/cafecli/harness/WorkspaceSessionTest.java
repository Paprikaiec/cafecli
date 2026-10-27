package com.cafecli.harness;

import static com.cafecli.harness.SessionTestSupport.run;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.RunOutcome;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.harness.config.WorkspaceConfig;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.tool.WorkspaceTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real workspace leaves exercised through PRD seam 1. */
class WorkspaceSessionTest {
    private static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8192, Set.of());
    @TempDir Path root;

    @Test
    void aWriteOutsideTheProjectIsDeniedWithoutCreatingTheVictim() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        FakeProvider provider = provider(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"../victim.txt\",\"content\":\"escaped\"}").build());
        AgentSession session = session(provider, project);

        assertInstanceOf(RunOutcome.Completed.class, run(session, "write"));

        assertEquals(ToolResult.Status.DENIED, results(session).getFirst().status());
        assertFalse(Files.exists(root.resolve("victim.txt")));
    }

    @Test
    void aWriteEndCarriesThePersistedBeforeAndAfterAsStructuredDiffDetails() throws Exception {
        Files.writeString(root.resolve("answer.txt"), "before\n");
        AgentSession session = session(provider(FakeResponse.builder().toolCall("write", "write_file",
                "{\"path\":\"answer.txt\",\"content\":\"after\\n\"}").build()), root);
        List<AgentEvent.ToolEnd> ends = new ArrayList<>();
        session.subscribe(event -> { if (event instanceof AgentEvent.ToolEnd end) ends.add(end); });

        assertInstanceOf(RunOutcome.Completed.class, run(session, "write"));

        assertEquals("after\n", Files.readString(root.resolve("answer.txt")));
        var diff = ends.getFirst().result().details().path("diff");
        assertEquals("answer.txt", diff.path("path").asText());
        assertEquals("before\n", diff.path("before").asText());
        assertEquals("after\n", diff.path("after").asText());
        assertEquals("文件已写入: answer.txt", results(session).getFirst().details().path("text").asText());
    }

    @Test
    void aCommandExitingOneStillHasAnOkOutcomeAndItsOriginalResultText() {
        AgentSession session = session(provider(FakeResponse.builder().toolCall("command", "execute_command",
                "{\"command\":\"printf 'hello\\\\n'; exit 1\"}").build()), root);

        assertInstanceOf(RunOutcome.Completed.class, run(session, "run"));

        assertEquals(ToolResult.Status.OK, results(session).getFirst().status());
        assertEquals(List.of(new TextBlock("命令执行完成 (exit code: 1)\nhello\n")),
                results(session).getFirst().message().content());
    }

    @Test
    void aSingleCommandUpdateReachesTheLoopWhileTheCommandIsStillWaitingForTheObserver() throws Exception {
        String command = "printf 'ready\\n'; while [ ! -f release ]; do sleep 0.01; done; printf finished > finished";
        FakeProvider provider = provider(FakeResponse.builder().toolCall("command", "execute_command",
                new ObjectMapper().writeValueAsString(Map.of("command", command))).build());
        AgentSession session = session(provider, root, new WorkspaceCapability(new WorkspaceTools(root, false, 2)));
        Thread loop = Thread.currentThread();
        AtomicBoolean liveUpdate = new AtomicBoolean();
        session.subscribe(event -> {
            assertSame(loop, Thread.currentThread(), "session subscribers run on the loop thread");
            if (event instanceof AgentEvent.ToolUpdate update) {
                assertEquals("command", update.call().id());
                assertEquals(List.of(new TextBlock("ready")), update.progress().content());
                assertFalse(Files.exists(root.resolve("finished")), "the process must still be gated");
                liveUpdate.set(true);
                try { Files.createFile(root.resolve("release")); }
                catch (java.io.IOException e) { throw new AssertionError(e); }
            }
        });

        assertInstanceOf(RunOutcome.Completed.class, run(session, "run"));

        assertTrue(liveUpdate.get(), "output must arrive before the command can finish");
        assertEquals(ToolResult.Status.OK, results(session).getFirst().status());
        assertEquals("finished", Files.readString(root.resolve("finished")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "1", "yes", " TRUE ", " Yes "})
    void disabledEditIsAbsentFromTheRequestAndCannotExecuteThroughTheCapability(String disabled) throws Exception {
        Files.writeString(root.resolve("answer.txt"), "before");
        for (Map<String, String> properties : List.of(Map.of("cafecli.edit_file.disabled", disabled,
                "paicli.edit_file.disabled", "false"), Map.of("paicli.edit_file.disabled", disabled))) {
            var config = ConfigLoader.load(new ConfigSources(properties,
                    Map.of(), root.resolve("home"), root));
            FakeProvider provider = provider(FakeResponse.builder().toolCall("edit", "edit_file",
                    "{\"path\":\"answer.txt\",\"old_string\":\"before\",\"new_string\":\"after\"}").build());
            AgentSession session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                    .capabilities(List.of(new WorkspaceCapability(root, config.workspace()))).build();

            assertInstanceOf(RunOutcome.Completed.class, run(session, "edit"));

            assertFalse(provider.requests().getFirst().context().tools().stream().anyMatch(t -> t.name().equals("edit_file")));
            assertEquals(ToolResult.Status.ERROR, results(session).getFirst().status());
            assertEquals(List.of(new TextBlock("未知工具: edit_file")), results(session).getFirst().message().content());
            assertEquals("before", Files.readString(root.resolve("answer.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "0", "no", "", "on", "unknown"})
    void otherDisableValuesKeepEditExecutableAndTheCafecliPropertyShadowsTheLegacyOne(String disabled) throws Exception {
        Files.writeString(root.resolve("answer.txt"), "before");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.edit_file.disabled", disabled,
                "paicli.edit_file.disabled", "true"), Map.of(), root.resolve("home"), root));
        FakeProvider provider = provider(FakeResponse.builder().toolCall("edit", "edit_file",
                "{\"path\":\"answer.txt\",\"old_string\":\"before\",\"new_string\":\"after\"}").build());
        AgentSession session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new WorkspaceCapability(root, config.workspace()))).build();

        assertInstanceOf(RunOutcome.Completed.class, run(session, "edit"));

        assertTrue(provider.requests().getFirst().context().tools().stream().anyMatch(t -> t.name().equals("edit_file")));
        assertEquals(ToolResult.Status.OK, results(session).getFirst().status());
        assertEquals("after", Files.readString(root.resolve("answer.txt")));
    }

    @Test
    void theWorkspaceCanListScaffoldFindAndGrepRealProjectFiles() throws Exception {
        Files.writeString(root.resolve("answer.txt"), "the answer is forty two\n");
        FakeProvider provider = provider(FakeResponse.builder()
                .toolCall("list", "list_dir", "{\"path\":\".\"}")
                .toolCall("scaffold", "create_project", "{\"name\":\"sample\",\"type\":\"node\"}")
                .toolCall("find", "glob_files", "{\"pattern\":\"*.txt\"}")
                .toolCall("grep", "grep_code", "{\"pattern\":\"forty two\",\"glob\":\"*.txt\"}").build());
        AgentSession session = session(provider, root);

        assertInstanceOf(RunOutcome.Completed.class, run(session, "inspect"));

        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK, ToolResult.Status.OK, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertTrue(results(session).get(0).details().path("text").asText().contains("[F] answer.txt"));
        assertTrue(Files.readString(root.resolve("sample/package.json")).contains("sample"));
        assertTrue(results(session).get(2).details().path("text").asText().contains("answer.txt"));
        assertTrue(results(session).get(3).details().path("text").asText().contains("forty two"));
        assertEquals(List.of("read_file", "write_file", "edit_file", "list_dir", "create_project", "execute_command",
                        "glob_files", "grep_code"),
                provider.requests().getFirst().context().tools().stream().map(t -> t.name()).toList());
    }

    @Test
    void editsToTheSameFileSerializeInsideTheLeafAndPreserveBothDisjointChanges() throws Exception {
        Files.writeString(root.resolve("answer.txt"), "alpha beta");
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger writes = new AtomicInteger();
        WorkspaceTools workspace = new WorkspaceTools(root, false, 60, (path, change) -> {
            if (writes.incrementAndGet() == 1) {
                firstInside.countDown();
                await(release);
            } else {
                secondInside.countDown();
            }
        });
        AgentSession session = session(provider(twoEdits("answer.txt", "answer.txt")), root,
                new WorkspaceCapability(workspace));
        session.subscribe(event -> { if (event instanceof AgentEvent.ToolStart) bothStarted.countDown(); });
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> run(session, "edit both"));
        try {
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            assertTrue(firstInside.await(2, TimeUnit.SECONDS));
            assertFalse(secondInside.await(200, TimeUnit.MILLISECONDS), "the second write cannot enter the held file lock");
        } finally {
            release.countDown();
            run.get(3, TimeUnit.SECONDS);
        }
        assertInstanceOf(RunOutcome.Completed.class, run.get(3, TimeUnit.SECONDS));
        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertEquals("ALPHA BETA", Files.readString(root.resolve("answer.txt")));
        assertEquals(2, writes.get());
    }

    @Test
    void editsToDifferentFilesOverlapInsideTheLeafMutationObservers() throws Exception {
        Files.writeString(root.resolve("one.txt"), "alpha beta");
        Files.writeString(root.resolve("two.txt"), "alpha beta");
        CountDownLatch bothInside = new CountDownLatch(2);
        WorkspaceTools workspace = new WorkspaceTools(root, false, 60, (path, change) -> {
            bothInside.countDown();
            await(bothInside);
        });
        AgentSession session = session(provider(twoEdits("one.txt", "two.txt")), root,
                new WorkspaceCapability(workspace));

        assertInstanceOf(RunOutcome.Completed.class, run(session, "edit both"));

        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertEquals("ALPHA beta", Files.readString(root.resolve("one.txt")));
        assertEquals("alpha BETA", Files.readString(root.resolve("two.txt")));
    }

    @Test
    void cancellingWhileAnEditWaitsForTheFileLockCannotProduceALaterGhostWrite() throws Exception {
        Files.writeString(root.resolve("answer.txt"), "alpha beta");
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch observerExited = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> persisted = new AtomicReference<>();
        WorkspaceTools workspace = new WorkspaceTools(root, false, 60, (path, change) -> {
            writes.incrementAndGet();
            persisted.set(change[1]);
            firstInside.countDown();
            try { await(release); }
            finally { observerExited.countDown(); }
        });
        AgentSession session = session(provider(twoEdits("answer.txt", "answer.txt")), root,
                new WorkspaceCapability(workspace));
        session.subscribe(event -> { if (event instanceof AgentEvent.ToolStart) bothStarted.countDown(); });
        CompletableFuture<RunOutcome> run = CompletableFuture.supplyAsync(() -> run(session, "edit both"));
        try {
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            assertTrue(firstInside.await(2, TimeUnit.SECONDS));
            session.cancel();
            assertInstanceOf(RunOutcome.Cancelled.class, run.get(3, TimeUnit.SECONDS));
            assertTrue(observerExited.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            session.cancel();
            run.get(3, TimeUnit.SECONDS);
        }
        assertEquals(List.of(ToolResult.Status.CANCELLED, ToolResult.Status.CANCELLED),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertEquals(1, writes.get());
        assertEquals(persisted.get(), Files.readString(root.resolve("answer.txt")));
    }

    @Test
    void parallelCommandReadersKeepTheirCallIdsAndDetailsWhileBothProcessesAreGated() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String first = "printf 'one\\n'; while [ ! -f release ]; do sleep 0.01; done; printf one > one-finished";
        String second = "printf 'two\\n'; while [ ! -f release ]; do sleep 0.01; done; printf two > two-finished";
        FakeProvider provider = provider(FakeResponse.builder()
                .toolCall("first", "execute_command", json.writeValueAsString(Map.of("command", first)))
                .toolCall("second", "execute_command", json.writeValueAsString(Map.of("command", second))).build());
        AgentSession session = session(provider, root, new WorkspaceCapability(new WorkspaceTools(root, false, 2)));
        List<String> updates = new ArrayList<>();
        session.subscribe(event -> {
            if (event instanceof AgentEvent.ToolUpdate update) {
                String expectedLine = update.call().id().equals("first") ? "one" : "two";
                assertEquals(List.of(new TextBlock(expectedLine)), update.progress().content());
                assertEquals(update.call().id().equals("first") ? first : second,
                        update.progress().details().path("command").asText());
                assertEquals(expectedLine, update.progress().details().path("line").asText());
                updates.add(update.call().id());
                if (updates.size() == 2) {
                    assertFalse(Files.exists(root.resolve("one-finished")));
                    assertFalse(Files.exists(root.resolve("two-finished")));
                    try { Files.createFile(root.resolve("release")); }
                    catch (java.io.IOException e) { throw new AssertionError(e); }
                }
            }
        });

        assertInstanceOf(RunOutcome.Completed.class, run(session, "run both"));

        assertEquals(Set.of("first", "second"), Set.copyOf(updates));
        assertEquals(List.of(ToolResult.Status.OK, ToolResult.Status.OK),
                results(session).stream().map(ToolResultEntry::status).toList());
        assertEquals("one", Files.readString(root.resolve("one-finished")));
        assertEquals("two", Files.readString(root.resolve("two-finished")));
    }

    @Test
    void cancellingASingleLiveCommandClosesItOnceAndDoesNotLeakIntoTheNextRun() throws Exception {
        String command = "printf 'ready\\n'; while [ ! -f release ]; do sleep 0.01; done; printf escaped > victim";
        FakeProvider provider = provider(FakeResponse.builder().toolCall("cancel", "execute_command",
                new ObjectMapper().writeValueAsString(Map.of("command", command))).build());
        AgentSession session = session(provider, root, new WorkspaceCapability(new WorkspaceTools(root, false, 2)));
        List<AgentEvent> events = new ArrayList<>();
        session.subscribe(event -> {
            events.add(event);
            if (event instanceof AgentEvent.ToolUpdate) session.cancel();
        });

        assertInstanceOf(RunOutcome.Cancelled.class, run(session, "cancel"));

        assertEquals(1, results(session).size());
        assertEquals(ToolResult.Status.CANCELLED, results(session).getFirst().status());
        assertEquals(1, events.stream().filter(AgentEvent.ToolEnd.class::isInstance).count());
        assertFalse(Files.exists(root.resolve("victim")));
        assertInstanceOf(RunOutcome.Completed.class, run(session, "again"));
        assertEquals(1, events.stream().filter(AgentEvent.ToolUpdate.class::isInstance).count());
    }

    @Test
    void theTypedCommandTimeoutReachesTheLeafAndRemainsIndependentOfTheBatchDeadline() throws Exception {
        var loaded = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        assertEquals(60, loaded.workspace().commandTimeoutSeconds());
        assertEquals(java.time.Duration.ofSeconds(90), loaded.workspace().toolBatchTimeout());
        assertEquals(java.time.Duration.ofSeconds(125), new WorkspaceConfig(false, 120).toolBatchTimeout());
        var config = new HarnessConfig(loaded.provider(), loaded.maxIterations(), root, loaded.homes(),
                loaded.warnings(), new WorkspaceConfig(false, 1));
        String command = "while [ ! -f release ]; do sleep 0.01; done";
        FakeProvider provider = provider(FakeResponse.builder().toolCall("timeout", "execute_command",
                new ObjectMapper().writeValueAsString(Map.of("command", command))).build());
        AgentSession session = AgentSession.builder().provider(provider).model(MODEL).config(config)
                .capabilities(List.of(new WorkspaceCapability(root, config.workspace()))).build();

        assertInstanceOf(RunOutcome.Completed.class, run(session, "timeout"));

        assertEquals(ToolResult.Status.TIMEOUT, results(session).getFirst().status());
        assertEquals(List.of(new TextBlock("命令执行超时（1秒），已强制终止")), results(session).getFirst().message().content());
    }

    private static FakeResponse twoEdits(String firstPath, String secondPath) throws Exception {
        ObjectMapper json = new ObjectMapper();
        return FakeResponse.builder()
                .toolCall("first", "edit_file", json.writeValueAsString(Map.of("path", firstPath,
                        "old_string", "alpha", "new_string", "ALPHA")))
                .toolCall("second", "edit_file", json.writeValueAsString(Map.of("path", secondPath,
                        "old_string", "beta", "new_string", "BETA"))).build();
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(2, TimeUnit.SECONDS), "the observer barrier was never released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private FakeProvider provider(FakeResponse batch) {
        return FakeProvider.scripted(List.of(batch, FakeResponse.text("done")));
    }

    private AgentSession session(FakeProvider provider, Path project) {
        return session(provider, project, new WorkspaceCapability(project));
    }

    private AgentSession session(FakeProvider provider, Path project, Capability capability) {
        return AgentSession.builder().provider(provider).model(MODEL)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), project)))
                .capabilities(List.of(capability)).build();
    }

    private static List<ToolResultEntry> results(AgentSession session) {
        return session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast).toList();
    }
}
