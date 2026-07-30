package com.cafecli.agent;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 前台 {@code spawn_agent} 工具的最高接缝测试：用桩 LlmClient 驱动一个真实的主 Agent，
 * 主 Agent 调用 spawn_agent 派生一个子任务（child Agent），子任务跑完自己的循环后
 * 只把最终结果文本（result-only）回灌主对话。
 *
 * 对应 CONTEXT.md「Agent 编排」术语：主 Agent 前台派生（foreground spawn）一个任务（Task）。
 */
class SpawnAgentToolTest {

    private static final String CHILD_PROMPT = "调研 Loom 虚拟线程的现状";
    private static final String CHILD_FINAL_TEXT = "Loom 已在 JDK 21 GA，虚拟线程适合 IO 密集任务。";
    private static final String CHILD_TOOL_NOISE = "INTERNAL_CHILD_TOOL_NOISE_read_file";
    private static final String MAIN_SUMMARY = "已让子任务调研完毕，结论：虚拟线程可用。";

    /**
     * (1) spawn_agent 注册为内置工具且对主 ReAct Agent 可见；
     * (2) 调用后子任务跑完，最终结果文本回灌到主对话的 tool 消息。
     */
    @Test
    void spawnAgentToolIsRegisteredAndReturnsChildFinalText() {
        TaskRegistry taskRegistry = new TaskRegistry();
        DispatchingStubLlmClient llm = scriptedMainAndChild();

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, taskRegistry));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        // spawn_agent 在工具定义里可见
        assertTrue(toolRegistry.getToolDefinitions().stream()
                        .anyMatch(t -> "spawn_agent".equals(t.name())),
                "spawn_agent 应该注册为内置工具并对主 Agent 可见");

        main.run("请调研一下 Loom 并汇报");

        // 主对话里出现一条 tool 消息，内容恰为子任务最终文本（result-only）
        List<LlmClient.Message> history = main.getConversationHistory();
        boolean childResultReachedMain = history.stream()
                .filter(m -> "tool".equals(m.role()))
                .anyMatch(m -> m.content() != null && m.content().contains(CHILD_FINAL_TEXT));
        assertTrue(childResultReachedMain, "子任务的最终结果文本应回灌到主对话的 tool 消息");
    }

    /**
     * (3) result-only：子任务的中间工具噪声绝不进入主 Agent 的对话历史。
     */
    @Test
    void childToolNoiseIsAbsentFromMainHistory() {
        TaskRegistry taskRegistry = new TaskRegistry();
        DispatchingStubLlmClient llm = scriptedMainAndChild();

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, taskRegistry));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        main.run("请调研一下 Loom 并汇报");

        String mainHistoryDump = main.getConversationHistory().stream()
                .map(m -> (m.role() == null ? "" : m.role()) + ":" + (m.content() == null ? "" : m.content()))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(mainHistoryDump.contains(CHILD_TOOL_NOISE),
                "子任务的中间工具噪声不应进入主 Agent 对话历史");
        // 主 Agent 仍然能拿到最终结论
        assertTrue(mainHistoryDump.contains(CHILD_FINAL_TEXT),
                "主对话里仍应有子任务的最终结论");
    }

    /**
     * (4) TaskRegistry 记录任务，在稳定 task_id 下完成 RUNNING → DONE 状态迁移。
     */
    @Test
    void taskRegistryTracksLifecycleRunningToDone() {
        TaskRegistry taskRegistry = new TaskRegistry();
        DispatchingStubLlmClient llm = scriptedMainAndChild();

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, taskRegistry));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        assertTrue(taskRegistry.tasks().isEmpty(), "运行前 registry 为空");

        main.run("请调研一下 Loom 并汇报");

        List<Task> tasks = taskRegistry.tasks();
        assertEquals(1, tasks.size(), "应登记恰好一个任务");
        Task task = tasks.get(0);
        assertTrue(task.id().startsWith("task_"), "task_id 应稳定且以 task_ 前缀命名，实际：" + task.id());
        assertEquals(Task.State.DONE, task.state(), "子任务跑完后应迁移到 DONE");
        assertTrue(task.result() != null && task.result().contains(CHILD_FINAL_TEXT),
                "任务结果应保存子任务最终文本");
    }

    /**
     * (5) 失败路径：子任务抛错 → 任务标 FAILED，且工具结果是可读的失败说明而非异常栈。
     */
    @Test
    void childFailureMarksTaskFailedAndReturnsSaneToolResult() {
        TaskRegistry taskRegistry = new TaskRegistry();

        // 主 Agent 调 spawn_agent；spawner 用一个总是抛错的 LlmClient 跑子任务。
        Function<String, LlmClient.ChatResponse> mainDispatcher = conversation -> {
            // 子任务失败后，工具结果（tool 消息）里含"失败"→ 主 Agent 收尾汇报。
            if (conversation.contains("tool:") && conversation.contains("失败")) {
                return text(MAIN_SUMMARY);
            }
            return spawnAgentCall(CHILD_PROMPT);
        };
        DispatchingStubLlmClient mainLlm = new DispatchingStubLlmClient(mainDispatcher);
        LlmClient explodingChildLlm = new ExplodingLlmClient();

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(explodingChildLlm, taskRegistry));

        Agent main = new Agent(mainLlm, toolRegistry);
        main.setRenderer(silentRenderer());

        main.run("请调研一下 Loom 并汇报");

        List<Task> tasks = taskRegistry.tasks();
        assertEquals(1, tasks.size());
        assertEquals(Task.State.FAILED, tasks.get(0).state(), "子任务出错应标记 FAILED");

        String toolResult = main.getConversationHistory().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(LlmClient.Message::content)
                .reduce("", (a, b) -> a + b);
        assertFalse(toolResult.isBlank(), "失败时仍应给主 Agent 一个工具结果");
        assertTrue(toolResult.contains("失败") || toolResult.toLowerCase().contains("fail"),
                "失败工具结果应是可读说明，实际：" + toolResult);
    }

    // ---- 脚本化的主+子桩：主 Agent 先调 spawn_agent，子 Agent 直接出最终文本 ----
    // 桩按整段对话（含 tool 消息）派发，主/子共用同一个桩，靠对话内容区分二者所处阶段。

    private DispatchingStubLlmClient scriptedMainAndChild() {
        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            // 主 Agent 已拿到 spawn_agent 的工具结果（含子任务最终文本）→ 做最终汇报。
            if (conversation.contains(CHILD_FINAL_TEXT)) {
                return text(MAIN_SUMMARY);
            }
            // 子 Agent 第二步：已执行过中间工具（噪声）→ 输出最终结论。
            if (conversation.contains(CHILD_TOOL_NOISE)) {
                return text(CHILD_FINAL_TEXT);
            }
            // 子 Agent 第一步：拿到 prompt → 先做一次中间工具调用，制造"工具噪声"。
            if (conversation.contains(CHILD_PROMPT)) {
                return noisyChildToolCall();
            }
            // 主 Agent 的第一步：调用 spawn_agent。
            return spawnAgentCall(CHILD_PROMPT);
        };
        return new DispatchingStubLlmClient(dispatcher);
    }

    private static LlmClient.ChatResponse spawnAgentCall(String prompt) {
        String args = "{\"description\":\"调研 Loom\",\"prompt\":\"" + prompt + "\"}";
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("spawn_agent", args));
        return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 100, 20);
    }

    /** 子 Agent 的中间动作：调用 list_dir，并在助手内容里带上噪声标记。这条只进子对话历史。 */
    private static LlmClient.ChatResponse noisyChildToolCall() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "child_call_1", new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
        return new LlmClient.ChatResponse("assistant", CHILD_TOOL_NOISE, null, List.of(call), 50, 10);
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    /** 丢弃所有输出的渲染器，让主 Agent 在测试里不打印到控制台。 */
    private static Renderer silentRenderer() {
        return new Renderer() {
            private final PrintStream discard =
                    new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);

            @Override public void start() {}
            @Override public void close() {}
            @Override public PrintStream stream() { return discard; }
            @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {}
            @Override public void appendDiff(String filePath, String before, String after) {}
            @Override public void updateStatus(StatusInfo status) {}
            @Override public ApprovalResult promptApproval(ApprovalRequest request) {
                return ApprovalResult.approve();
            }
            @Override public int openPalette(String title, List<String> items) { return -1; }
        };
    }

    /** 基于整段对话（role:content，含 tool 消息）派发响应，主 Agent 与子 Agent 共用。 */
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

    /** 永远抛错的 LlmClient，用于驱动子任务失败路径。 */
    private static final class ExplodingLlmClient extends GLMClient {
        private ExplodingLlmClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("boom: child LLM unavailable");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("boom: child LLM unavailable");
        }
    }
}
