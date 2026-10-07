package com.cafecli.agent;

import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.hitl.HitlHandler;
import com.cafecli.hitl.HitlToolRegistry;
import com.cafecli.hook.HookDecision;
import com.cafecli.hook.HookEvent;
import com.cafecli.hook.HookPayload;
import com.cafecli.hook.HookProperties;
import com.cafecli.hook.HookTenant;
import com.cafecli.llm.LlmClient;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 6（最高价值，{@code AgentLspDiagnosticsTest} 先例）：{@code Agent.run()} + 脚本化 LLM 客户端。
 *
 * 收尾门端到端：写文件 → 尝试收尾 → 被阻断并注入原因 → 跑验证命令 → 收尾成功。断言额外轮次、注入内容、
 * 阻断上限；再验证 disabled / no-write / already-verified 变体正常收尾。模式开关在 on/off 两态断言。
 */
class AgentFinishGateTest {

    private static final String STOP_ENABLED = "cafecli.hook.stop.enabled";
    private static final String GATE_ENABLED = "cafecli.hook.finish_gate.enabled";
    private static final String VERIFY_REGEX = "cafecli.hook.finish_gate.verify_regex";
    private static final String BLOCK_CAP = "cafecli.hook.finish_gate.block_cap";
    private static final String AUDIT_DIR = "paicli.audit.dir";
    private static final String PRE_TOOL_USE_ENABLED = HookProperties.PRE_TOOL_USE_ENABLED;

    @AfterEach
    void clearProps() {
        System.clearProperty(PRE_TOOL_USE_ENABLED);
        System.clearProperty(STOP_ENABLED);
        System.clearProperty(GATE_ENABLED);
        System.clearProperty(VERIFY_REGEX);
        System.clearProperty(BLOCK_CAP);
        System.clearProperty(AUDIT_DIR);
    }

    private static LlmClient.ToolCall writeFile(String path) {
        return new LlmClient.ToolCall("w1", new LlmClient.ToolCall.Function(
                "write_file", "{\"path\":\"" + path + "\",\"content\":\"hi\"}"));
    }

    private static LlmClient.ToolCall runVerify() {
        return new LlmClient.ToolCall("c1", new LlmClient.ToolCall.Function(
                "execute_command", "{\"command\":\"true\"}"));
    }

