package com.paicli.agent;

import com.paicli.agent.task.Task;
import com.paicli.agent.task.TaskRegistry;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #07 验收（headless seam）：事件循环 {@link AgentSession} 经 postEvent(UserSubmit|TaskDone) 驱动，
 * 单串行执行器是主对话唯一写入者；后台任务完成 push 一个自治回合、结果以带标签 {@code <task-notification>}
 * user 块注入并交给 push sink；回合边界上多个 TaskDone drain 进同一回合；spawn_agent({background:true})
 * 立即返回 task_id、本轮不阻塞，完成后 push 回来。全部用桩 LlmClient 在无终端下驱动断言。
 */
class AgentSessionTest {

    @Test
    void userSubmitRunsTurnAndWritesMainConversation() throws Exception {
        CapturingSink sink = new CapturingSink();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(conversation ->
                conversation.contains("HELLO_INPUT") ? text("MAIN_REPLY") : text("?"));
        AgentSession session = newSession(llm, sink);
        session.start();
        try {
            session.postEvent(new AgentSession.UserSubmit("HELLO_INPUT"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)), "回合应处理完毕");

            String history = dump(session.mainAgent());
            assertTrue(history.contains("user:HELLO_INPUT"), "用户输入应进主对话");
            assertTrue(history.contains("assistant:MAIN_REPLY"), "主 Agent 回复应进主对话");
            assertTrue(sink.captured().stream().anyMatch(s -> s.contains("MAIN_REPLY")),
                    "回合输出应交给 push sink");
        } finally {
            session.stop();
        }
    }

    @Test
    void taskDonePushInjectsLabeledNotificationAndRunsAutonomousTurn() throws Exception {
        CapturingSink sink = new CapturingSink();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(conversation ->
                conversation.contains("TASK_RESULT_XYZ") ? text("RELAYED_TO_USER") : text("?"));
        AgentSession session = newSession(llm, sink);
        session.start();
        try {
            session.postEvent(new AgentSession.TaskDone("task_1", "TASK_RESULT_XYZ"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)));

            String history = dump(session.mainAgent());
            assertTrue(history.contains("<task-notification task_id=\"task_1\">"),
                    "后台结果应以带标签 <task-notification> user 块注入");
            assertTrue(history.contains("TASK_RESULT_XYZ"), "通知应携带任务的 result-only 文本");
            assertTrue(history.contains("assistant:RELAYED_TO_USER"),
                    "执行器应跑一个自治回合处理通知");
            assertTrue(sink.captured().stream().anyMatch(s -> s.contains("RELAYED_TO_USER")),
                    "push 回合输出应经 sink 渲染（Main 里走 printAbove）");
        } finally {
            session.stop();
        }
    }

    @Test
    void multipleTaskDoneDrainIntoOneTurnAtBoundary() throws Exception {
        CapturingSink sink = new CapturingSink();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch firstTurnEntered = new CountDownLatch(1);
        AtomicInteger notificationTurns = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            if (conversation.contains("<task-notification")) {
                notificationTurns.incrementAndGet();
                return text("HANDLED_NOTIFICATIONS");
            }
            if (conversation.contains("START_INPUT")) {
                // 把执行器卡在第一个回合里，给我们时间把两个 TaskDone 排进队列
                firstTurnEntered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return text("STARTED");
            }
            return text("?");
        };
        AgentSession session = newSession(new DispatchingStubLlmClient(dispatcher), sink);
        session.start();
        try {
            session.postEvent(new AgentSession.UserSubmit("START_INPUT"));
            assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS), "第一个回合应已开始");

            // 执行器忙于第一个回合时，两个后台任务先后完成 → 都排进队列
            session.postEvent(new AgentSession.TaskDone("task_1", "RESULT_ONE"));
            session.postEvent(new AgentSession.TaskDone("task_2", "RESULT_TWO"));
            gate.countDown();
            assertTrue(session.awaitIdle(Duration.ofSeconds(5)));

            assertEquals(1, notificationTurns.get(),
                    "两个 TaskDone 应在回合边界 drain 进同一个自治回合（只跑一次）");
            String history = dump(session.mainAgent());
            assertTrue(history.contains("task_1") && history.contains("RESULT_ONE"), "回合应含 task_1 通知");
            assertTrue(history.contains("task_2") && history.contains("RESULT_TWO"), "回合应含 task_2 通知");
        } finally {
            session.stop();
        }
    }

    @Test
    void backgroundSpawnReturnsImmediatelyAndPushesResultBack() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        CountDownLatch relayed = new CountDownLatch(1);
        CapturingSink sink = new CapturingSink(s -> {
            if (s.contains("BG_RELAYED")) {
                relayed.countDown();
            }
        });

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            // 自治回合：主 Agent 收到后台任务通知
            if (conversation.contains("<task-notification") && conversation.contains("BG_RESULT")) {
                return text("BG_RELAYED 已把后台结论汇报给你");
            }
            // 后台子任务跑自己的回合
            if (conversation.contains("BG_CHILD_PROMPT")) {
                return text("BG_RESULT");
            }
            // 主 Agent 派完后台任务后本轮收尾（不阻塞）
            if (conversation.contains("已在后台启动")) {
                return text("已派出后台任务，我先继续");
            }
            // 主 Agent 第一轮：派一个后台任务
            return spawnBackgroundCall("BG_CHILD_PROMPT");
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        ToolRegistry toolRegistry = new ToolRegistry();
        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, toolRegistry);
        toolRegistry.setAgentSpawner(spawner);

        Agent mainAgent = new Agent(llm, toolRegistry);
        mainAgent.setRenderer(silentRenderer());
        AgentSession session = new AgentSession(mainAgent, registry, sink);
        // 后台完成 → 投递回事件循环
        spawner.setBackgroundCompletionSink(session::onBackgroundTaskDone);
        session.start();
        try {
            session.postEvent(new AgentSession.UserSubmit("派个后台任务去调研"));
            assertTrue(relayed.await(5, TimeUnit.SECONDS),
                    "后台任务完成应 push 一个自治回合并被处理（无需用户再提问）");

            assertEquals(1, registry.tasks().size(), "应登记一个后台任务");
            assertEquals(Task.State.DONE, registry.tasks().get(0).state(), "后台任务应完成");
            String history = dump(mainAgent);
            assertTrue(history.contains("已派出后台任务"),
                    "派生轮应不阻塞地继续/收尾（拿到 task_id 即返回）");
            assertTrue(history.contains("<task-notification") && history.contains("BG_RESULT"),
                    "后台结论应以带标签通知 push 回主对话");
        } finally {
            session.stop();
        }
    }

    // ---- helpers ----

    private static AgentSession newSession(LlmClient llm, Consumer<String> sink) {
        ToolRegistry toolRegistry = new ToolRegistry();
        Agent mainAgent = new Agent(llm, toolRegistry);
        mainAgent.setRenderer(silentRenderer());
        return new AgentSession(mainAgent, new TaskRegistry(), sink);
    }

    private static String dump(Agent agent) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : agent.getConversationHistory()) {
            sb.append(m.role() == null ? "" : m.role()).append(':')
                    .append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.toString();
    }

    private static LlmClient.ChatResponse spawnBackgroundCall(String prompt) {
        String args = "{\"description\":\"后台调研\",\"prompt\":\"" + prompt + "\",\"background\":true}";
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "bg1", new LlmClient.ToolCall.Function("spawn_agent", args));
        return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 100, 20);
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static final class CapturingSink implements Consumer<String> {
        private final CopyOnWriteArrayList<String> captured = new CopyOnWriteArrayList<>();
        private final Consumer<String> onEach;

        CapturingSink() {
            this(s -> {});
        }

        CapturingSink(Consumer<String> onEach) {
            this.onEach = onEach;
        }

        @Override
        public void accept(String s) {
            captured.add(s == null ? "" : s);
            onEach.accept(s == null ? "" : s);
        }

        List<String> captured() {
            return captured;
        }
    }

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
