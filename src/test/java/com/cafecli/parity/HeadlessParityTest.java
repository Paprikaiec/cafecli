package com.cafecli.parity;

import com.cafecli.agent.Agent;
import com.cafecli.ai.Model;
import com.cafecli.cli.HeadlessRunner;
import com.cafecli.cli.RunCommand;
import com.cafecli.harness.*;
import com.cafecli.harness.config.*;
import com.cafecli.harness.hooks.HooksCapability;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.trace.TraceCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.hitl.HitlHandler;
import com.cafecli.hitl.HitlToolRegistry;
import com.cafecli.hook.*;
import com.cafecli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary seam 4: runs both real spines, never synthesizes a reference conversation. */
class HeadlessParityTest {
    @TempDir Path root;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plain-answer", "read-edit-test", "approval-denied", "pre-tool-block", "finish-gate", "skill-load", "headless-background", "compaction", "mcp-tool", "cancel-mid-run", "retained-reasoning", "budget-completed-usage", "failed-completed-usage", "headless-snapshot", "headless-parallel-children", "unicode-blank-reasoning", "control-only-reasoning", "mixed-unicode-blank-reasoning", "mixed-controls-reasoning", "nonblank-unicode-reasoning", "project-config-file"})
    void theSameScriptHasOnlyApprovedDifferencesThroughBothHeadlessSpines(String name) throws Exception {
        Path fixture = Path.of("src/test/resources/parity", name + ".json");
        var script = new ParityScript(fixture);
        Path project = Files.createDirectories(root.resolve("project"));
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> properties = new HashMap<>(Map.of("user.home", home.toString(), "user.dir", project.toString(),
                "cafecli.memory.dir", root.resolve("memory").toString(), "cafecli.snapshot.dir", root.resolve("snapshots").toString()));
        script.scenario.path("properties").properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue().asText()));
        new HashMap<>(properties).forEach((key, value) -> { if (key.startsWith("cafecli.")) properties.put("paicli." + key.substring(8), value); });
        Map<String, String> previous = new HashMap<>();
        properties.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
        try {
            ParityFiles.seed(project, script.scenario.path("files"));
            Result v1 = legacy(fixture, project);
            ParityFiles.erase(project);
            ParityFiles.seed(project, script.scenario.path("files"));
            Result v2 = modern(fixture, project, home);
            ParityEvidence.save(name, v1.trace(), v2.trace(), exceptions(name));
            ParityEvidence.result(name, "v1", v1.answer(), v1.files()); ParityEvidence.result(name, "v2", v2.answer(), v2.files());
            assertEquals(v1.answer(), v2.answer(), name + ": final answer");
            if (name.equals("headless-snapshot")) {
                var adjusted = new TreeMap<>(v1.files());
                assertEquals("YWZ0ZXIK", adjusted.put("note.txt", "YmVmb3JlCg=="), "exception 11: only the edited file can differ after headless revert");
                assertEquals(adjusted, v2.files());
            } else assertEquals(v1.files(), v2.files(), name + ": actual file bytes (base64)");
            assertExpected(script, v1, "V1"); assertExpected(script, v2, "V2");
            ParityComparison.compare(name, ParityComparison.trace(v1.trace()), ParityComparison.trace(v2.trace()));
            JsonNode summary1 = ParityEvidence.normalize(v1.trace()), summary2 = ParityEvidence.normalize(v2.trace());
            ParityComparison.summaries(name, summary1, summary2);
            ParityComparison.rejectsUnexplainedDifferences(name, ParityComparison.trace(v1.trace()), ParityComparison.trace(v2.trace()), summary1, summary2);
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private static List<Integer> exceptions(String scenario) {
        return switch (scenario) {
            case "compaction" -> List.of(5);
            case "cancel-mid-run" -> List.of(9);
            case "headless-snapshot" -> List.of(11);
            case "retained-reasoning", "budget-completed-usage", "failed-completed-usage", "control-only-reasoning",
                    "mixed-unicode-blank-reasoning", "mixed-controls-reasoning", "nonblank-unicode-reasoning" -> List.of(12);
            default -> List.of();
        };
    }

    @Test void unexplainedContentFieldsAndRecordTypesCannotPassTheComparator() throws Exception {
        var expected = List.of(ParityScript.JSON.readTree("{\"type\":\"assistant\",\"text\":\"kept\"}"));
        for (String mutation : List.of("{\"type\":\"assistant\",\"text\":\"lost\"}",
                "{\"type\":\"assistant\",\"text\":\"kept\",\"new_field\":true}",
                "{\"type\":\"user\",\"text\":\"kept\"}")) {
            var actual = List.of(ParityScript.JSON.readTree(mutation));
            assertThrows(AssertionError.class, () -> ParityComparison.compare("plain-answer", expected, actual));
        }
    }

    @Test void invalidDurationsCannotBeHiddenByTimingNormalization() throws Exception {
        for (String record : List.of("{\"type\":\"result\"}", "{\"type\":\"result\",\"wall_ms\":-1}",
                "{\"type\":\"result\",\"wall_ms\":\"1\"}", "{\"type\":\"result\",\"wall_ms\":0.5}")) {
            Path trace = root.resolve("invalid.jsonl"); Files.writeString(trace, record);
            assertThrows(AssertionError.class, () -> ParityComparison.trace(trace));
        }
    }

    private Result legacy(Path fixture, Path project) throws Exception {
        var script = new ParityScript(fixture); var client = script.client();
        ToolRegistry registry = script.scenario.path("denyApproval").asBoolean() ? new HitlToolRegistry(new HitlHandler() {
            @Override public com.cafecli.hitl.ApprovalResult requestApproval(com.cafecli.hitl.ApprovalRequest request) {
                return com.cafecli.hitl.ApprovalResult.reject("fixture denial");
            }
            @Override public boolean isEnabled() { return true; }
            @Override public void setEnabled(boolean enabled) { }
        }) : new ToolRegistry();
        registry.setProjectPath(project.toString());
        if (script.scenario.path("blockHook").asBoolean()) {
            var dispatcher = new HookDispatcher(); dispatcher.register(HookEvent.PRE_TOOL_USE, blockingTenant()); registry.setHookDispatcher(dispatcher);
        }
        var agent = new Agent(client, registry);
        if (script.scenario.path("skills").asBoolean()) {
            var paths = SkillsConfig.under(new ConfigHomes(homePath(), project.resolve(".cafecli")));
            new com.cafecli.skill.SkillBuiltinExtractor(paths.builtinCache()).extractAll();
            var skills = new com.cafecli.skill.SkillRegistry(paths.builtinCache(), paths.userSkills(), paths.projectSkills(), new com.cafecli.skill.SkillStateStore(paths.stateFile()));
            skills.reload(); var buffer = new com.cafecli.skill.SkillContextBuffer();
            registry.setSkillRegistry(skills); registry.setSkillContextBuffer(buffer); agent.setSkillRegistry(skills); agent.setSkillContextBuffer(buffer);
        }
        if (script.scenario.path("tasks").asBoolean()) registry.setAgentSpawner(new com.cafecli.agent.ForegroundAgentSpawner(client, new com.cafecli.agent.task.TaskRegistry(), registry));
        Path trace = root.resolve("v1.jsonl"); String answer = "";
        ParityMcpServer server = script.scenario.path("mcp").asBoolean() ? new ParityMcpServer() : null;
        byte[] ownedMcpConfiguration = server == null ? null : server.configure(project);
        var manager = server == null ? null : new com.cafecli.mcp.McpServerManager(registry, project,
                new com.cafecli.mcp.config.McpConfigLoader(homePath().resolve("mcp.json"), project.resolve(".cafecli/mcp.json"), project, Map.of(), Map.of(), root.resolve("home")));
        try (server; manager; var snapshots = registry.getSnapshotService()) {
            if (manager != null) { manager.loadConfiguredServers(); manager.startAll(); }
            for (String input : script.inputs()) {
                Path issue = root.resolve("issue.md"); Files.writeString(issue, input);
                if (script.scenario.path("cancel").asBoolean() && input.equals(script.inputs().getFirst())) {
                    var token = new com.cafecli.runtime.CancellationToken(); agent.setCancellationToken(token);
                    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        var pending = executor.submit(() -> HeadlessRunner.run(new RunCommand(issue, trace), agent, client));
                        try {
                            assertTrue(script.held.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            assertFalse(pending.isDone()); token.cancel();
                            assertFalse(pending.isDone(), "exception 9: v1 cannot interrupt the model stream");
                        } finally { script.finishLegacyStream.countDown(); }
                        assertEquals("⏹️ 已取消当前任务。", pending.get(5, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    agent.setCancellationToken(new com.cafecli.runtime.CancellationToken());
                } else answer = HeadlessRunner.run(new RunCommand(issue, trace), agent, client);
            }
            if (script.scenario.path("cancel").asBoolean()) assertEquals(List.of("system", "user", "user"),
                    script.legacyRequests.getLast().stream().map(com.cafecli.llm.LlmClient.Message::role).toList());
            if (server != null) assertEquals(1, server.calls.get());
            return new Result(answer, ParityFiles.read(project, ownedMcpConfiguration), trace);
        }
    }

    private Result modern(Path fixture, Path project, Path home) throws Exception {
        var script = new ParityScript(fixture); var provider = script.provider();
        Map<String, String> properties = new HashMap<>(); properties.put("cafecli.provider", "fake");
        script.scenario.path("properties").properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue().asText()));
        ParityMcpServer server = script.scenario.path("mcp").asBoolean() ? new ParityMcpServer() : null;
        try (server) {
            byte[] ownedMcpConfiguration = server == null ? null : server.configure(project);
            var config = ConfigLoader.load(new ConfigSources(properties, Map.of(), home, project));
            Path trace = root.resolve("v2.jsonl"); String answer = "";
            var registrations = script.scenario.path("blockHook").asBoolean()
                    ? List.of(new HooksCapability.Registration(HookEvent.PRE_TOOL_USE, blockingTenant())) : List.<HooksCapability.Registration>of();
            var permission = script.scenario.path("denyApproval").asBoolean()
                    ? new PermissionCapability((request, token) -> ApprovalResult.reject("fixture denial"), true) : PermissionCapability.headless();
            var capabilities = new ArrayList<Capability>(List.of(new WorkspaceCapability(project), permission,
                    new HooksCapability(config.hooks(), registrations, null), new TraceCapability(trace)));
            if (server != null) {
                var audit = new com.cafecli.harness.permission.AuditCapability(permission, new com.cafecli.policy.AuditLog(root.resolve("audit")));
                capabilities.add(audit); capabilities.add(new com.cafecli.harness.mcp.McpCapability(config.mcp(), audit));
            }
            if (script.scenario.path("skills").asBoolean()) capabilities.add(new com.cafecli.harness.skills.SkillsCapability(config.skills()));
            if (script.scenario.path("compact").asBoolean()) capabilities.add(new com.cafecli.harness.context.CompactionCapability(provider));
            Model model = new Model("fake-model", "fake", "fake", script.scenario.path("window").asInt(128000), 8192, Set.of());
            com.cafecli.snapshot.SnapshotService snapshots = script.scenario.path("snapshot").asBoolean()
                    ? new com.cafecli.snapshot.SnapshotService(new com.cafecli.snapshot.SideGitManager(project,
                    new com.cafecli.snapshot.SnapshotConfig(true, root.resolve("v2-snapshots"), 50, List.of(".git")))) : null;
            if (snapshots != null) {
                var snapshotCapability = new com.cafecli.harness.snapshot.SnapshotCapability(snapshots, ignored -> {});
                capabilities.addFirst(new Capability() { @Override public void attachSession(SessionRegistrar session) { session.onClose(snapshotCapability); } });
                capabilities.add(snapshotCapability);
            }
            var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(home.resolve(".cafecli/prompts"), project.resolve(".cafecli/prompts"))), config.workspace().toolBatchTimeout());
            if (script.scenario.path("tasks").asBoolean()) capabilities.add(new com.cafecli.harness.tasks.SubAgentsCapability(factory, AgentProfile.mainAgent(model, List.copyOf(capabilities), config.maxIterations()), config.tasks()));
            var presenter = new HeadlessRunPresenter();
            try (var session = AgentSession.builder().config(config).factory(factory)
                    .model(model).capabilities(capabilities).build()) {
                session.subscribe(presenter);
                for (String input : script.inputs()) {
                    if (script.scenario.path("cancel").asBoolean() && input.equals(script.inputs().getFirst())) {
                        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            var pending = executor.submit(() -> session.submit(input));
                            try {
                                assertTrue(script.held.await(5, java.util.concurrent.TimeUnit.SECONDS)); assertFalse(pending.isDone());
                                assertTrue(session.cancel());
                                assertInstanceOf(com.cafecli.core.RunOutcome.Cancelled.class,
                                        assertInstanceOf(SubmitResult.Completed.class, pending.get(5, java.util.concurrent.TimeUnit.SECONDS)).outcome());
                            } finally { session.cancel(); }
                        }
                    } else answer = presenter.format(assertInstanceOf(SubmitResult.Completed.class, session.submit(input)).outcome());
                }
                if (snapshots != null) assertEquals(1, snapshots.listSnapshots(20).stream().filter(snapshot -> snapshot.phase() == com.cafecli.snapshot.SnapshotPhase.PRE_TURN).count(),
                        "the headless main has an anchor and its child creates no independent anchor");
                if (script.scenario.path("cancel").asBoolean()) {
                    assertEquals(List.of(com.cafecli.ai.message.UserMessage.text(script.inputs().getFirst()),
                            com.cafecli.ai.message.UserMessage.text(script.inputs().getLast())), provider.requests().getLast().context().messages());
                }
            }
            if (server != null) assertEquals(1, server.calls.get());
            return new Result(answer, ParityFiles.read(project, ownedMcpConfiguration), trace);
        }
    }

    private Path homePath() { return root.resolve("home/.cafecli"); }

    private static HookTenant blockingTenant() { return new HookTenant() {
        @Override public HookDecision evaluate(HookPayload payload) { return HookDecision.block("fixture policy feedback"); }
        @Override public String name() { return "parity-policy"; }
    }; }

    private static void assertExpected(ParityScript script, Result result, String variant) {
        assertEquals(script.scenario.path("answer").asText(), result.answer());
        JsonNode expected = script.scenario.has("expectedFiles" + variant) ? script.scenario.path("expectedFiles" + variant)
                : script.scenario.has("expectedFiles") ? script.scenario.path("expectedFiles") : script.scenario.path("files");
        ParityFiles.compare(ParityFiles.expected(expected), result.files());
    }

    private record Result(String answer, Map<String, String> files, Path trace) { }
}