    private static LlmClient.ChatResponse toolResp(LlmClient.ToolCall call) {
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 10, 2);
    }

    private static LlmClient.ChatResponse finishResp(String text) {
        return new LlmClient.ChatResponse("assistant", text, null, 10, 2);
    }

    private Agent newAgent(RecordingClient llm, Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        return new Agent(llm, registry);
    }

    @Test
    void blocksFinishAfterWriteThenFinishesOnceVerified(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");   // 让无害命令 `true` 算作验证，保持确定性

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),  // 1: 写文件
                finishResp("已完成。"),             // 2: 尝试收尾 -> 被收尾门阻断
                toolResp(runVerify()),             // 3: 跑验证命令
                finishResp("已验证并完成。")));      // 4: 收尾成功

        Agent agent = newAgent(llm, tempDir);
        agent.run("改一个文件");

        // 额外轮次真的发生了：4 次 LLM 调用（否则被阻断后不会再有第 3、4 次）。
        assertEquals(4, llm.messagesByCall.size(), "被阻断后应多跑一轮");

        // 第 3 次 LLM 请求历史里应带有清晰标记的收尾门注入消息（user 角色）。
        List<LlmClient.Message> thirdCall = llm.messagesByCall.get(2);
        assertTrue(thirdCall.stream()
                        .filter(m -> "user".equals(m.role()) && m.content() != null)
                        .anyMatch(m -> m.content().contains("收尾门")
                                && m.content().contains("验证命令")),
                "阻断原因应作为标记消息注入下一轮请求");
    }

    @Test
    void respectsBlockCap(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");
        System.setProperty(BLOCK_CAP, "1");  // 只允许阻断 1 次，第二次收尾直接放行

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),  // 1: 写文件
                finishResp("先交付。"),             // 2: 收尾 -> 阻断 1 次（count 0<1）
                finishResp("还是交付。")));          // 3: 收尾 -> count 1>=cap，放行

        Agent agent = newAgent(llm, tempDir);
        agent.run("改一个文件但不验证");

        assertEquals(3, llm.messagesByCall.size(), "达到阻断上限后应放行收尾，恰好 3 轮");
    }

    @Test
    void hardIterationCapAlwaysWinsOverBlocks(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "NEVER_MATCHES");   // 验证永不成立
        System.setProperty(BLOCK_CAP, "100");                // 阻断上限很高
        System.setProperty("paicli.react.hard.max.iterations", "2");
        try {
            RecordingClient llm = new RecordingClient(List.of(
                    toolResp(writeFile("Note.txt")),  // iteration 1: 写
                    finishResp("交付。")));             // iteration 2: 收尾 -> 阻断，continue -> 触硬轮数上限

            Agent agent = newAgent(llm, tempDir);
            String result = agent.run("改文件永不验证");

            assertEquals(2, llm.messagesByCall.size(), "硬轮数上限应在阻断循环里兜底");
            assertTrue(result.contains("硬轮数上限"), "应以硬轮数上限收尾: " + result);
        } finally {
            System.clearProperty("paicli.react.hard.max.iterations");
        }
    }

    @Test
    void recordsHookBlockInAuditLog(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");
        System.setProperty(AUDIT_DIR, tempDir.resolve("audit").toString());

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),
                finishResp("尝试收尾。"),
                toolResp(runVerify()),
                finishResp("完成。")));

        Agent agent = newAgent(llm, tempDir);
        agent.run("改文件");

        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        List<AuditLog.AuditEntry> recent = auditLog.readRecent(50);
        assertTrue(recent.stream().anyMatch(e -> AuditLog.OUTCOME_HOOK_BLOCK.equals(e.outcome())),
                "收尾门阻断应写入一条 hook_block 审计");
    }

    @Test
    void disabledGateFinishesNormally(@TempDir Path tempDir) {
        // 两个开关都不设 -> 收尾门不注册，第一次收尾即返回。
        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),
                finishResp("直接完成，不受收尾门约束。")));

        Agent agent = newAgent(llm, tempDir);
        agent.run("改文件但门是关的");

        assertEquals(2, llm.messagesByCall.size(), "门关闭时不应产生额外轮次");
    }

    @Test
    void noWriteTurnFinishesNormally(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");

        RecordingClient llm = new RecordingClient(List.of(
                finishResp("只是回答问题，没有写文件。")));

        Agent agent = newAgent(llm, tempDir);
        agent.run("解释一下什么是 ReAct");

        assertEquals(1, llm.messagesByCall.size(), "只读回合收尾门应静默");
    }

    @Test
    void alreadyVerifiedTurnFinishesNormally(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),  // 写
                toolResp(runVerify()),             // 写后立即验证
                finishResp("已写并已验证。")));       // 收尾 -> 静默放行

        Agent agent = newAgent(llm, tempDir);
        agent.run("改文件并验证");

        assertEquals(3, llm.messagesByCall.size(), "写后已验证应正常收尾，无额外阻断轮次");
    }

    // ---- 写没有发生的回合：收尾门必须静默（ADR 0019：成败读 ToolOutput.status()，不嗅文本前缀）----
    // 下面四条在旧的前缀名单实现下都会被当成「成功写入」而阻断收尾：第 3 次 LLM 调用发生并因缺少预设响应而结束。

    @Test
    void policyDeniedWriteDoesNotCountAsAWrite(@TempDir Path tempDir, @TempDir Path outside) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");

        Path target = outside.resolve("Note.txt");
        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile(target.toString().replace("\\", "\\\\"))),  // 项目根外 -> 策略拒绝
                finishResp("写不了，到此为止。")));

        Agent agent = newAgent(llm, tempDir);
        agent.run("往项目外写个文件");

        assertTrue(lastToolResult(llm).startsWith("🛡️"), "应走策略拒绝路径: " + lastToolResult(llm));
        assertFalse(Files.exists(target), "策略拒绝的写入不应落盘");
        assertEquals(2, llm.messagesByCall.size(), "被策略拒绝的 write_file 不是写入，收尾门不应阻断");
    }

    @Test
    void editWhoseOldStringIsMissingDoesNotCountAsAWrite(@TempDir Path tempDir) throws IOException {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");

        Path file = tempDir.resolve("Note.txt");
        Files.writeString(file, "hello\n");
        LlmClient.ToolCall edit = new LlmClient.ToolCall("e1", new LlmClient.ToolCall.Function(
                "edit_file", "{\"path\":\"Note.txt\",\"edits\":[{\"old_string\":\"not-there\",\"new_string\":\"x\"}]}"));
        RecordingClient llm = new RecordingClient(List.of(
                toolResp(edit),
                finishResp("没改成，到此为止。")));

        Agent agent = newAgent(llm, tempDir);
        agent.run("改一处不存在的文本");

        assertTrue(lastToolResult(llm).contains("old_string 未找到"), "应走编辑未命中路径: " + lastToolResult(llm));
        assertEquals("hello\n", Files.readString(file), "未命中的编辑不应改动文件");
        assertEquals(2, llm.messagesByCall.size(), "未命中的 edit_file 不是写入，收尾门不应阻断");
    }

    @Test
    void hitlRejectedWriteDoesNotCountAsAWrite(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),
                finishResp("用户拒绝了，到此为止。")));

        HitlToolRegistry registry = new HitlToolRegistry(new RejectingHitlHandler());
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);
        agent.run("写个文件");

        assertTrue(lastToolResult(llm).startsWith("[HITL]"), "应走 HITL 拒绝路径: " + lastToolResult(llm));
        assertFalse(Files.exists(tempDir.resolve("Note.txt")), "HITL 拒绝的写入不应落盘");
        assertEquals(2, llm.messagesByCall.size(), "HITL 拒绝的 write_file 不是写入，收尾门不应阻断");
    }

    @Test
    void preToolUseBlockedWriteDoesNotCountAsAWrite(@TempDir Path tempDir) {
        System.setProperty(STOP_ENABLED, "true");
        System.setProperty(GATE_ENABLED, "true");
        System.setProperty(VERIFY_REGEX, "true");
        System.setProperty(PRE_TOOL_USE_ENABLED, "true");

        RecordingClient llm = new RecordingClient(List.of(
                toolResp(writeFile("Note.txt")),
                finishResp("被 Hook 拦了，到此为止。")));

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);
        // 收尾门已让 Agent 把它的分发器注入 registry；在同一分发器上挂一个拦 write_file 的 PreToolUse 租户。
        registry.getHookDispatcher().register(HookEvent.PRE_TOOL_USE, new HookTenant() {
            @Override
            public String name() {
                return "block-writes";
            }

            @Override
            public HookDecision evaluate(HookPayload payload) {
                return payload.event() == HookEvent.PRE_TOOL_USE ? HookDecision.block("不许写") : HookDecision.cont();
            }
        });
        agent.run("写个文件");

        assertTrue(lastToolResult(llm).startsWith("[Hook 拦截"), "应走 PreToolUse 拦截路径: " + lastToolResult(llm));
        assertFalse(Files.exists(tempDir.resolve("Note.txt")), "被 Hook 拦截的写入不应落盘");
        assertEquals(2, llm.messagesByCall.size(), "被 Hook 拦截的 write_file 不是写入，收尾门不应阻断");
    }

    /** 最后一次 LLM 请求里看到的最后一条工具结果（确认用例真的走到了预期的失败路径）。 */
    private static String lastToolResult(RecordingClient llm) {
        List<LlmClient.Message> last = llm.messagesByCall.get(llm.messagesByCall.size() - 1);
        return last.stream()
                .filter(m -> "tool".equals(m.role()) && m.content() != null)
                .map(LlmClient.Message::content)
                .reduce((first, second) -> second)
                .orElse("");
    }

    private static final class RejectingHitlHandler implements HitlHandler {
        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            return ApprovalResult.reject("测试拒绝");
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
