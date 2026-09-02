package com.cafecli.cli;

import com.cafecli.agent.Agent;
import com.cafecli.agent.AgentSession;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.runtime.CancellationToken;
import com.cafecli.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消的**范围**与**落点**（issue 08 的[[取消键]]表，两键共享）。
 *
 * <p>范围与 {@code CONTEXT.md} 既有定义一致，**不得扩大**：只碰当前前台回合与仍[[附着]]的[[任务]]；
 * [[游离]]任务不受影响，仍只经 {@code /task cancel} 单独终止。落点是**回合边界**：取消是协作式的，
 * 流式回答不被中途截断。
 *
 * <p>范围那半在 S-B（真 REPL、真按键）上断言，落点那半在 S-D（桩 {@link LlmClient} + 真
 * {@link Agent} 的 ReAct 循环）上断言——它问的是 agent 什么时候看那个令牌，而不是键怎么被路由的。
 */
class CancellationScopeTest {

    /**
     * 取消只波及前台回合与仍附着的任务；游离任务继续运行，且仍可经 {@code /task cancel} 单独终止。
     */
    @Test
    @Timeout(30)
    void cancellingTouchesTheForegroundTurnAndItsAttachedTasksAndNothingElse() throws Exception {
        try (CancelKeyTableTest.Rig rig = new CancelKeyTableTest.Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("spawn some work\n");
            rig.awaitTurnToken();

            CancellationToken attached = rig.cancellations.registerTask("task_1", true);
            CancellationToken detached = rig.cancellations.registerTask("task_2", false);

            rig.pressCtrlC();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!rig.turnToken.isCancelled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            assertTrue(rig.turnToken.isCancelled(), "前台回合被取消");
            assertTrue(attached.isCancelled(), "仍[[附着]]的任务随前台回合一起被取消");
            assertFalse(detached.isCancelled(),
                    "[[游离]]任务不受[[取消键]]影响——它没有人在等，也没人替它做决定");

            assertTrue(rig.cancellations.cancelTask("task_2"),
                    "游离任务仍可经 /task cancel 单独终止");
            assertTrue(detached.isCancelled());
        }
    }

    /**
     * 取消**落在回合边界**：按下去的那一刻先给反馈，而正在流式吐出的回答**不被中途截断**。
     *
     * <p>这条走 S-D 而不是 S-B：被断言的是 {@link Agent} 的 ReAct 循环什么时候去看那个令牌，与按键
     * 怎么被路由无关。桩模型在第一次调用里逐字流出一段回答，取消发生在**流的中途**；判据是流出来的
     * 每一个字都到了渲染器，且第二次 LLM 调用**没有发生**——回合在下一个边界上停住，而不是把话说到
     * 一半就没了。
     */
    @Test
    @Timeout(30)
    void cancellationLandsOnATurnBoundaryAndDoesNotTruncateTheStreamingAnswer() throws Exception {
        CancellationRegistry cancellations = new CancellationRegistry();
        StreamingStub llm = new StreamingStub();
        CollectingRenderer renderer = new CollectingRenderer();
        Agent agent = new Agent(llm, new ToolRegistry());
        agent.setRenderer(renderer);
        AgentSession session = new AgentSession(agent, new TaskRegistry(), output -> { });
        SessionInterruptGuard interrupts =
                new SessionInterruptGuard(cancellations, renderer, null, session);
        // 与 Main 的装配同形：每个前台回合各持一个显式令牌。
        session.setTurnRunner(input -> {
            CancellationToken token = cancellations.beginForegroundTurn();
            agent.setCancellationToken(token);
            try {
                return agent.run(input);
            } finally {
                cancellations.endForegroundTurn(token);
                agent.setCancellationToken(null);
            }
        });
        // 流到一半时按下取消——这正是「回答还在吐」的那一刻。
        llm.cancelMidStream = interrupts::interrupt;
        session.start();
        try {
            session.postEvent(new AgentSession.UserSubmit("do the thing"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(20)), "回合必须自己停下来");

            assertEquals(StreamingStub.ANSWER, renderer.streamed(),
                    "流式回答不得被中途截断——取消落在边界上，不在字里");
            assertEquals(1, llm.calls.get(),
                    "取消之后不得再发起下一次 LLM 调用：回合在边界停住");
        } finally {
            session.stop();
        }
    }

    /**
     * 上一条的**对照组**：同一个桩、同一条装配，只是没人按取消——于是回合照常走到第二次 LLM 调用。
     *
     * <p>没有它，「第二次调用没有发生」只证明了这个桩本来就只被调一次。
     */
    @Test
    @Timeout(30)
    void theControlGroupWithNobodyCancellingRunsOnIntoTheNextIteration() throws Exception {
        CancellationRegistry cancellations = new CancellationRegistry();
        StreamingStub llm = new StreamingStub();
        CollectingRenderer renderer = new CollectingRenderer();
        Agent agent = new Agent(llm, new ToolRegistry());
        agent.setRenderer(renderer);
        AgentSession session = new AgentSession(agent, new TaskRegistry(), output -> { });
        session.setTurnRunner(input -> {
            CancellationToken token = cancellations.beginForegroundTurn();
            agent.setCancellationToken(token);
            try {
                return agent.run(input);
            } finally {
                cancellations.endForegroundTurn(token);
                agent.setCancellationToken(null);
            }
        });
        session.start();
        try {
            session.postEvent(new AgentSession.UserSubmit("do the thing"));
            assertTrue(session.awaitIdle(Duration.ofSeconds(20)));

            assertEquals(StreamingStub.ANSWER, renderer.streamed());
            assertTrue(llm.calls.get() > 1,
                    "对照组必须真的走进第二次调用，否则上一条断言的是一个恒真的东西");
        } finally {
            session.stop();
        }
    }

    /**
     * 桩模型：第一次调用逐字流出一段回答并**在流的中途**触发取消，然后返回一个工具调用——若 agent
     * 不在边界上停下来，它就会进入第二次调用。
     */
    private static final class StreamingStub implements LlmClient {

        static final String ANSWER = "先读文件，再改一行，最后跑测试。";

        final AtomicInteger calls = new AtomicInteger();
        volatile Runnable cancelMidStream = () -> { };

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            int call = calls.incrementAndGet();
            if (call > 1) {
                return new ChatResponse("assistant", "second call", List.of(), 0, 0);
            }
            for (int i = 0; i < ANSWER.length(); i++) {
                if (i == ANSWER.length() / 2) {
                    cancelMidStream.run();
                }
                listener.onContentDelta(String.valueOf(ANSWER.charAt(i)));
            }
            return new ChatResponse("assistant", ANSWER,
                    List.of(new ToolCall("call_1", new ToolCall.Function("read_file", "{}"))),
                    0, 0);
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    }

    /** 只关心「答案的字到底有没有全部到达」的渲染器替身。 */
    private static final class CollectingRenderer implements Renderer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        private final StringBuilder answer = new StringBuilder();

        String streamed() {
            return answer.toString();
        }

        @Override
        public void start() {
        }

        @Override
        public BlockWriter beginAnswer() {
            return new BlockWriter() {
                @Override
                public void append(String sourceDelta) {
                    answer.append(sourceDelta);
                }

                @Override
                public void finish() {
                }
            };
        }

        @Override
        public void close() {
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(com.cafecli.render.StatusInfo status) {
        }

        @Override
        public com.cafecli.hitl.ApprovalResult promptApproval(
                com.cafecli.hitl.ApprovalRequest request) {
            return com.cafecli.hitl.ApprovalResult.approve();
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }
    }
}
