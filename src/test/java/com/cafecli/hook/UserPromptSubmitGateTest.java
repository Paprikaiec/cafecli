package com.cafecli.hook;

import com.cafecli.agent.Agent;
import com.cafecli.llm.LlmClient;
import com.cafecli.policy.AuditLog;
import com.cafecli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserPromptSubmit 门（issue 06）：BLOCK 拦下提交（原因给用户）+ 审计；additionalContext 追加到 prompt；
 * 关闭 / 无租户时原样放行；附加上下文经 scripted client 确认真的进了 Agent 的请求历史。
 */
class UserPromptSubmitGateTest {

    private static final String ENABLED = HookProperties.USER_PROMPT_SUBMIT_ENABLED;

    @AfterEach
    void clear() {
        System.clearProperty(ENABLED);
    }

    private record Stub(HookDecision decision) implements HookTenant {
        @Override
        public String name() {
            return "ups-stub";
        }

        @Override
        public HookDecision evaluate(HookPayload payload) {
            return decision;
        }
    }

    private static HookDispatcher dispatcherWith(HookDecision decision) {
        HookDispatcher d = new HookDispatcher();
        d.register(HookEvent.USER_PROMPT_SUBMIT, new Stub(decision));
        return d;
    }

    @Test
    void blockStopsSubmissionAndAudits(@TempDir Path dir) {
        System.setProperty(ENABLED, "true");
        AuditLog audit = new AuditLog(dir.resolve("audit"));
        UserPromptSubmitGate gate = new UserPromptSubmitGate(
                dispatcherWith(HookDecision.block("疑似密钥，已拦下")), dir.toString(), audit);

        UserPromptSubmitGate.Decision decision = gate.decide("这是我的 AKIA... 密钥");

        assertTrue(decision.blocked());
        assertEquals("疑似密钥，已拦下", decision.reason());
        assertTrue(audit.readRecent(10).stream().anyMatch(e -> AuditLog.OUTCOME_HOOK_BLOCK.equals(e.outcome())));
    }

    @Test
    void additionalContextAppendedToPrompt(@TempDir Path dir) {
        System.setProperty(ENABLED, "true");
        UserPromptSubmitGate gate = new UserPromptSubmitGate(
                dispatcherWith(HookDecision.cont().withAdditionalContext("团队约定：用 4 空格缩进")),
                dir.toString(), null);

        UserPromptSubmitGate.Decision decision = gate.decide("帮我加个函数");

        assertFalse(decision.blocked());
        assertTrue(decision.effectivePrompt().startsWith("帮我加个函数"));
        assertTrue(decision.effectivePrompt().contains("团队约定：用 4 空格缩进"), decision.effectivePrompt());
    }

    @Test
    void disabledMasterSwitchProceedsUnchanged(@TempDir Path dir) {
        // 不设开关 → 即便有租户也原样放行
        UserPromptSubmitGate gate = new UserPromptSubmitGate(
                dispatcherWith(HookDecision.block("won't fire")), dir.toString(), null);
        UserPromptSubmitGate.Decision decision = gate.decide("hello");
        assertFalse(decision.blocked());
        assertEquals("hello", decision.effectivePrompt());
    }

    @Test
    void noTenantsProceedsUnchanged(@TempDir Path dir) {
        System.setProperty(ENABLED, "true");
        UserPromptSubmitGate gate = new UserPromptSubmitGate(new HookDispatcher(), dir.toString(), null);
        assertEquals("hello", gate.decide("hello").effectivePrompt());
    }

    @Test
    void appendedContextReachesAgentRequestHistory(@TempDir Path dir) {
        System.setProperty(ENABLED, "true");
        UserPromptSubmitGate gate = new UserPromptSubmitGate(
                dispatcherWith(HookDecision.cont().withAdditionalContext("CONVENTION-XYZ")), dir.toString(), null);
        String effective = gate.decide("do the thing").effectivePrompt();

        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, 5, 1)));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        Agent agent = new Agent(llm, registry);
        agent.run(effective);

        List<LlmClient.Message> firstCall = llm.messagesByCall.get(0);
        assertTrue(firstCall.stream()
                        .filter(m -> "user".equals(m.role()) && m.content() != null)
                        .anyMatch(m -> m.content().contains("CONVENTION-XYZ")),
                "附加上下文应进入 Agent 首轮请求的 user 消息");
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
