package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.RunInput;
import com.cafecli.snapshot.TurnSnapshot;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.core.ToolResult;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.snapshot.SnapshotCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.snapshot.SideGitManager;
import com.cafecli.snapshot.SnapshotConfig;
import com.cafecli.snapshot.SnapshotPhase;
import com.cafecli.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cafecli.harness.SessionTestSupport.run;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotSessionTest {
    private static final Model MODEL = new Model("fake", "fake", "fake", 128000, 8192, Set.of());
    @TempDir Path root;

    @Test void aMainRunIsAnchoredBeforeItsFirstRequestAndRevertRestoresItsOriginalBytes() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "before\n");
        Files.writeString(project.resolve(".git"), "gitdir: ../main/.git/worktrees/project\n");
        byte[] gitfile = Files.readAllBytes(project.resolve(".git"));
        var phasesAtRequest = new AtomicReference<List<SnapshotPhase>>();
        var requests = new AtomicInteger();
        try (var snapshots = new SnapshotService(new SideGitManager(project,
                new SnapshotConfig(true, root.resolve("snapshots"), 50, List.of(".git"))))) {
            var provider = FakeProvider.responding(request -> {
                int index = requests.getAndIncrement();
                if (index == 0) {
                    try { phasesAtRequest.set(snapshots.listSnapshots(20).stream().map(s -> s.phase()).toList()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    return call("write", "write_file", "{\"path\":\"answer.txt\",\"content\":\"after\\n\"}");
                }
                if (index == 1) return call("revert", "revert_turn", "{}");
                return FakeResponse.text("restored");
            });
            var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), project));
            var snapshotCapability = new SnapshotCapability(snapshots, ignored -> {});
            Capability ownership = new Capability() { public void attachSession(SessionRegistrar session) { session.onClose(snapshotCapability); } };
            try (var session = AgentSession.builder().config(config).provider(provider).model(MODEL)
                    .capabilities(List.of(ownership, new WorkspaceCapability(project), snapshotCapability)).build()) {
                run(session, "change then revert");
                assertEquals(List.of(SnapshotPhase.PRE_TURN), phasesAtRequest.get(), "the first request must already see its pre-turn anchor");
                assertEquals("before\n", Files.readString(project.resolve("answer.txt")));
                var result = session.log().stream().filter(ToolResultEntry.class::isInstance).map(ToolResultEntry.class::cast)
                        .filter(entry -> entry.message().toolName().equals("revert_turn")).findFirst().orElseThrow();
                assertEquals(ToolResult.Status.OK, result.status());
                assertEquals(ToolExecution.Kind.RETURNED, ToolExecution.from(new ToolResult(result.message().content(), result.details(), result.status())).kind());
            }
        }
        assertArrayEquals(gitfile, Files.readAllBytes(project.resolve(".git")));
    }

    @Test void snapshotCommandsListRestoreAndCleanWithoutAddingModelHistory() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "before");
        var snapshots = new SnapshotService(new SideGitManager(project,
                new SnapshotConfig(true, root.resolve("snapshots"), 50, List.of(".git"))));
        var capability = new SnapshotCapability(snapshots, ignored -> {});
        Capability ownership = new Capability() { public void attachSession(SessionRegistrar session) { session.onClose(capability); } };
        var provider = FakeProvider.scripted(List.of(call("write", "write_file", "{\"path\":\"answer.txt\",\"content\":\"after\"}"), FakeResponse.text("changed")));
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake"), Map.of(), root.resolve("home"), project));
        try (var session = AgentSession.builder().config(config).provider(provider).model(MODEL)
                .capabilities(List.of(ownership, new WorkspaceCapability(project), capability)).build()) {
            assertEquals("📭 暂无 Side-Git 快照\n", session.dispatch("/snapshot").orElseThrow());
            assertEquals("❌ 未知 /snapshot 子命令: bogus\n可用命令：\n  /snapshot\n  /snapshot status\n  /snapshot clean\n  /restore <N>\n",
                    session.dispatch("/snapshot bogus").orElseThrow());
            run(session, "write");
            var before = session.log();
            assertTrue(session.dispatch("/snapshot LIST").orElseThrow().contains("/restore 1"));
            assertTrue(session.dispatch("/snapshot status").orElseThrow().startsWith("📸 Side-Git 快照状态\n"));
            assertTrue(session.dispatch("/restore invalid").orElseThrow().startsWith("✅ 已恢复到快照 "));
            assertEquals("before", Files.readString(project.resolve("answer.txt")));
            assertEquals(before, session.log(), "snapshot commands do not append model messages");
            assertEquals(2, provider.requests().size());
            assertTrue(session.dispatch("/snapshot clean").orElseThrow().startsWith("🧹 已清理当前项目的 Side-Git 快照目录: "));
            assertEquals("📭 暂无 Side-Git 快照\n", session.dispatch("/snapshot list").orElseThrow());
        }
    }

    @Test void theAnchorPrecedesEarlierWritersAuxiliaryToolsAndKeepsTheRawInputSummary() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "before");
        var snapshots = new SnapshotService(new SideGitManager(project,
                new SnapshotConfig(true, root.resolve("snapshots"), 50, List.of(".git"))));
        var feature = new SnapshotCapability(snapshots, ignored -> {});
        Capability ownership = new Capability() { public void attachSession(SessionRegistrar session) { session.onClose(feature); } };
        Capability writer = new Capability() { public void attachAgent(AgentScope scope) {
            scope.inputPreparation((input, token) -> new RunInput(input.source(), UserMessage.text("prepared presentation"), List.of(), 0));
            scope.runStartWriter((input, token, tools) -> {
                tools.invoke("write_file", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("path", "answer.txt").put("content", "writer changed it"));
                return List.of();
            });
        }};
        var provider = FakeProvider.scripted(List.of(call("revert", "revert_turn", "{}"), FakeResponse.text("done")));
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), project));
        try (var session = AgentSession.builder().config(config).provider(provider).model(MODEL)
                .capabilities(List.of(ownership, writer, new WorkspaceCapability(project), feature)).build()) {
            run(session, " raw\n user input ");
            assertEquals("before", Files.readString(project.resolve("answer.txt")), "the writer's auxiliary write must happen after the anchor");
            assertTrue(provider.requests().getFirst().context().messages().toString().contains("prepared presentation"));
            var anchor = snapshots.listSnapshots(20).stream().filter(snapshot -> snapshot.phase() == SnapshotPhase.PRE_TURN).findFirst().orElseThrow();
            try (var repository = new FileRepositoryBuilder().setGitDir(snapshots.manager().gitDir().toFile()).setWorkTree(project.toFile()).build();
                 var walk = new RevWalk(repository)) {
                assertTrue(walk.parseCommit(ObjectId.fromString(anchor.commitId())).getFullMessage().contains("mode=react\ninput=raw user input"));
            }
        }
    }

    @Test void closingAnInterruptedSessionWaitsForTheRealPostWriterAndLeavesItsBytesDurable() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "before");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var postWorker = new AtomicReference<Thread>();
        var manager = new SideGitManager(project, new SnapshotConfig(true, root.resolve("snapshots"), 50, List.of(".git"))) {
            @Override public synchronized TurnSnapshot postTurnSnapshot(String id, String summary) throws java.io.IOException, org.eclipse.jgit.api.errors.GitAPIException {
                postWorker.set(Thread.currentThread()); entered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                }
                try { return super.postTurnSnapshot(id, summary); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        };
        var snapshots = new SnapshotService(manager);
        var feature = new SnapshotCapability(snapshots, ignored -> {});
        Capability ownership = new Capability() { public void attachSession(SessionRegistrar session) { session.onClose(feature); } };
        Capability interruptingCleanup = new Capability() { public void attachSession(SessionRegistrar session) {
            session.onClose(() -> Thread.currentThread().interrupt());
        }};
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), project));
        var provider = FakeProvider.scripted(List.of(call("write", "write_file", "{\"path\":\"answer.txt\",\"content\":\"after\"}"), FakeResponse.text("done")));
        var interruptedAfter = new AtomicBoolean();
        var closing = new CompletableFuture<Void>();
        try (var session = AgentSession.builder().config(config).provider(provider).model(MODEL)
                .capabilities(List.of(ownership, interruptingCleanup, new WorkspaceCapability(project), feature)).build()) {
            run(session, "write");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread closer = Thread.ofPlatform().start(() -> {
                try { session.close(); interruptedAfter.set(Thread.currentThread().isInterrupted()); closing.complete(null); }
                catch (Throwable failure) { closing.completeExceptionally(failure); }
            });
            try { assertThrows(TimeoutException.class, () -> closing.get(150, TimeUnit.MILLISECONDS), "close must wait for owned snapshot I/O"); }
            finally {
                release.countDown(); closer.join(5000); assertFalse(closer.isAlive());
                if (postWorker.get() != null) { postWorker.get().join(5000); assertFalse(postWorker.get().isAlive()); }
            }
            closing.get(5, TimeUnit.SECONDS);
            assertTrue(interruptedAfter.get(), "draining snapshot I/O must preserve the closer's interruption");
            var post = manager.listSnapshots(20).stream().filter(snapshot -> snapshot.phase() == SnapshotPhase.POST_TURN).findFirst().orElseThrow();
            try (var repository = new FileRepositoryBuilder().setGitDir(manager.gitDir().toFile()).setWorkTree(project.toFile()).build();
                 var walk = new RevWalk(repository)) {
                var commit = walk.parseCommit(ObjectId.fromString(post.commitId()));
                try (var file = TreeWalk.forPath(repository, "answer.txt", commit.getTree())) {
                    assertNotNull(file);
                    assertEquals("after", new String(repository.open(file.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
                }
            }
        } finally { release.countDown(); }
    }

    private static FakeResponse call(String id, String name, String arguments) {
        return FakeResponse.builder().toolCall(id, name, arguments).build();
    }
}
