package com.cafecli.agent;

import com.cafecli.agent.task.AdmissionControl;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.GLMClient;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #02 验收：一条 assistant 消息里的多个 {@code spawn_agent} 通过既有 tool-batch 并行 fan-out，
 * 每个子任务跑在自己隔离的 ToolRegistry 上；并发写同一文件由进程级 FileMutationLocks（ADR 0001）串行。
 */
class ParallelSpawnAgentTest {

    /**
     * 并行 fan-out：一条 assistant 消息发两个 spawn_agent → 两个子任务的 stub chat()
     * 在共享 CountDownLatch 屏障上同时阻塞 → 并发峰值至少 2，证明它们并发而非串行执行。
     */
    @Test
    void twoSpawnAgentCallsRunConcurrently() {
        TaskRegistry taskRegistry = new TaskRegistry();
        CountDownLatch barrier = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            // 主 Agent：已拿到两个子任务结果（tool 消息）→ 收尾。
            if (conversation.contains("CHILD_A_DONE") || conversation.contains("CHILD_B_DONE")) {
                return text("两个子任务都完成了");
            }
            // 子任务 A / B：在屏障上阻塞，记录并发峰值，再返回各自结果。
            if (conversation.contains("CHILD_A_PROMPT")) {
                return awaitBarrier(barrier, current, peak, text("CHILD_A_DONE"));
            }
            if (conversation.contains("CHILD_B_PROMPT")) {
                return awaitBarrier(barrier, current, peak, text("CHILD_B_DONE"));
            }
            // 主 Agent 第一步：一条消息里两个 spawn_agent。
            return twoSpawnCalls();
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, taskRegistry, toolRegistry));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        main.run("并行调研两件事");

        assertEquals(2, peak.get(), "两个 spawn_agent 应并发执行（并发峰值 = 2）");
        assertEquals(2, taskRegistry.tasks().size(), "应登记两个子任务");
    }

    /**
     * 同文件写串行（ADR 0001）：两个子任务各写同一文件一次，进程级 FileMutationLocks 串行落盘，
     * 无丢失更新 —— 文件最终内容是两者之一（而非半截/损坏），且两次写都真实发生过（登记表两条 DONE）。
     */
    @Test
    void twoTasksWritingSameFileAreSerialized(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("shared.txt");
        Files.writeString(target, "INIT");

        TaskRegistry taskRegistry = new TaskRegistry();

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            if (conversation.contains("WROTE_A") || conversation.contains("WROTE_B")) {
                return text("两个子任务都写完了");
            }
            // 子任务 A：写 "A" 到共享文件，然后收尾。
            if (conversation.contains("WRITE_A_PROMPT")) {
                if (conversation.contains("文件已写入")) {
                    return text("WROTE_A");
                }
                return writeFileCall(target, "AAAA");
            }
            // 子任务 B：写 "B" 到共享文件，然后收尾。
            if (conversation.contains("WRITE_B_PROMPT")) {
                if (conversation.contains("文件已写入")) {
                    return text("WROTE_B");
                }
                return writeFileCall(target, "BBBB");
            }
            return twoWriteSpawnCalls();
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(llm, taskRegistry, toolRegistry));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        main.run("并行写同一个文件");

        assertEquals(2, taskRegistry.tasks().size(), "应登记两个写文件子任务");
        // 串行落盘 → 文件内容是某一次完整写入（无半截/交错），不会是空或损坏。
        String content = Files.readString(target);
        assertTrue(content.equals("AAAA") || content.equals("BBBB"),
                "同文件写应被串行化为某一次完整写入，实际：" + content);
    }

    /**
     * 准入上限约束峰值并发（ADR 0006）：一条消息派生 3 个任务，但准入上限=2 → 同时运行的任务峰值=2。
     * latch(2) 保证至少 2 个并发（峰值 ≥ 2），准入信号量保证 ≤ 2，故峰值恰为 2。
     */
    @Test
    void admissionCapsPeakConcurrencyBelowLimit() {
        TaskRegistry taskRegistry = new TaskRegistry();
        CountDownLatch overlap = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = conversation -> {
            if (conversation.contains("CONC_DONE")) {
                return text("三个子任务都跑完了");
            }
            if (conversation.contains("CONC_PROMPT")) {
                return recordConcurrency(current, peak, overlap, text("CONC_DONE"));
            }
            return threeSpawnCalls();
        };
        DispatchingStubLlmClient llm = new DispatchingStubLlmClient(dispatcher);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setAgentSpawner(new ForegroundAgentSpawner(
                llm, taskRegistry, toolRegistry, new AdmissionControl(2, 5)));

        Agent main = new Agent(llm, toolRegistry);
        main.setRenderer(silentRenderer());

        main.run("并行三件事");

        assertEquals(3, taskRegistry.tasks().size(), "应登记三个子任务");
        assertEquals(2, peak.get(),
                "准入上限=2 应把峰值并发运行任务约束在 2（即便一条消息派生了 3 个）");
    }

    // ---- helpers ----

    private static LlmClient.ChatResponse recordConcurrency(AtomicInteger current, AtomicInteger peak,
                                                            CountDownLatch overlap, LlmClient.ChatResponse resp) {
        int now = current.incrementAndGet();
        peak.updateAndGet(p -> Math.max(p, now));
        overlap.countDown();
        try {
            overlap.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return resp;
    }

    private static LlmClient.ChatResponse threeSpawnCalls() {
        LlmClient.ToolCall a = spawn("c_a", "并发A", "CONC_PROMPT_A");
        LlmClient.ToolCall b = spawn("c_b", "并发B", "CONC_PROMPT_B");
        LlmClient.ToolCall c = spawn("c_c", "并发C", "CONC_PROMPT_C");
        return new LlmClient.ChatResponse("assistant", "", null, List.of(a, b, c), 100, 20);
    }

    private static LlmClient.ChatResponse awaitBarrier(CountDownLatch latch, AtomicInteger current,
                                                       AtomicInteger peak, LlmClient.ChatResponse response) {
        int now = current.incrementAndGet();
        peak.updateAndGet(prev -> Math.max(prev, now));
        latch.countDown();
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "两个子任务应同时到达 chat()");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return response;
    }

    private static LlmClient.ChatResponse twoSpawnCalls() {
        LlmClient.ToolCall a = spawn("call_a", "调研A", "CHILD_A_PROMPT");
        LlmClient.ToolCall b = spawn("call_b", "调研B", "CHILD_B_PROMPT");
        return new LlmClient.ChatResponse("assistant", "", null, List.of(a, b), 100, 20);
    }

    private static LlmClient.ChatResponse twoWriteSpawnCalls() {
        LlmClient.ToolCall a = spawn("call_a", "写A", "WRITE_A_PROMPT");
        LlmClient.ToolCall b = spawn("call_b", "写B", "WRITE_B_PROMPT");
        return new LlmClient.ChatResponse("assistant", "", null, List.of(a, b), 100, 20);
    }

    private static LlmClient.ToolCall spawn(String id, String desc, String prompt) {
        String args = "{\"description\":\"" + desc + "\",\"prompt\":\"" + prompt + "\"}";
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function("spawn_agent", args));
    }

    private static LlmClient.ChatResponse writeFileCall(Path target, String content) {
        String args = "{\"path\":\"" + target.getFileName() + "\",\"content\":\"" + content + "\"}";
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "wcall", new LlmClient.ToolCall.Function("write_file", args));
        return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 50, 10);
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

    /** 按整段对话内容派发响应，线程安全，主/子共用。 */
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
