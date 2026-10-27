package com.cafecli.app;

import com.cafecli.core.RunOutcome;
import com.cafecli.harness.SubmitResult;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.snapshot.SideGitManager;
import com.cafecli.snapshot.SnapshotPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCompositionTest {
    @TempDir Path root;

    @Test void actualCompositionAnchorsAndRestoresAndDrainsItsPostSnapshotOnClose() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("answer.txt"), "original");
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"toolCalls":[{"id":"write","name":"write_file","arguments":{"path":"answer.txt","content":"changed"}}]},
                  {"toolCalls":[{"id":"restore","name":"revert_turn"}]},
                  {"text":"restored"}
                ]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.dir", root.resolve("snapshots").toString()), Map.of(), root.resolve("home"), project));
        try (var session = Composition.headlessSession(config)) {
            var done = assertInstanceOf(SubmitResult.Completed.class, session.submit("write then restore"));
            assertEquals("restored", assertInstanceOf(RunOutcome.Completed.class, done.outcome()).message().text());
            assertEquals("original", Files.readString(project.resolve("answer.txt")), "production must wire revert_turn to its main anchor");
        }
        var phases = new SideGitManager(project, config.snapshots()).listSnapshots(20).stream().map(snapshot -> snapshot.phase()).toList();
        assertEquals(1, phases.stream().filter(phase -> phase == SnapshotPhase.PRE_TURN).count());
        assertEquals(1, phases.stream().filter(phase -> phase == SnapshotPhase.POST_TURN).count());
        assertEquals(1, phases.stream().filter(phase -> phase == SnapshotPhase.PRE_RESTORE).count());
    }
    @Test void cliWarnsAboutAHijackedProjectOnStderrAndNeverTouchesItsGitfileOrSnapshotTarget() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path snapshots = Files.createDirectories(root.resolve("snapshots"));
        Path target = Files.createDirectories(snapshots.resolve("old/hijacked/.git"));
        Files.writeString(target.resolve("marker"), "unchanged snapshot target");
        Files.writeString(project.resolve(".git"), "gitdir: " + target + "\n");
        byte[] gitfile = Files.readAllBytes(project.resolve(".git"));
        Path script = root.resolve("script.json");
        Files.writeString(script, "{\"responses\":[{\"text\":\"done\"}]}");
        Files.writeString(project.resolve("issue.md"), "answer");
        var sources = new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.dir", snapshots.toString()), Map.of(), root.resolve("home"), project);
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"run", "--file", "issue.md"}, sources,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit);
        assertEquals("done\n", out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("git worktree repair"), "headless startup must expose repair guidance on stderr");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("快照已停用"));
        assertArrayEquals(gitfile, Files.readAllBytes(project.resolve(".git")));
        assertEquals("unchanged snapshot target", Files.readString(target.resolve("marker")));
        try (var files = Files.list(target)) { assertEquals(List.of("marker"), files.map(path -> path.getFileName().toString()).toList()); }
        try (var session = Composition.headlessSession(ConfigLoader.load(sources))) {
            assertTrue(session.dispatch("/snapshot status").orElseThrow().contains("git worktree repair"));
            assertTrue(session.dispatch("/restore").orElseThrow().startsWith("❌ ⚠️  快照已停用"));
        }
        assertArrayEquals(gitfile, Files.readAllBytes(project.resolve(".git")));
    }

    @Test void childWritesJoinTheMainTimelineAndDoNotCreateChildAnchors() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[
                  {"toolCalls":[{"id":"spawn","name":"spawn_agent","arguments":{"prompt":"write child"}}]},
                  {"toolCalls":[{"id":"child-write","name":"write_file","arguments":{"path":"child.txt","content":"child bytes"}}]},
                  {"toolCalls":[{"id":"child-restore","name":"revert_turn"}]},
                  {"toolCalls":[{"id":"child-rewrite","name":"write_file","arguments":{"path":"child.txt","content":"child bytes"}}]},
                  {"text":"child done"},{"text":"parent done"},
                  {"toolCalls":[{"id":"rewrite","name":"write_file","arguments":{"path":"child.txt","content":"changed"}}]},
                  {"toolCalls":[{"id":"restore","name":"revert_turn"}]},{"text":"restored"}
                ]}
                """);
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.dir", root.resolve("snapshots").toString()), Map.of(), root.resolve("home"), project));
        var events = new java.util.concurrent.CopyOnWriteArrayList<com.cafecli.core.AgentEvent>();
        try (var session = Composition.headlessSession(config)) {
            session.subscribe(events::add);
            assertInstanceOf(SubmitResult.Completed.class, session.submit("delegate"));
            var childRestore = events.stream().filter(event -> event instanceof com.cafecli.core.AgentEvent.ToolEnd end
                            && end.agentId().equals("task_1") && end.result().message().toolName().equals("revert_turn"))
                    .map(com.cafecli.core.AgentEvent.ToolEnd.class::cast).findFirst().orElseThrow();
            assertEquals(com.cafecli.core.ToolResult.Status.OK, childRestore.result().status(), "the child shares its main run's restore service");
            assertEquals("child bytes", Files.readString(project.resolve("child.txt")));
            assertInstanceOf(SubmitResult.Completed.class, session.submit("rewrite then restore"));
            assertEquals("child bytes", Files.readString(project.resolve("child.txt")), "the next main anchor includes prior child writes");
        }
        var snapshots = new SideGitManager(project, config.snapshots()).listSnapshots(20);
        assertEquals(2, snapshots.stream().filter(snapshot -> snapshot.phase() == SnapshotPhase.PRE_TURN).count());
        assertEquals(2, snapshots.stream().filter(snapshot -> snapshot.phase() == SnapshotPhase.POST_TURN).count());
        assertTrue(snapshots.stream().filter(snapshot -> snapshot.phase() != SnapshotPhase.PRE_RESTORE)
                .allMatch(snapshot -> snapshot.turnId().startsWith("react-")));
    }

    @Test void aDisabledRestoreIsABusinessErrorWithReturnedExecutionAndDurableAllowAudit() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path script = root.resolve("script.json");
        Files.writeString(script, """
                {"responses":[{"toolCalls":[{"id":"restore","name":"revert_turn"}]},{"text":"done"}]}
                """);
        Path audit = root.resolve("audit");
        var config = ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider", "fake", "cafecli.fake.script", script.toString(),
                "cafecli.snapshot.enabled", "false", "cafecli.audit.dir", audit.toString()), Map.of(), root.resolve("home"), project));
        try (var session = Composition.headlessSession(config)) {
            session.submit("restore");
            var result = session.log().stream().filter(com.cafecli.core.log.ToolResultEntry.class::isInstance)
                    .map(com.cafecli.core.log.ToolResultEntry.class::cast).findFirst().orElseThrow();
            assertEquals(com.cafecli.core.ToolResult.Status.ERROR, result.status());
            assertEquals(List.of(new com.cafecli.ai.message.TextBlock("❌ 快照功能已关闭")), result.message().content());
            assertEquals(com.cafecli.harness.ToolExecution.Kind.RETURNED,
                    com.cafecli.harness.ToolExecution.from(new com.cafecli.core.ToolResult(result.message().content(), result.details(), result.status())).kind());
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var files = Files.list(audit)) {
            var records = files.filter(file -> file.toString().endsWith(".jsonl")).flatMap(file -> {
                try { return Files.readAllLines(file).stream(); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }).map(line -> {
                try { return json.readTree(line); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }).toList();
            assertEquals(1, records.size());
            assertEquals("revert_turn", records.getFirst().path("tool").asText());
            assertEquals("allow", records.getFirst().path("outcome").asText());
            assertEquals("none", records.getFirst().path("approver").asText());
        }
        assertFalse(Files.exists(config.snapshots().snapshotsRoot()));
    }

}
