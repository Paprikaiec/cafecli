package com.cafecli.parity;

import com.cafecli.agent.Agent;
import com.cafecli.agent.ForegroundAgentSpawner;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.ai.Model;
import com.cafecli.cli.AgentTraceWriter;
import com.cafecli.harness.*;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.tasks.SubAgentsCapability;
import com.cafecli.harness.tasks.TaskNotification;
import com.cafecli.harness.trace.TraceCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Headless spawning is synchronous. Busy delivery therefore uses both actual interactive session facades. */
class BusyNotificationParityTest {
    @TempDir Path root;
    private static final Path SCRIPT = Path.of("src/test/resources/parity/busy-notification.json");
    private static final String NOTIFICATION = "<task-notification task_id=\"task_1\">\n[task_1 · done]\n\nchild fixture result\n</task-notification>";

    @Test void actualBusyCompletionChangesOnlyItsEligibleBoundaryAndTheDerivedExtraV1Run() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        var scenario = new ParityScript(SCRIPT).scenario;
        ParityFiles.seed(project, scenario.path("files"));
        String previousHome = System.getProperty("user.home"), previousMemory = System.getProperty("paicli.memory.dir");
        System.setProperty("user.home", root.resolve("home").toString()); System.setProperty("paicli.memory.dir", root.resolve("memory").toString());
        Path v1 = root.resolve("v1.jsonl"), v2 = root.resolve("v2.jsonl");
        try {
            var legacy = legacy(project, v1);
            ParityEvidence.result("busy-notification", "v1", legacy.answer(), legacy.files());
            ParityFiles.erase(project); ParityFiles.seed(project, scenario.path("files"));
            var modern = modern(project, v2);
            ParityEvidence.result("busy-notification", "v2", modern.answer(), modern.files());
            assertEquals(scenario.path("answer").asText(), legacy.answer()); assertEquals(legacy.answer(), modern.answer());
            ParityFiles.compare(ParityFiles.expected(scenario.path("files")), legacy.files());
            ParityFiles.compare(ParityFiles.expected(scenario.path("files")), modern.files());
            ParityFiles.compare(legacy.files(), modern.files());
            ParityEvidence.save("busy-notification", v1, v2, List.of(10));
            ParityComparison.compare("busy-notification", ParityComparison.trace(v1), ParityComparison.trace(v2));
            var summary1 = ParityEvidence.normalize(v1); var summary2 = ParityEvidence.normalize(v2);
            ParityComparison.summaries("busy-notification", summary1, summary2);
            ParityComparison.rejectsUnexplainedDifferences("busy-notification", ParityComparison.trace(v1), ParityComparison.trace(v2), summary1, summary2);
        } finally {
            if (previousHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", previousHome);
            if (previousMemory == null) System.clearProperty("paicli.memory.dir"); else System.setProperty("paicli.memory.dir", previousMemory);
        }
    }

    private Result legacy(Path project, Path trace) throws Exception {
        var script = new ParityScript(SCRIPT); var client = script.client();
        var registry = new ToolRegistry(); registry.setProjectPath(project.toString());
        var tasks = new TaskRegistry(); var spawner = new ForegroundAgentSpawner(client, tasks, registry);
        registry.setAgentSpawner(spawner); var agent = new Agent(client, registry);
        var answers = new CopyOnWriteArrayList<String>();
        var session = new com.cafecli.agent.AgentSession(agent, tasks, answers::add);
        spawner.setBackgroundCompletionSink(session::onBackgroundTaskDone);
        session.start();
        try {
            session.postEvent(new com.cafecli.agent.AgentSession.UserSubmit(script.inputs().getFirst()));
            completeChildWhileParentIsBusy(script);
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(List.of("parent complete", "parent complete"), answers, "v1 queues exactly one new run");
            assertEquals(5, script.legacyRequests.size(), "four main responses and one child response");
            var main = script.legacyRequests.stream().filter(request -> !request.stream().filter(message -> message.role().equals("user"))
                    .findFirst().orElseThrow().content().startsWith("CHILD:")).toList();
            assertFalse(main.get(2).toString().contains(NOTIFICATION), "v1 current run has no busy completion");
            assertEquals(NOTIFICATION, main.get(3).getLast().content());
            try (var writer = Files.newBufferedWriter(trace)) { AgentTraceWriter.write(writer, agent.getConversationHistory(), agent.getLastRunUsage(), "fake", "fake-model", 0); }
            return new Result(answers.getLast(), ParityFiles.read(project));
        } finally { script.release("child"); script.release("parent"); session.stop(); registry.getSnapshotService().close(); }
    }

    private Result modern(Path project, Path trace) throws Exception {
        var script = new ParityScript(SCRIPT); var provider = script.provider();
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), project));
        var model = new Model("fake-model", "fake", "fake", 128000, 8192, Set.of());
        var workspace = new WorkspaceCapability(project);
        var factory = new AgentFactory(provider, new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                root.resolve("home/.cafecli/prompts"), project.resolve(".cafecli/prompts"))), Duration.ofSeconds(90));
        var tasks = new SubAgentsCapability(factory, AgentProfile.mainAgent(model, List.of(workspace), 20));
        var answers = new CopyOnWriteArrayList<String>();
        try (var session = AgentSession.builder().config(config).factory(factory).model(model).interactive(true)
                .capabilities(List.of(workspace, tasks, new TraceCapability(trace))).build()) {
            session.subscribeCompletion(completed -> answers.add(RunOutcomeText.format(assertInstanceOf(SessionCompletion.Completed.class, completed).outcome())));
            session.submit(script.inputs().getFirst());
            completeChildWhileParentIsBusy(script);
            try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                worker.submit(() -> { session.awaitIdle(); return null; }).get(5, TimeUnit.SECONDS);
            }
            assertEquals(List.of("parent complete"), answers, "v2 consumes completion in the existing run");
            assertEquals(4, provider.requests().size(), "three main responses and one child response");
            var main = provider.requests().stream().filter(request -> !request.context().messages().getFirst().toString().contains("CHILD:")).toList();
            assertTrue(main.get(2).context().messages().contains(com.cafecli.ai.message.UserMessage.text(NOTIFICATION)));
            assertEquals(1, session.log().stream().filter(TaskNotification.class::isInstance).count());
            assertEquals(1, session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count(),
                    "notification preserves custom source provenance");
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
            return new Result(answers.getLast(), ParityFiles.read(project));
        } finally { script.release("child"); script.release("parent"); }
    }

    private record Result(String answer, Map<String, String> files) { }

    private static void completeChildWhileParentIsBusy(ParityScript script) throws Exception {
        script.awaitEntered("child"); script.awaitEntered("parent");
        try {
            script.release("child");
            Thread child = script.workers.get("child"); child.join(5000);
            assertFalse(child.isAlive(), "child completion publication finishes before parent continues");
        } finally { script.release("parent"); }
    }
}
