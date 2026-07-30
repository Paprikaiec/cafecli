package com.cafecli.agent;

import com.cafecli.agent.task.IdleTaskReaper;
import com.cafecli.agent.task.Task;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.GLMClient;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #03 验收：任务完成后转空闲-可续派（历史保留）；{@code send_message} 在保留历史上续派、
 * 复用既有上下文；{@code task_output} 按需拉取完整轨迹（含中间工具噪声，非 result-only）；
 * 对未知/已回收 id 的续派/检视返回明确的「已过期，请重开」，不崩。
 */
class SendMessageTaskOutputTest {

    private static final String RESEARCH_PROMPT = "RESEARCH_PROMPT";
    private static final String NOISE_MARKER = "INTERNAL_CHILD_NOISE_list_dir";
    private static final String RESEARCH_RESULT = "调研结论 RESEARCH_RESULT";
    private static final String CONTINUE_PROMPT = "CONTINUE_PROMPT_基于上面的发现继续实现";

    /**
     * 完成的任务保留历史 → send_message 在其上续派、续派轮看到保留上下文；
     * task_output 返回完整轨迹（含中间工具噪声），而首派交付仍是 result-only。
     */
    @Test
    void continueReusesRetainedContextAndTaskOutputShowsFullTrajectory() {
        TaskRegistry registry = new TaskRegistry();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(scriptedChild());
        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, null);

        // 首派：子任务先做一次中间工具调用（噪声），再出最终结论。
        String firstResult = spawner.spawn("调研任务", RESEARCH_PROMPT);
        assertTrue(firstResult.contains(RESEARCH_RESULT), "首派应回灌子任务最终结论");
        assertFalse(firstResult.contains(NOISE_MARKER), "首派交付是 result-only，不含中间工具噪声");

        List<Task> tasks = registry.tasks();
        assertEquals(1, tasks.size());
        String taskId = tasks.get(0).id();
        assertEquals(Task.State.DONE, tasks.get(0).state(), "完成的任务转入 DONE（空闲-可续派）");

        // 续派：在保留历史上跑新回合；桩只在看到保留上下文（RESEARCH_RESULT）时才回 CONTINUED_WITH_CONTEXT。
        String continued = spawner.continueTask(taskId, CONTINUE_PROMPT);
        assertTrue(continued.contains("CONTINUED_WITH_CONTEXT"),
                "续派轮应复用保留上下文（看到首派的研究历史），实际：" + continued);

        // task_output：完整轨迹含中间工具噪声 + 两轮结论，区别于 result-only 交付。
        String trajectory = spawner.taskOutput(taskId);
        assertTrue(trajectory.contains(NOISE_MARKER), "完整轨迹应包含中间工具调用（噪声）");
        assertTrue(trajectory.contains(RESEARCH_RESULT), "完整轨迹应包含首派结论");
        assertTrue(trajectory.contains("CONTINUED_WITH_CONTEXT"), "完整轨迹应包含续派结论");
    }

    /** 未知/已回收 id 的续派与检视都返回「已过期」，不抛异常、不崩。 */
    @Test
    void expiredTaskIdReturnsExpiredMessageNotCrash() {
        TaskRegistry registry = new TaskRegistry();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(scriptedChild());
        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, null);

        String continued = spawner.continueTask("task_404", "随便续派");
        assertTrue(continued.contains("已过期"), "未知 id 续派应返回已过期，实际：" + continued);

        String output = spawner.taskOutput("task_404");
        assertTrue(output.contains("已过期"), "未知 id 检视应返回已过期，实际：" + output);
    }

    /** send_message / task_output 注册为内置工具，对主 Agent 可见。 */
    @Test
    void sendMessageAndTaskOutputToolsAreRegistered() {
        ToolRegistry registry = new ToolRegistry();
        List<String> names = registry.getToolDefinitions().stream().map(LlmClient.Tool::name).toList();
        assertTrue(names.contains("send_message"), "send_message 应注册为内置工具");
        assertTrue(names.contains("task_output"), "task_output 应注册为内置工具");
    }

    /** 经真实工具路径调用 send_message 续派一个未知 id → 工具结果是「已过期」，不报错。 */
    @Test
    void sendMessageToolReturnsExpiredForUnknownId() {
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(scriptedChild());
        ToolRegistry registry = new ToolRegistry();
        registry.setAgentSpawner(new ForegroundAgentSpawner(llm, new TaskRegistry(), null));

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "send_message",
                        "{\"to\":\"task_999\",\"message\":\"hi\"}")));

        assertEquals(1, results.size());
        assertTrue(results.get(0).result().contains("已过期"),
                "send_message 工具对未知 id 应返回已过期，实际：" + results.get(0).result());
    }

    /** #12 集成：LRU 回收最旧空闲任务 → 其子 Agent 移出保留表，续派返回「已过期」；最近的仍可续派。 */
    @Test
    void lruReapsOldestIdleTaskSoContinueReturnsExpired() {
        TaskRegistry registry = new TaskRegistry();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(conversation -> text("子任务结论"));
        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, null);
        spawner.setIdleTaskReaper(new IdleTaskReaper(2));  // 只保留最近 2 个空闲任务

        spawner.spawn("调研A", "PROMPT_A");  // task_1
        spawner.spawn("调研B", "PROMPT_B");  // task_2
        spawner.spawn("调研C", "PROMPT_C");  // task_3 → 淘汰最旧的 task_1

        assertTrue(spawner.continueTask("task_1", "继续").contains("已过期"),
                "被 LRU 回收的最旧任务续派应返回已过期");
        assertFalse(spawner.continueTask("task_3", "继续").contains("已过期"),
                "最近的空闲任务仍可续派");
    }

    // ---- 脚本化子 Agent 桩：首派先做噪声工具调用再出结论；续派看到保留上下文则证明复用 ----
    private static Function<String, LlmClient.ChatResponse> scriptedChild() {
        return conversation -> {
            // 续派轮：只有在看到保留历史（首派结论）时才回 CONTINUED_WITH_CONTEXT，证明上下文复用。
            if (conversation.contains(CONTINUE_PROMPT)) {
                return conversation.contains(RESEARCH_RESULT)
                        ? text("CONTINUED_WITH_CONTEXT")
                        : text("CONTINUED_NO_CONTEXT");
            }
            // 首派第二步：已执行过中间工具（噪声）→ 出最终结论。
            if (conversation.contains(NOISE_MARKER)) {
                return text(RESEARCH_RESULT);
            }
            // 首派第一步：拿到 prompt → 先做一次中间工具调用，制造工具噪声。
            if (conversation.contains(RESEARCH_PROMPT)) {
                return noisyToolCall();
            }
            return text("fallback");
        };
    }

    private static LlmClient.ChatResponse noisyToolCall() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "child_call_1", new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
        return new LlmClient.ChatResponse("assistant", NOISE_MARKER, null, List.of(call), 50, 10);
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    /** 基于整段对话内容派发响应，主/子共用，线程安全。 */
    private static final class DispatchingStubLlmClient extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingStubLlmClient(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            String conversation = flatten(messages);
            ChatResponse response = dispatcher.apply(conversation);
            if (response == null) {
                throw new IOException("无匹配响应，对话: " + conversation);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        private static String flatten(List<Message> messages) {
            StringBuilder sb = new StringBuilder();
            for (Message m : messages) {
                sb.append(m.role() == null ? "" : m.role()).append(':')
                        .append(m.content() == null ? "" : m.content()).append('\n');
            }
            return sb.toString();
        }
    }
}
