package com.cafecli.tool;

import com.cafecli.agent.task.AgentSpawner;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.hitl.HitlHandler;
import com.cafecli.hitl.HitlToolRegistry;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookDispatcher;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.hook.HookTenant;
import com.cafecli.mcp.InMemoryTransport;
import com.cafecli.mcp.McpClient;
import com.cafecli.mcp.protocol.McpCallToolResult;
import com.cafecli.mcp.protocol.McpToolDescriptor;
import com.cafecli.runtime.CancellationContext;
import com.cafecli.runtime.CancellationToken;
import com.cafecli.snapshot.SideGitManager;
import com.cafecli.snapshot.SnapshotConfig;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.tool.ToolOutput.Status;
import com.cafecli.tool.ToolRegistry.ToolExecutionResult;
import com.cafecli.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolOutput.Status} 的等价性证明：驱动工具管线里每一个真实的结果产生点，断言
 * (1) 状态是约定的那个；(2) 基于状态的 {@link WebTools#isUsableMcpOutput} 与重构前的前缀判据给出同一个答案。
 *
 * <p>内置工具体那一节只断言 (1) 与逐字文本：内置工具的业务失败文本（「读取文件失败」…）在前缀判据下是「可用」，
 * 在状态判据下是 ERROR——两者在这里本就不一致，但 {@code isUsableMcpOutput} 只看 StepSearch 的 MCP 结果，碰不到它们。
 */
class ToolOutputStatusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUDIT_DIR = "paicli.audit.dir";
    private static final String RAG_DIR = "paicli.rag.dir";

    @TempDir
    Path tempDir;

    private String previousAuditDir;

    @BeforeEach
    void isolateAudit() {
        previousAuditDir = System.getProperty(AUDIT_DIR);
        System.setProperty(AUDIT_DIR, tempDir.resolve("audit").toString());
    }

    @AfterEach
    void restoreAudit() {
        restore(AUDIT_DIR, previousAuditDir);
    }

    /**
     * 冻结副本（FROZEN，不要随产品代码修改）：重构前 {@code WebTools.isUsableMcpOutput} 的前缀嗅探判据，逐字照抄，
     * 只作等价性 oracle。
     */
    private static boolean frozenPrefixOracle(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) {
            return false;
        }
        String text = output.text().trim();
        return !text.startsWith("[HITL]")
                && !text.startsWith("🛡️")
                && !text.startsWith("工具执行失败")
                && !text.startsWith("未知工具")
                && !text.startsWith("MCP 工具返回错误");
    }

    private static void assertProducer(ToolOutput output, Status expected) {
        assertNotNull(output);
        assertEquals(expected, output.status(), output.text());
        assertEquals(expected == Status.OK, output.isOk(), output.text());
        assertEquals(frozenPrefixOracle(output), WebTools.isUsableMcpOutput(output),
                "状态判据必须与冻结的前缀判据一致: status=" + expected + " text=" + output.text());
    }

    // ------------------ executeToolOutput：单次调用路径（WebTools 经此调用 StepSearch） ------------------

    @Test
    void successfulBuiltInIsOkAndUsable() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        ToolRegistry registry = registry();

        ToolOutput output = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\"}");

        assertProducer(output, Status.OK);
        assertTrue(WebTools.isUsableMcpOutput(output));
    }

    @Test
    void unknownToolIsError() {
        ToolOutput output = registry().executeToolOutput("no_such_tool", "{}");

        assertEquals("未知工具: no_such_tool", output.text());
        assertProducer(output, Status.ERROR);
    }

    @Test
    void policyDeniedPathIsDenied() {
        ToolOutput output = registry().executeToolOutput("read_file", "{\"path\":\"../outside.txt\"}");

        assertTrue(output.text().startsWith("🛡️ 策略拒绝: "), output.text());
        assertProducer(output, Status.DENIED);
    }

    @Test
    void builtInThrowingOnMalformedJsonIsError() {
        ToolOutput output = registry().executeToolOutput("read_file", "{\"path\": ");

        assertTrue(output.text().startsWith("工具执行失败: "), output.text());
        assertProducer(output, Status.ERROR);
    }

    @Test
    void preToolUseHookBlockIsBlockedAndStaysUsable() {
        String prev = System.setProperty(HookProperties.PRE_TOOL_USE_ENABLED, "true");
        try {
            ToolRegistry plain = registry();
            plain.setHookDispatcher(blockingDispatcher("nope"));
            ToolOutput fromPlain = plain.executeToolOutput("read_file", "{\"path\":\"a.txt\"}");

            HitlToolRegistry hitl = new HitlToolRegistry(new StubHitl(req -> ApprovalResult.approve()));
            hitl.setProjectPath(tempDir.toString());
            hitl.setHookDispatcher(blockingDispatcher("nope"));
            ToolOutput fromHitl = hitl.executeToolOutput("write_file", "{\"path\":\"b.txt\",\"content\":\"x\"}");

            for (ToolOutput output : List.of(fromPlain, fromHitl)) {
                assertEquals("[Hook 拦截 / PreToolUse]\nnope", output.text());
                assertProducer(output, Status.BLOCKED);
                assertTrue(WebTools.isUsableMcpOutput(output), "BLOCKED 原样回给模型，不触发回退搜索");
            }
            assertFalse(Files.exists(tempDir.resolve("b.txt")));
        } finally {
            restore(HookProperties.PRE_TOOL_USE_ENABLED, prev);
        }
    }

    @Test
    void hitlRejectIsDenied() {
        HitlToolRegistry registry = new HitlToolRegistry(new StubHitl(req -> ApprovalResult.reject("too risky")));
        registry.setProjectPath(tempDir.toString());

        ToolOutput output = registry.executeToolOutput("write_file", "{\"path\":\"b.txt\",\"content\":\"x\"}");

        assertEquals("[HITL] 操作已被拒绝：too risky", output.text());
        assertProducer(output, Status.DENIED);
    }

    @Test
    void hitlSkipIsDenied() {
        HitlToolRegistry registry = new HitlToolRegistry(new StubHitl(req -> ApprovalResult.skip()));
        registry.setProjectPath(tempDir.toString());

        ToolOutput output = registry.executeToolOutput("write_file", "{\"path\":\"b.txt\",\"content\":\"x\"}");

        assertEquals("[HITL] 操作已被跳过", output.text());
        assertProducer(output, Status.DENIED);
    }

    @Test
    void cancelledCallIsCancelledAndStaysUsable() {
        ToolRegistry registry = registry();
        CancellationToken token = CancellationContext.startRun();
        token.cancel();
        ToolOutput output;
        try {
            output = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\"}");
        } finally {
            CancellationContext.clear(token);
        }

        assertEquals("用户取消了此次工具调用", output.text());
        assertProducer(output, Status.CANCELLED);
        assertTrue(WebTools.isUsableMcpOutput(output), "CANCELLED 原样回给模型，不在取消后回退去真实搜索");
    }

    // ------------------ 内置工具体：业务级结果的状态（文本逐字冻结为上报状态之前的字面量） ------------------

    /**
     * 每个内置工具至少一条失败路径 + 成功路径，按表顺序逐条执行（编辑类用例依赖前面落下的文件）。
     * 期望文本是内置工具上报状态之前的字面量：状态是新增的，文本一个字节都不许变。
     */
    @Test
    void builtInToolsReportStatusWithUnchangedText() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        Files.writeString(tempDir.resolve("edit-ok.txt"), "alpha beta\n");
        Files.writeString(tempDir.resolve("edit-amb.txt"), "dup dup\n");
        Files.writeString(tempDir.resolve("edit-same.txt"), "say “hi”\n");
        Path dir = Files.createDirectories(tempDir.resolve("adir"));
        Files.createDirectories(tempDir.resolve("ld"));
        Files.writeString(tempDir.resolve("ld/only.txt"), "x");
        String writeIntoDirError = ioMessage(() ->
                Files.writeString(dir.toRealPath(), "x", StandardCharsets.UTF_8));

        String previousRagDir = System.setProperty(RAG_DIR, tempDir.resolve("rag").toString());
        try {
            assertCases(registry(), List.of(
                    // read_file
                    c("read_file", args("path", "a.txt"), Status.OK, "文件内容:\nhello"),
                    c("read_file", args("path", "adir"), Status.ERROR, "读取文件失败: 不是普通文件"),
                    // write_file
                    c("write_file", args("path", "w.txt", "content", "hi"), Status.OK, "文件已写入: w.txt"),
                    c("write_file", args("path", "adir", "content", "x"), Status.ERROR,
                            "写入文件失败: " + writeIntoDirError),
                    // edit_file
                    c("edit_file", args("path", "edit-ok.txt", "old_string", "alpha", "new_string", "gamma"), Status.OK,
                            "文件已编辑: edit-ok.txt，共 1 条编辑，替换 1 处匹配"),
                    c("edit_file", args("path", "a.txt", "old_string", "zzz", "new_string", "y"), Status.ERROR,
                            "第1条 old_string 未找到，请确认与文件完全一致（含缩进/空白/换行）"),
                    c("edit_file", args("path", "edit-amb.txt", "old_string", "dup", "new_string", "x"), Status.ERROR,
                            "第1条 old_string 匹配 2 处，请缩小范围或使用 replace_all=true"),
                    // 直引号模糊命中整行、替换成原行本身：内容不变
                    c("edit_file", args("path", "edit-same.txt", "old_string", "say \"hi\"", "new_string", "say “hi”"),
                            Status.ERROR, "编辑后文件内容无变化"),
                    c("edit_file", args("path", "missing.txt", "old_string", "a", "new_string", "b"), Status.ERROR,
                            "文件不存在，请用 write_file 新建"),
                    c("edit_file", "{\"path\":\"a.txt\",\"edits\":[]}", Status.ERROR, "edits 不能为空"),
                    // list_dir：「为空或不存在」不声明失败，保持 OK
                    c("list_dir", args("path", "ld"), Status.OK, "目录内容:\n[F] only.txt\n"),
                    c("list_dir", args("path", "nope"), Status.OK, "目录为空或不存在"),
                    // create_project
                    c("create_project", args("name", "p", "type", "rust"), Status.ERROR,
                            "创建项目失败: 不支持的项目类型 'rust'（支持 java / python / node）"),
                    c("create_project", args("name", "demo", "type", "node"), Status.OK, "项目已创建: demo (类型: node)"),
                    // execute_command：跑完即 OK，退出码只在文本里
                    c("execute_command", args("command", "echo hi"), Status.OK, "命令执行完成 (exit code: 0)\nhi\n"),
                    c("execute_command", args("command", "exit 3"), Status.OK, "命令执行完成 (exit code: 3)\n"),
                    c("execute_command", args("command", "   "), Status.ERROR, "执行命令失败: 命令不能为空"),
                    // 搜索：「未找到」是 OK
                    c("glob_files", args("pattern", ""), Status.ERROR, "文件匹配失败: pattern 不能为空"),
                    c("glob_files", args("pattern", "*.nomatch"), Status.OK, "未找到匹配文件: *.nomatch"),
                    c("grep_code", args("pattern", ""), Status.ERROR, "代码搜索失败: pattern 不能为空"),
                    c("search_code", args("query", "登录"), Status.ERROR, "代码库尚未索引，请先使用 /index 命令索引当前项目。"),
                    // web
                    c("web_search", args("query", " "), Status.ERROR, "搜索关键词不能为空"),
                    c("web_fetch", args("url", ""), Status.ERROR, "URL 不能为空"),
                    c("web_fetch", args("url", "file:///etc/passwd"), Status.DENIED,
                            "❌ 网络访问被拒绝: 禁止的 scheme: file（仅允许 http、https）"),
                    // 浏览器 / 会话 / 任务：协作者未注入
                    c("browser_status", "{}", Status.ERROR, "浏览器连接器未初始化，无法查看浏览器状态"),
                    c("save_memory", args("fact", "x"), Status.ERROR, "保存长期记忆失败: 记忆保存器未初始化"),
                    c("save_memory", args("fact", " "), Status.ERROR, "保存长期记忆失败: fact 不能为空"),
                    c("load_skill", args("name", "foo"), Status.ERROR, "load_skill 失败: Skill 系统未初始化"),
                    c("spawn_agent", args("description", "d", "prompt", "go"), Status.ERROR,
                            "spawn_agent 暂不可用：当前未注入 AgentSpawner（派生子 Agent 的能力未启用）。"),
                    c("spawn_agent", args("description", "d", "prompt", " "), Status.ERROR, "spawn_agent 失败: prompt 不能为空"),
                    c("send_message", args("to", "", "message", "m"), Status.ERROR, "send_message 失败: to(task_id) 不能为空"),
                    c("task_output", args("task_id", "t1"), Status.ERROR, "task_output 暂不可用：当前未注入 AgentSpawner。")));
        } finally {
            restore(RAG_DIR, previousRagDir);
        }
    }

    @Test
    void builtInsWithCollaboratorsInjectedAreOk() {
        ToolRegistry registry = registry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));
        registry.setAgentSpawner(new StubSpawner());

        assertCases(registry, List.of(
                c("save_memory", args("fact", "x"), Status.OK, "💾 已保存到长期记忆(project): x"),
                c("spawn_agent", args("description", "d", "prompt", "go"), Status.OK, "spawned:d"),
                c("spawn_agent", "{\"description\":\"d\",\"prompt\":\"go\",\"background\":true}", Status.OK, "bg:d"),
                c("send_message", args("to", "t1", "message", "m"), Status.OK, "continued:t1"),
                c("task_output", args("task_id", "t1"), Status.OK, "trace:t1")));
        assertEquals(List.of("project:x"), saved);
    }

    @Test
    void revertTurnThatDidNotRestoreIsErrorWithTheCliText() {
        ToolRegistry registry = registry();
        SnapshotConfig disabled = new SnapshotConfig(false, tempDir.resolve("snapshots"), 10, List.of(".git"));
        registry.setSnapshotService(new SnapshotService(new SideGitManager(tempDir, disabled)));

        assertCases(registry, List.of(c("revert_turn", "{}", Status.ERROR, "❌ 快照功能已关闭")));
    }

    @Test
    void commandTimeoutIsTimeout() {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        assertCases(registry, List.of(
                c("execute_command", args("command", "sleep 2"), Status.TIMEOUT, "命令执行超时（1秒），已强制终止")));
    }

    /** StepSearch 路径换上自己的标题，但状态照搬 MCP 结果：可用结果也可能是 CANCELLED / BLOCKED。 */
    @Test
    void stepSearchWrapperCarriesTheMcpStatus() {
        for (Status status : List.of(Status.OK, Status.CANCELLED, Status.BLOCKED, Status.TIMEOUT)) {
            ToolRegistry registry = registry();
            registry.setCurrentModel("step", "step-3.7-flash");
            registry.registerMcpToolOutput(stepSearchDescriptor("web_search"),
                    args -> ToolOutput.failure(status, " hits "));
            registry.registerMcpToolOutput(stepSearchDescriptor("web_fetch"),
                    args -> ToolOutput.failure(status, " page "));

            assertCases(registry, List.of(
                    c("web_search", args("query", " q "), status, "🔍 [StepSearch] q\n\nhits"),
                    // IP 字面量：网络策略不走 DNS，离线可跑
                    c("web_fetch", args("url", "https://93.184.216.34/"), status,
                            "🌐 [StepSearch] 抓取: https://93.184.216.34/\n\npage")));
        }
    }

    // ------------------ MCP ------------------

    @Test
    void mcpIsErrorThroughClientIsErrorAndKeepsImageParts() throws Exception {
        ToolRegistry registry = registry();
        registerMcp(registry, "echo", """
                {"content": [
                    {"type": "text", "text": "no such file"},
                    {"type": "image", "data": "aGVsbG8=", "mimeType": "image/png"}
                ], "isError": true}
                """);

        ToolOutput output = registry.executeToolOutput("mcp__demo__echo", "{}");

        assertTrue(output.text().startsWith("MCP 工具返回错误: no such file"), output.text());
        assertEquals(1, output.imageParts().size(), "isError 结果保留图片附件");
        assertProducer(output, Status.ERROR);
    }

    @Test
    void mcpIsErrorWithoutBodyIsError() throws Exception {
        ToolOutput direct = new McpCallToolResult(List.of(), true).toToolOutput();
        assertEquals("MCP 工具返回错误，但没有错误正文", direct.text());
        assertProducer(direct, Status.ERROR);

        ToolRegistry registry = registry();
        registerMcp(registry, "echo", """
                {"content": [], "isError": true}
                """);
        ToolOutput viaClient = registry.executeToolOutput("mcp__demo__echo", "{}");
        assertEquals("MCP 工具返回错误: MCP 工具返回错误，但没有错误正文", viaClient.text());
        assertProducer(viaClient, Status.ERROR);
    }

    @Test
    void mcpSuccessIsOkAndUsable() throws Exception {
        ToolRegistry registry = registry();
        registerMcp(registry, "echo", """
                {"content": [{"type": "text", "text": "search hits"}], "isError": false}
                """);

        ToolOutput output = registry.executeToolOutput("mcp__demo__echo", "{}");

        assertEquals("search hits", output.text());
        assertProducer(output, Status.OK);
        assertProducer(new McpCallToolResult(List.of(), false).toToolOutput(), Status.OK);
    }

    // ------------------ executeTools：批次路径 ------------------

    @Test
    void batchTimeoutIsTimeout() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public ToolOutput executeToolOutput(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ToolOutput.text("result-" + name);
            }
        };

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("call_1", "slow", "{}"),
                new ToolInvocation("call_2", "fast", "{}")));

        assertTrue(results.get(0).timedOut());
        assertEquals("工具执行超时（1秒），已取消", results.get(0).result());
        assertProducer(results.get(0).output(), Status.TIMEOUT);
        assertProducer(results.get(1).output(), Status.OK);
    }

    @Test
    void batchExecutionExceptionIsError() {
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public ToolOutput executeToolOutput(String name, String argumentsJson) {
                if ("boom".equals(name)) {
                    throw new IllegalStateException("kaboom");
                }
                return ToolOutput.text("ok-" + name);
            }
        };

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("call_1", "boom", "{}"),
                new ToolInvocation("call_2", "fine", "{}")));

        assertEquals("工具执行失败: kaboom", results.get(0).result());
        assertProducer(results.get(0).output(), Status.ERROR);
        assertProducer(results.get(1).output(), Status.OK);
    }

    /**
     * 批次层面的取消 / 中断沿用「工具执行失败: 」前缀，状态是 CANCELLED——这里冻结判据与状态判据<b>不一致</b>
     * （前缀判不可用，CANCELLED 判可用）。WebTools 只经 {@code executeToolOutput} 单次调用 StepSearch、
     * 从不经 {@code executeTools}，所以这条分歧对它不可达；钉在这里是给后续消费方看的。
     */
    @Test
    void batchCancellationBeforeStartIsCancelled() {
        ToolRegistry registry = registry();
        CancellationToken token = CancellationContext.startRun();
        token.cancel();
        List<ToolExecutionResult> results;
        try {
            results = registry.executeTools(List.of(
                    new ToolInvocation("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                    new ToolInvocation("call_2", "read_file", "{\"path\":\"b.txt\"}")));
        } finally {
            CancellationContext.clear(token);
        }

        for (ToolExecutionResult result : results) {
            assertEquals("工具执行失败: 用户取消了此次工具调用", result.result());
            assertEquals(Status.CANCELLED, result.output().status());
            assertFalse(frozenPrefixOracle(result.output()));
            assertTrue(WebTools.isUsableMcpOutput(result.output()));
        }
    }

    @Test
    void batchCancellationPerTaskIsCancelled() throws Exception {
        CountDownLatch allRunning = new CountDownLatch(4);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<CancellationToken> tokenRef = new AtomicReference<>();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public ToolOutput executeToolOutput(String name, String argumentsJson) {
                allRunning.countDown();
                try {
                    allRunning.await(5, TimeUnit.SECONDS);
                    if ("t0".equals(name)) {
                        tokenRef.get().cancel();
                        cancelled.countDown();
                    } else {
                        cancelled.await(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolOutput.text("ok-" + name);
            }
        };
        tokenRef.set(CancellationContext.startRun());
        List<ToolExecutionResult> results;
        try {
            List<ToolInvocation> invocations = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                invocations.add(new ToolInvocation("call_" + i, "t" + i, "{}"));
            }
            // 池上限 4：第 5 个任务只能在前 4 个之一返回后起跑，那时令牌已取消
            results = registry.executeTools(invocations);
        } finally {
            CancellationContext.clear(tokenRef.get());
        }

        ToolExecutionResult fifth = results.get(4);
        assertEquals("工具执行失败: 用户取消了此次工具调用", fifth.result());
        assertEquals(Status.CANCELLED, fifth.output().status());
        for (int i = 0; i < 4; i++) {
            assertProducer(results.get(i).output(), Status.OK);
        }
    }

    @Test
    void batchInterruptedWhileWaitingIsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public ToolOutput executeToolOutput(String name, String argumentsJson) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolOutput.text("ok-" + name);
            }
        };
        AtomicReference<List<ToolExecutionResult>> out = new AtomicReference<>();
        Thread caller = new Thread(() -> out.set(registry.executeTools(List.of(
                new ToolInvocation("call_1", "a", "{}"),
                new ToolInvocation("call_2", "b", "{}")))));
        caller.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(5_000);
        release.countDown();

        assertFalse(caller.isAlive());
        for (ToolExecutionResult result : out.get()) {
            assertEquals("工具执行失败: 工具批次执行被中断", result.result());
            assertEquals(Status.CANCELLED, result.output().status());
        }
    }

    // ------------------ McpResultBudget 改写结果时保留状态 ------------------

    @Test
    void largeMcpResultTurnedIntoReferenceKeepsStatus() {
        ToolRegistry registry = registry();
        registry.registerMcpToolOutput(descriptor("echo"),
                args -> ToolOutput.failure(Status.ERROR, "MCP 工具返回错误: " + "x".repeat(30_000)));

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("call_large", "mcp__demo__echo", "{}")));

        assertTrue(results.get(0).result().startsWith("MCP result reference:"), results.get(0).result());
        assertEquals(Status.ERROR, results.get(0).output().status());
    }

    @Test
    void mcpBatchBudgetReferenceKeepsStatus() {
        ToolRegistry registry = registry();
        registry.registerMcpToolOutput(descriptor("echo"),
                args -> ToolOutput.failure(Status.ERROR, "MCP 工具返回错误: " + "x".repeat(24_000)));
        List<ToolInvocation> invocations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            invocations.add(new ToolInvocation("call_batch_" + i, "mcp__demo__echo", "{\"index\":" + i + "}"));
        }

        List<ToolExecutionResult> results = registry.executeTools(invocations);

        assertTrue(results.stream().anyMatch(r -> r.result().startsWith("MCP result reference:")),
                "合计超预算时至少一条应被转成引用");
        for (ToolExecutionResult result : results) {
            assertEquals(Status.ERROR, result.output().status(), result.result());
        }
    }

    // ------------------ ToolOutput 自身 ------------------

    @Test
    void constructorsDefaultToOkAndWithStatusCopiesEverythingElse() {
        assertEquals(Status.OK, ToolOutput.text("x").status());
        assertEquals(Status.OK, new ToolOutput("x", List.of()).status());
        assertEquals(Status.OK, new ToolOutput("x", List.of(), null).status());
        assertEquals(Status.OK, new ToolOutput("x", List.of(), null, null).status());
        assertEquals(Status.OK, ToolOutput.ofBlocks(List.of(ToolOutput.Block.text("x")), List.of()).status());

        ToolOutput original = ToolOutput.ofBlocks(List.of(ToolOutput.Block.text("a"), ToolOutput.Block.text("b")), List.of());
        ToolOutput denied = original.withStatus(Status.DENIED);
        assertEquals(Status.DENIED, denied.status());
        assertFalse(denied.isOk());
        assertEquals(original.text(), denied.text());
        assertEquals(original.blocks(), denied.blocks());
        assertEquals(original.imageParts(), denied.imageParts());
        assertEquals(ToolOutput.text("t").withStatus(Status.TIMEOUT), ToolOutput.failure(Status.TIMEOUT, "t"));
    }

    // ------------------ helpers ------------------

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        return registry;
    }

    /** 一行表：调用哪个工具、带什么参数，期望什么状态与（逐字）文本。 */
    private record Case(String tool, String args, Status status, String text) {}

    private static Case c(String tool, String args, Status status, String text) {
        return new Case(tool, args, status, text);
    }

    /** 按表顺序<b>立即</b>执行每一行（后面的行可能依赖前面落下的文件），再统一报告所有不符。 */
    private static void assertCases(ToolRegistry registry, List<Case> cases) {
        List<Executable> checks = new ArrayList<>();
        for (Case k : cases) {
            ToolOutput out = registry.executeToolOutput(k.tool(), k.args());
            String label = k.tool() + " " + k.args();
            checks.add(() -> assertEquals(k.text(), out.text(), label + " 文本"));
            checks.add(() -> assertEquals(k.status(), out.status(), label + " 状态; text=" + out.text()));
        }
        assertAll(checks);
    }

    /** 成对的键值 → JSON 参数串。 */
    private static String args(String... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node.toString();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** 跑一遍与工具体相同的 I/O、取它的异常消息：工具失败文本里的那段随平台而变，不写死。 */
    private static String ioMessage(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            return e.getMessage();
        }
        throw new AssertionError("期望这次 I/O 失败");
    }

    private static McpToolDescriptor stepSearchDescriptor(String name) {
        return new McpToolDescriptor("step_search", name, McpToolDescriptor.namespaced("step_search", name),
                "step tool", JsonNodeFactory.instance.objectNode().put("type", "object"));
    }

    private static McpToolDescriptor descriptor(String name) {
        return new McpToolDescriptor("demo", name, McpToolDescriptor.namespaced("demo", name), "demo tool",
                JsonNodeFactory.instance.objectNode().put("type", "object"));
    }

    private static void registerMcp(ToolRegistry registry, String name, String toolsCallResponse) throws IOException {
        JsonNode response = MAPPER.readTree(toolsCallResponse);
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode())
                .handle("tools/call", p -> response);
        McpClient client = new McpClient("demo", transport);
        client.initialize();
        registry.registerMcpToolOutput(descriptor(name), args -> {
            try {
                return client.callToolOutput(name, args);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static HookDispatcher blockingDispatcher(String reason) {
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(HookEvent.PRE_TOOL_USE, new HookTenant() {
            @Override
            public String name() {
                return "stub-block";
            }

            @Override
            public HookDecision evaluate(HookPayload payload) {
                return payload.event() == HookEvent.PRE_TOOL_USE ? HookDecision.block(reason) : HookDecision.cont();
            }
        });
        return dispatcher;
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    private static final class StubSpawner implements AgentSpawner {
        @Override
        public String spawn(String description, String prompt) {
            return "spawned:" + description;
        }

        @Override
        public String spawnBackground(String description, String prompt) {
            return "bg:" + description;
        }

        @Override
        public String continueTask(String taskId, String message) {
            return "continued:" + taskId;
        }

        @Override
        public String taskOutput(String taskId) {
            return "trace:" + taskId;
        }
    }

    private static final class StubHitl implements HitlHandler {
        private final Function<com.cafecli.hitl.ApprovalRequest, ApprovalResult> decision;
        private boolean enabled = true;

        StubHitl(Function<com.cafecli.hitl.ApprovalRequest, ApprovalResult> decision) {
            this.decision = decision;
        }

        @Override
        public ApprovalResult requestApproval(com.cafecli.hitl.ApprovalRequest request) {
            return decision.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
