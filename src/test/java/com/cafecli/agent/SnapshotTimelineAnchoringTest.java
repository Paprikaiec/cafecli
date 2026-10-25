package com.cafecli.agent;

import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.render.ApprovalRequest;
import com.cafecli.render.ApprovalResult;
import com.cafecli.llm.GLMClient;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #13：快照只锚<b>串行主回合边界</b>（前台回合 + push 回合，都在执行器上），<b>任务不单独快照</b>。
 *
 * <p>在 Main 里，主回合经 turnRunner 包进 {@code SnapshotService.runTurn}（= 快照锚点）；子任务的
 * {@code Agent.run} 不调 runTurn，故不新增快照。本测试把 turnRunner 当作快照锚点计数：断言它只在主回合
 * （前台 + push）各触发一次，即便主回合内派生了子任务也不增加锚点——即快照时间线对齐主回合、保持线性。
 */
class SnapshotTimelineAnchoringTest {

    @Test
    void snapshotsAnchorAtMainTurnsForegroundAndPushNotChildTasks() throws Exception {
        AtomicInteger snapshotAnchors = new AtomicInteger();   // turnRunner = Main 里包 runTurn 的快照锚点
        TaskRegistry registry = new TaskRegistry();

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            if (conversation.contains("<task-notification")) {
                return text("push 回合处理完毕");          // push 主回合（TaskDone）
            }
            if (conversation.contains("CHILD_DONE")) {
                return text("主回合收尾");                  // 主收到子任务结果后收尾
            }
            if (conversation.contains("CHILD_PROMPT")) {
                return text("CHILD_DONE");                 // 子任务自己的回合（不经 turnRunner）
            }
            return spawnCall("CHILD_PROMPT");              // 主回合第一步：前台派生一个子任务
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, registry, toolRegistry));
        Agent mainAgent = new Agent(llm, toolRegistry);
        mainAgent.setRenderer(silentRenderer());

        AgentSession session = new AgentSession(mainAgent, registry, output -> { });
        // 模拟 Main 的快照锚点：每个主回合（前台 / push）经 turnRunner 跑一次 = 锚一次快照。
        session.setTurnRunner(input -> {
            snapshotAnchors.incrementAndGet();
            return mainAgent.run(input);
        });
        session.start();
        try {
            // 主回合 1（前台）：内含派生一个前台子任务
            session.postEvent(new AgentSession.UserSubmit("派个前台子任务并汇报"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)));
            // 主回合 2（push）：后台任务完成的自治回合
            session.postEvent(new AgentSession.TaskDone("task_pushed", "后台结论"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)));

            assertEquals(2, snapshotAnchors.get(),
                    "快照只在串行主回合边界锚定：前台回合 + push 回合各一次，子任务不单独锚");
            assertTrue(registry.tasks().size() >= 1,
                    "主回合 1 确实派生了子任务——但它没有新增快照锚点（折叠进主回合时间线）");
        } finally {
            session.stop();
        }
    }

    // ---- helpers ----

    private static LlmClient.ChatResponse spawnCall(String prompt) {
        String args = "{\"description\":\"子任务\",\"prompt\":\"" + prompt + "\"}";
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "c1", new LlmClient.ToolCall.Function("spawn_agent", args));
        return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 100, 20);
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static Renderer silentRenderer() {
        return new Renderer() {
            private final PrintStream discard =
                    new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);

            @Override public void start() {}
            @Override public void close() {}
            @Override public PrintStream stream() { return discard; }
            @Override public void appendToolCalls(List<ToolCallView> toolCalls) {}
            @Override public void appendDiff(String filePath, String before, String after) {}
            @Override public void updateStatus(StatusInfo status) {}
            @Override public ApprovalResult promptApproval(ApprovalRequest request) {
                return ApprovalResult.approve();
            }
            @Override public int openPalette(String title, List<String> items) { return -1; }
        };
    }

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
