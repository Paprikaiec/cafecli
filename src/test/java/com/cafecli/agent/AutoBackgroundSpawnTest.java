package com.cafecli.agent;

import com.cafecli.agent.task.Task;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.llm.GLMClient;
import com.cafecli.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #08：交互模式（接了 backgroundSink）下前台派生超阈值自动转后台——解除本轮阻塞返回「已转入后台」、
 * 任务继续跑、完成后经 backgroundSink push；未超阈值则前台直接拿结果，不转后台、不 push。headless（无
 * backgroundSink）下前台无限阻塞（见其它测试）。
 */
class AutoBackgroundSpawnTest {

    @Test
    void foregroundSpawnAutoBackgroundsPastThresholdThenPushesResult() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        CountDownLatch releaseChild = new CountDownLatch(1);
        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            if (conversation.contains("SLOW_PROMPT")) {
                try {
                    releaseChild.await(5, TimeUnit.SECONDS);   // 把子任务卡住，超过阈值
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return text("SLOW_RESULT 调研完成");
            }
            return text("fallback");
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        List<String> pushed = new CopyOnWriteArrayList<>();
        CountDownLatch pushedLatch = new CountDownLatch(1);

        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, null);
        spawner.setBackgroundCompletionSink((taskId, notification) -> {
            pushed.add(notification);
            pushedLatch.countDown();
        });
        spawner.setAutoBackgroundMillis(100);   // 小阈值便于触发

        String result = spawner.spawn("慢调研", "SLOW_PROMPT");
        assertTrue(result.contains("转入后台"), "超阈值前台派生应自动转后台，实际：" + result);
        assertEquals(1, registry.tasks().size());

        // 放行子任务 → 完成 → 经 backgroundSink push 结果回来（无需用户再问）
        releaseChild.countDown();
        assertTrue(pushedLatch.await(5, TimeUnit.SECONDS), "auto-bg 的任务完成后应 push 结果回来");
        assertTrue(pushed.stream().anyMatch(s -> s.contains("SLOW_RESULT")),
                "push 的应是子任务最终结果，实际：" + pushed);
        assertEquals(Task.State.DONE, registry.tasks().get(0).state(), "任务最终 DONE");
    }

    @Test
    void fastForegroundSpawnReturnsInlineWithoutBackgrounding() {
        TaskRegistry registry = new TaskRegistry();
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(conversation -> text("FAST_RESULT 完成"));
        List<String> pushed = new CopyOnWriteArrayList<>();

        ForegroundAgentSpawner spawner = new ForegroundAgentSpawner(llm, registry, null);
        spawner.setBackgroundCompletionSink((id, notification) -> pushed.add(notification));
        spawner.setAutoBackgroundMillis(5000);   // 大阈值，快任务不会触发

        String result = spawner.spawn("快任务", "FAST_PROMPT");
        assertTrue(result.contains("FAST_RESULT"), "未超阈值应前台直接返回结果，实际：" + result);
        assertFalse(result.contains("转入后台"), "未超阈值不应转后台");
        assertTrue(pushed.isEmpty(), "未转后台不应 push");
    }

    // ---- helpers ----

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
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
