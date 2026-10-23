package com.cafecli.app;

import com.cafecli.ai.ErrorKind;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.core.BudgetReason;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2 command line, driven the way the real entry drives it — arguments, config sources and the two streams — but
 * with maps and temporary directories for the sources and buffers for the streams: no {@code System.exit}, no
 * {@code System.out}, no real home.
 */
class CliTest {

    private static final String ANSWER_SCRIPT = """
            {"responses": [{"text": "The answer is 4."}]}
            """;

    @TempDir
    Path root;

    private Path home;
    private Path project;
    private final Map<String, String> properties = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void dirs() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void runFileWithTheFakeProviderPrintsTheScriptedAnswer() throws IOException {
        Files.writeString(project.resolve("issue.md"), "What is 2 + 2?");
        useFakeScript(ANSWER_SCRIPT);

        int exit = run("run", "--file", "issue.md");

        assertEquals("The answer is 4." + System.lineSeparator(), stdout());
        assertEquals("", stderr());
        assertEquals(0, exit);
    }

    @Test
    void headlessAutomaticallyApprovesMutationsAndAuditsToTheConfiguredDirectory() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Write a file");
        Path auditDir = root.resolve("explicit-audit");
        properties.put("cafecli.audit.dir", auditDir.toString());
        properties.put("paicli.audit.dir", root.resolve("wrong-audit").toString());
        useFakeScript("""
                {"responses":[
                  {"toolCalls":[{"id":"write","name":"write_file","arguments":{"path":"answer.txt","content":"written"}}]},
                  {"text":"done"}
                ]}
                """);
        assertEquals(0, run("run", "--file", "issue.md"));
        assertEquals("written", Files.readString(project.resolve("answer.txt")));
        assertEquals("done" + System.lineSeparator(), stdout());
        assertEquals("", stderr());
        var entries = new com.cafecli.policy.AuditLog(auditDir).readRecent(10);
        assertEquals(1, entries.size());
        assertEquals("allow", entries.getFirst().outcome());
        assertEquals("none", entries.getFirst().approver());
        assertFalse(Files.exists(root.resolve("wrong-audit")));
    }

    @Test
    void auditDirectoryAliasesAndRelativePathsFollowTheSamePrecedenceInHeadlessRuns() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Write");
        useFakeScript("""
                {"responses":[
                  {"toolCalls":[{"id":"write","name":"write_file","arguments":{"path":"answer.txt","content":"written"}}]},
                  {"text":"done"}
                ]}
                """);
        List<Map<String, String>> configured = List.of(Map.of("paicli.audit.dir", "legacy-property"), Map.of(), Map.of(), Map.of(),
                Map.of("cafecli.audit.dir", " ", "paicli.audit.dir", "ignored"));
        List<Map<String, String>> environments = List.of(Map.of("CAFECLI_AUDIT_DIR", "ignored"),
                Map.of("CAFECLI_AUDIT_DIR", "new-environment", "PAICLI_AUDIT_DIR", "ignored"),
                Map.of("PAICLI_AUDIT_DIR", "legacy-environment"), Map.of(), Map.of("CAFECLI_AUDIT_DIR", "blank-property"));
        List<Path> expected = List.of(project.resolve("legacy-property"), project.resolve("new-environment"),
                project.resolve("legacy-environment"), home.resolve(".cafecli/audit"), project.resolve("blank-property"));
        for (int i = 0; i < configured.size(); i++) {
            var perRun = new HashMap<>(properties);
            perRun.putAll(configured.get(i));
            int exit = Cli.run(new String[]{"run", "--file", "issue.md"}, new ConfigSources(perRun, environments.get(i), home, project),
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            assertEquals(0, exit, stderr());
            assertEquals(1, new com.cafecli.policy.AuditLog(expected.get(i)).readRecent(10).size(), "case " + i);
            assertEquals("", stderr());
        }
        assertFalse(Files.exists(project.resolve("ignored")));
    }

    @Test
    void theIssueFileAndTheScriptMayBeGivenAsAbsolutePathsAndTheFlagsInAnyCase() throws IOException {
        Path issue = Files.writeString(root.resolve("elsewhere.md"), "What is 2 + 2?");
        useFakeScript(ANSWER_SCRIPT);
        properties.put("cafecli.fake.script", project.resolve("script.json").toString());

        int exit = run("RUN", "--FILE", issue.toString(), "--strategy=react");

        assertEquals("The answer is 4." + System.lineSeparator(), stdout());
        assertEquals(0, exit);
    }

    @Test
    void aModelThatNeverStopsEndsAtTheHardCapAndPrintsV1sCapMessageAsTheAnswer() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Loop");
        useFakeScript("""
                {"responses": [
                  {"toolCalls": [{"id": "call_1", "name": "read_file", "arguments": {"path": "a.txt"}}]},
                  {"toolCalls": [{"id": "call_2", "name": "read_file", "arguments": {"path": "a.txt"}}]},
                  {"toolCalls": [{"id": "call_3", "name": "read_file", "arguments": {"path": "a.txt"}}]}
                ]}
                """);
        properties.put("cafecli.react.hard.max.iterations", "2");

        int exit = run("run", "--file", "issue.md");

        // v1's Agent.run returns its failure texts as the answer, and Main prints the answer on stdout, exit 0.
        assertEquals("❌ 达到硬轮数上限（2），已强制收尾" + System.lineSeparator(), stdout());
        assertEquals("", stderr());
        assertEquals(0, exit);
    }

    @Test
    void aRepeatedToolBatchPrintsV1sStagnationDescription() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Loop");
        useFakeScript("""
                {"responses":[
                  {"toolCalls":[{"id":"one","name":"read_file","arguments":{"path":"missing.txt"}}]},
                  {"toolCalls":[{"id":"two","name":"read_file","arguments":{"path":"missing.txt"}}]},
                  {"toolCalls":[{"id":"three","name":"read_file","arguments":{"path":"missing.txt"}}]}
                ]}
                """);
        assertEquals(0, run("run", "--file", "issue.md"));
        assertEquals("❌ 检测到连续 3 轮重复的工具调用，疑似死循环，已强制收尾" + System.lineSeparator(), stdout());
        assertEquals("", stderr());
    }

    @Test
    void anExplicitTokenBudgetPrintsV1sTokenCountsAndDescription() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Read");
        properties.put("cafecli.react.token.budget", "100");
        useFakeScript("""
                {"responses":[
                  {"toolCalls":[{"id":"one","name":"read_file","arguments":{"path":"missing.txt"}}],
                   "usage":{"input":80,"output":40}}
                ]}
                """);
        assertEquals(0, run("run", "--file", "issue.md"), stderr());
        assertEquals("❌ Token 预算已用尽（120 / 100），任务被强制收尾" + System.lineSeparator(), stdout());
        assertEquals("", stderr());
    }

    @Test
    void aFailedRequestPrintsV1sLlmFailureMessageAsTheAnswer() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Hi");
        useFakeScript("""
                {"responses": [{"error": {"kind": "fatal", "message": "401 invalid api key"}}]}
                """);

        int exit = run("run", "--file", "issue.md");

        assertEquals("❌ 调用 LLM 失败: 401 invalid api key" + System.lineSeparator(), stdout());
        assertEquals("", stderr());
        assertEquals(0, exit);
    }

    /** No headless run can be cancelled (nothing cancels it), but the outcome still reads as v1's would. */
    @Test
    void everyOutcomeIsPrintedAsV1PrintsItsAnswerOnStdoutWithExit0() {
        assertReported(new RunOutcome.Cancelled(), "⏹️ 已取消当前任务。" + System.lineSeparator());
        assertReported(new RunOutcome.BudgetExhausted(new BudgetReason.IterationCap(50)),
                "❌ 达到硬轮数上限（50），已强制收尾" + System.lineSeparator());
        assertReported(new RunOutcome.Failed(ErrorKind.RETRYABLE, "429 rate limited"),
                "❌ 调用 LLM 失败: 429 rate limited" + System.lineSeparator());
        assertReported(new RunOutcome.Completed(answer("  ")), "");
        assertReported(new RunOutcome.Completed(answer("Done.")), "Done." + System.lineSeparator());
        BudgetReason tokens = new BudgetReason() {
            @Override
            public String toString() {
                return "token budget";
            }
        };
        assertReported(new RunOutcome.BudgetExhausted(tokens), "❌ 预算已用尽（token budget），已强制收尾"
                + System.lineSeparator());
    }

    private void assertReported(RunOutcome outcome, String expectedStdout) {
        out.reset();

        int exit = Cli.report(outcome, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(expectedStdout, stdout(), outcome.toString());
        assertEquals(0, exit, outcome.toString());
    }

    private static AssistantMessage answer(String text) {
        return new AssistantMessage(List.of(new TextBlock(text)), StopReason.END_TURN, null, "fake", "fake-model");
    }

    @Test
    void providersWhoseAdaptersAreNotPortedAreNotAvailableInV2Yet() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Hi");

        int exit = run("run", "--file", "issue.md");

        assertEquals("", stdout());
        assertTrue(stderr().startsWith("❌ not available in v2 yet: provider glm."), stderr());
        assertEquals(1, exit);
    }

    @Test
    void theFakeProviderWithoutAScriptSaysWhichKnobIsMissing() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Hi");
        properties.put("cafecli.provider", "fake");

        int exit = run("run", "--file", "issue.md");

        assertTrue(stderr().contains("cafecli.fake.script"), stderr());
        assertEquals(1, exit);
    }

    @Test
    void anInvalidScriptIsReportedAndNothingRuns() throws IOException {
        Files.writeString(project.resolve("issue.md"), "Hi");
        useFakeScript("{\"responses\": [{}]}");

        int exit = run("run", "--file", "issue.md");

        assertEquals("", stdout());
        assertTrue(stderr().startsWith("❌ invalid fake provider script: "), stderr());
        assertTrue(stderr().contains("responses[0] is empty"), stderr());
        assertEquals(1, exit);
    }

    @Test
    void aMissingIssueFileIsV1sRunFailure() throws IOException {
        useFakeScript(ANSWER_SCRIPT);

        int exit = run("run", "--file", "missing.md");

        assertTrue(stderr().startsWith("❌ 运行失败: "), stderr());
        assertTrue(stderr().contains("missing.md"), stderr());
        assertEquals(1, exit);
    }

    @Test
    void anUnreadableConfigJsonIsV1sWarningAndTheRunGoesOn() throws IOException {
        Files.writeString(Files.createDirectories(home.resolve(".cafecli")).resolve("config.json"), "{oops");
        Files.writeString(project.resolve("issue.md"), "What is 2 + 2?");
        useFakeScript(ANSWER_SCRIPT);

        int exit = run("run", "--file", "issue.md");

        assertTrue(stderr().startsWith("⚠️ 配置文件读取失败，使用默认配置: "), stderr());
        assertEquals("The answer is 4." + System.lineSeparator(), stdout());
        assertEquals(0, exit);
    }

    @Test
    void runWithoutAFileIsV1sUsageError() {
        int exit = run("run");

        assertEquals("❌ cafecli run 需要 --file <issue.md>" + System.lineSeparator(), stderr());
        assertEquals(2, exit);
    }

    @Test
    void aStrategyOtherThanReactIsV1sUsageError() {
        int exit = run("run", "--file", "issue.md", "--strategy", "team");

        assertTrue(stderr().startsWith("❌ 不支持的 --strategy=team"), stderr());
        assertEquals(2, exit);
    }

    @Test
    void anInteractiveLaunchIsNotAvailableInV2Yet() {
        int exit = run();

        assertEquals("", stdout());
        assertTrue(stderr().startsWith("❌ not available in v2 yet: interactive mode."), stderr());
        assertEquals(2, exit);
    }

    @Test
    void anyOtherCommandIsNotAvailableInV2Yet() {
        int exit = run("chat");

        assertEquals("", stdout());
        assertTrue(stderr().startsWith("❌ not available in v2 yet: the `chat` command."), stderr());
        assertEquals(2, exit);
    }

    @Test
    void serveHttpIsRemovedInV2() {
        // PRD intentional change 7: not postponed, gone.
        int exit = run("SERVE", "--port", "8080", "--HTTP");

        assertEquals("", stdout());
        assertTrue(stderr().startsWith("❌ removed in v2: `serve --http`."), stderr());
        assertEquals(2, exit);
    }

    @Test
    void deepSeekWithoutAKeyExplainsTheMissingCredentialBeforeStarting() throws IOException {
        properties.put("cafecli.provider", "deepseek");
        Files.writeString(project.resolve("issue.md"), "Hi");
        int exit = run("run", "--file", "issue.md");
        assertEquals(1, exit);
        assertTrue(stderr().contains("DEEPSEEK_API_KEY"), stderr());
        assertFalse(stderr().contains("not available"), stderr());
    }

    @Test
    void aDeepSeekKeyBuildsTheRealSessionAndProceedsToReadingTheIssueFile() throws IOException {
        Files.writeString(Files.createDirectories(home.resolve(".cafecli")).resolve("config.json"), """
                {"defaultProvider":"deepseek","providers":{"deepseek":{"apiKey":"fixture-key","model":"custom-deepseek"}}}
                """);
        int exit = run("run", "--file", "missing.md");
        assertEquals(1, exit);
        assertTrue(stderr().startsWith("❌ 运行失败: "), stderr());
        assertTrue(stderr().contains("missing.md"), stderr());
        assertEquals("", stdout());
    }

    private void useFakeScript(String json) throws IOException {
        Files.writeString(project.resolve("script.json"), json);
        properties.put("cafecli.provider", "fake");
        properties.put("cafecli.fake.script", "script.json");
    }

    private int run(String... args) {
        ConfigSources sources = new ConfigSources(properties, Map.of(), home, project);
        return Cli.run(args, sources, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
