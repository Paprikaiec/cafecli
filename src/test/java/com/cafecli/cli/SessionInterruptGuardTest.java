package com.cafecli.cli;

import com.cafecli.agent.Agent;
import com.cafecli.agent.AgentSession;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.tool.ToolRegistry;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.inline.TracingTerminal;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.runtime.CancellationToken;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级 SIGINT 兜底（ADR 0016 D3 / issue 12）。
 *
 * <p>被断言的是**用户看得见的结果**：进程还在、回合停了、[[游离]]任务没被牵连、那句反馈进了转写。
 * 不断言装了几个 handler、也不断言写了什么字节。
 *
 * <p>最后那条用例用的是 issue 06 的 seam（管道流驱动的 {@link InteractiveRepl}）。它当初留在这里而不是
 * 放进 {@code InteractiveReplTest}，是因为那个类整类跑不完：{@code pumpWaitUntilIdle} 的
 * {@code readRuntimeControl} 在回合期间会把非 ESC 字节静默 drain 掉，于是排在回合之前写进管道的
 * {@code ^D} 消失、随后的 {@code readLine} 永远等不到 EOF。**issue 07 把那个轮询器整个删掉之后这个挂起
 * 也随之消失**（实测 2026-08-28：{@code InteractiveReplTest} 11 条 6.8s 全绿，{@code -Pquick} 62s 跑完）。
 */
class SessionInterruptGuardTest {

    /**
     * 有前台回合在跑时，一次中断把它和仍[[附着]]的任务一起停下——范围与 ESC 完全一致，
     * 不多一个也不少一个：[[游离]]任务继续跑，仍然只能经 {@code /task cancel} 终止。
     */
    @Test
    void anInterruptStopsTheRunningTurnAndItsAttachedTasksAndNothingElse() {
        CancellationRegistry cancellations = new CancellationRegistry();
        CancellationToken turn = cancellations.beginForegroundTurn();
        CancellationToken attached = cancellations.registerTask("task_1", true);
        CancellationToken detached = cancellations.registerTask("task_2", false);
        RecordingRenderer renderer = new RecordingRenderer();

        new SessionInterruptGuard(cancellations, renderer, null, null).interrupt();

        assertTrue(turn.isCancelled(), "前台回合应被请求取消");
        assertTrue(attached.isCancelled(), "附着任务随前台回合一起停");
        assertFalse(detached.isCancelled(),
                "游离任务不受影响，只能经 /task cancel 终止（范围不得扩大）");
    }

    /**
     * 取消是协作式的（回合在边界才停），所以按下去的那一刻必须立刻有反应——与 ESC 同一句话，
     * 走同一条 {@link Renderer#stream()} 通道，因此它也会进有序提交日志。
     */
    @Test
    void theUserIsToldImmediatelyThroughTheTranscriptRatherThanTheTerminal() {
        CancellationRegistry cancellations = new CancellationRegistry();
        cancellations.beginForegroundTurn();
        RecordingRenderer renderer = new RecordingRenderer();

        new SessionInterruptGuard(cancellations, renderer, null, null).interrupt();

        assertTrue(renderer.text().contains("已请求取消"),
                "取消要有即时反馈，且经转写而不是直写终端：" + renderer.text());
    }

    /**
     * 没有可停的东西时**什么都不做**——本 issue 的全部要点就是「不死」。
     *
     * <p>明码标价的代价（ADR 0016 D3）：Ctrl+C 因此不再是卡死进程的逃生口。这条断言正是那条
     * 代价的形状，写在这里是为了让任何「空闲时改回默认处理器」的变体立刻变红——那会把条件分支
     * 带回规则里，是被否决过的形态。
     */
    @Test
    void anInterruptWithNothingToCancelDoesNothingAtAll() {
        CancellationRegistry cancellations = new CancellationRegistry();
        CancellationToken detached = cancellations.registerTask("task_9", false);
        RecordingRenderer renderer = new RecordingRenderer();

        new SessionInterruptGuard(cancellations, renderer, null, null).interrupt();

        assertEquals("", renderer.text(), "无回合可停时不该说任何话：" + renderer.text());
        assertFalse(detached.isCancelled(), "更不该动游离任务");
    }

    /**
     * 反馈说不出去时，回合照样停，而且**不许有东西从 signal 线程漏到终端上**。
     *
     * <p>JLine 的信号代理不接 handler 抛出的异常（`Signals.register` 的 `InvocationHandler` 直接
     * `handler.run()`），JDK 的信号分发线程也不接——一个漏出去的异常会由默认处理器把栈打到 stderr，
     * 那正是「signal 线程从不直写终端」这条不变式禁止的事，而此刻屏幕上可能正开着[[转写浏览层]]。
     * 所以取消先做、说话在后，且说话失败不改变已经做成的事。
     */
    @Test
    void aRendererThatCannotSpeakStillGetsTheTurnStoppedAndThrowsNothingAtTheSignalThread() {
        CancellationRegistry cancellations = new CancellationRegistry();
        CancellationToken turn = cancellations.beginForegroundTurn();
        Renderer broken = new RecordingRenderer() {
            @Override
            public PrintStream stream() {
                throw new IllegalStateException("renderer is gone");
            }
        };

        new SessionInterruptGuard(cancellations, broken, null, null).interrupt();

        assertTrue(turn.isCancelled(), "反馈是附带的，停回合不是");
    }

    /**
     * 空闲态**行为与今天完全一致**，而这不是靠约定、是靠 JLine 自己的存档/还原：
     * {@code LineReaderImpl.readLine} 进读时把 INT 换成自己的（中断读线程），退出时还原前一个。
     * 所以会话级 handler 在 {@code readLine} 期间被盖住、出了 {@code readLine} 才轮到它——正好是
     * 今天裸着的那些间隙（等待泵、[[转写浏览层]]、斜杠 palette、审批读键）。
     *
     * <p>本用例在**真的 JLine 行编辑器**上验证这条前提（ADR 0016 D3 把它列为「未验证、应实测而非
     * 论证」），不是重述文档：信号经 {@code terminal.raise} 走 JLine 自己的处理器注册表分发。
     */
    @Test
    @Timeout(30)
    void jlineOwnsTheInterruptWhileItIsReadingAndTheSessionGetsItBackAfterwards() throws Exception {
        try (Rig rig = new Rig()) {
            // 1. readLine 之外：会话级兜底接管，回合停下。
            CancellationToken beforeReading = rig.cancellations.beginForegroundTurn();
            rig.terminal.raise(Terminal.Signal.INT);
            assertTrue(beforeReading.isCancelled(),
                    "读取间隙里的 Ctrl+C 必须停回合而不是杀进程");
            rig.cancellations.endForegroundTurn(beforeReading);

            // 2. readLine 之内：JLine 的 handler 在上面，行为不变。
            LineReader reader = LineReaderBuilder.builder().terminal(rig.terminal).build();
            CancellationToken whileReading = rig.cancellations.beginForegroundTurn();
            List<String> surfaced = new CopyOnWriteArrayList<>();
            Thread reading = new Thread(() -> {
                try {
                    surfaced.add("line:" + reader.readLine("> "));
                } catch (UserInterruptException e) {
                    surfaced.add("UserInterruptException");
                } catch (Throwable t) {
                    surfaced.add(t.getClass().getSimpleName());
                }
            }, "sigint-guard-readline");
            reading.setDaemon(true);
            reading.start();
            waitUntil(reader::isReading);
            rig.terminal.raise(Terminal.Signal.INT);
            reading.join(10_000);

            assertEquals(List.of("UserInterruptException"), surfaced,
                    "空闲态仍由 JLine 处理：readLine 抛 UserInterruptException，REPL continue");
            assertFalse(whileReading.isCancelled(),
                    "readLine 期间会话级 handler 被盖住，本 issue 不得改动空闲态");

            // 3. readLine 结束后前一个 handler 被还原，兜底重新生效。
            rig.terminal.raise(Terminal.Signal.INT);
            assertTrue(whileReading.isCancelled(),
                    "readLine 退出后 JLine 还原前一个 handler，兜底必须重新生效");
        }
    }

    /**
     * 同一条前提的另一半（ADR 0016 D3 「未验证」段）：一个 widget 正阻塞在自己的
     * {@code terminal.reader().read()} 里时，中断由谁处理、读出什么。
     *
     * <p>实测结论钉在这里，因为 issue 07 之后[[转写浏览层]]永远跑在 widget 里：JLine 的 handler
     * 中断**读线程**，阻塞中的 {@code read()} 因此抛 {@code InterruptedIOException}，会话级兜底
     * 一个字节都没看到；而 widget 吃掉那个异常之后，{@code readLine} **不抛
     * {@code UserInterruptException}、继续读**——中断就此消失。**issue 07 据此把取消交给了浏览层
     * 自己**（`TranscriptOverlay` 的 {@code onInterrupt}，由 {@code InlineRenderer.bindSessionInterrupt}
     * 注入本 guard 的 {@code interrupt()}），端到端的判据在
     * {@code ResidentInputRegimeTest.ctrlCInsideTheOverlayStopsTheTurnAndLeavesTheOverlayOpen}。
     * 本用例继续钉住那条前提本身：它若变了，那个修复的理由就没了。
     */
    @Test
    @Timeout(30)
    void aWidgetBlockedOnItsOwnReadIsInterruptedByJlineAndNotBySessionGuard() throws Exception {
        try (Rig rig = new Rig()) {
            LineReader reader = LineReaderBuilder.builder().terminal(rig.terminal).build();
            CountDownLatch insideWidget = new CountDownLatch(1);
            List<String> widgetSaw = new CopyOnWriteArrayList<>();
            reader.getWidgets().put("blocking-modal", () -> {
                insideWidget.countDown();
                try {
                    widgetSaw.add("read:" + rig.terminal.reader().read());
                } catch (Throwable t) {
                    widgetSaw.add(t.getClass().getSimpleName());
                }
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("blocking-modal"), KeyMap.ctrl('O'));

            CancellationToken whileReading = rig.cancellations.beginForegroundTurn();
            List<String> surfaced = new CopyOnWriteArrayList<>();
            Thread reading = new Thread(() -> {
                try {
                    surfaced.add("line:" + reader.readLine("> "));
                } catch (Throwable t) {
                    surfaced.add(t.getClass().getSimpleName());
                }
            }, "sigint-guard-widget");
            reading.setDaemon(true);
            reading.start();
            waitUntil(reader::isReading);
            rig.typeByte(15);                       // Ctrl+O：进 widget，阻塞在自己的 read 上
            assertTrue(insideWidget.await(10, TimeUnit.SECONDS));
            rig.terminal.raise(Terminal.Signal.INT);
            // UserInterruptException 会立刻到；等两秒是为了让「它不来」这件事是观察到的而不是猜的。
            reading.join(2_000);

            assertEquals(List.of("InterruptedIOException"), widgetSaw,
                    "阻塞中的 widget read 收到的是线程中断，不是我们的 handler");
            assertFalse(whileReading.isCancelled(),
                    "readLine 期间（含 widget 期间）会话级兜底仍被盖住");
            assertEquals(List.of(), surfaced,
                    "widget 吃掉中断后 readLine 继续读：UserInterruptException 不会浮上来（issue 07 须自己认下它）");
        }
    }

    /**
     * 回合期等待泵里的 Ctrl+C，经 issue 06 建立的 seam 驱动（无真实 TTY 的管道流 REPL）：
     * **会话活下来**，回合被请求取消，走的是 ESC 那条取消通路。
     *
     * <p>触发点是注入的假信号——真 SIGINT 落到会话级 handler 上就是这一次 {@code interrupt()}
     * 调用；单元里投递真信号既不可控也无必要，要断言的是「产生了什么取消事件、以及没退出」。
     * 「没退出」在这里有可观察的形状：REPL 继续读，下一条提交照常跑完一个回合。
     *
     * <p>这条用例不放在 {@code InteractiveReplTest} 里，是因为该类目前整类跑不完（见类注释末）。
     */
    @Test
    @Timeout(30)
    void ctrlCDuringAForegroundTurnStopsTheTurnAndTheSessionKeepsServing() throws Exception {
        try (ReplRig rig = new ReplRig()) {
            SessionInterruptGuard guard = new SessionInterruptGuard(rig.cancellations, rig.renderer, null, null);
            rig.session.setTurnRunner(input -> {
                if (!input.startsWith("long")) {
                    rig.turns.add(input);
                    return "turn-done";
                }
                // 取消是协作式的：回合 park 在自己的令牌上，在边界自己停。
                CancellationToken token = rig.cancellations.beginForegroundTurn();
                try {
                    long deadline = System.currentTimeMillis() + 5_000;
                    while (!token.isCancelled() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                    rig.turns.add(token.isCancelled() ? "CANCELLED" : "RAN_TO_COMPLETION");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    rig.cancellations.endForegroundTurn(token);
                }
                return "turn-done";
            });

            Thread repl = rig.runInBackground();
            rig.type("long running\n");
            waitUntil(() -> !rig.session.isIdle());

            guard.interrupt();

            waitUntil(() -> !rig.turns.isEmpty());
            assertEquals(List.of("CANCELLED"), rig.turns);

            // 会话没死：等待泵把输入交回来，下一条提交照常跑。
            waitUntil(rig.session::isIdle);
            rig.type("still here\n");
            waitUntil(() -> rig.turns.size() > 1);
            assertEquals(List.of("CANCELLED", "still here"), rig.turns,
                    "Ctrl+C 停的是回合，不是会话");

            rig.endOfFile();
            repl.join(5_000);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    // --- test doubles ------------------------------------------------------------------------------

    /** 管道流驱动的终端 + 装好的兜底：没有真实 TTY，信号经 JLine 自己的处理器注册表分发。 */
    private static final class Rig implements AutoCloseable {
        private final PipedOutputStream keys;
        final TracingTerminal terminal;
        final CancellationRegistry cancellations = new CancellationRegistry();
        final RecordingRenderer renderer = new RecordingRenderer();

        Rig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("sigint-guard", "xterm-256color", in,
                    new ByteArrayOutputStream());
            terminal.setSize(new Size(100, 24));
            new SessionInterruptGuard(cancellations, renderer, null, null).installOn(terminal);
        }

        void typeByte(int b) throws Exception {
            keys.write(b);
            keys.flush();
        }

        @Override
        public void close() throws Exception {
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            terminal.close();
        }
    }

    /** issue 06 的 seam：管道流驱动的完整 REPL 装配，回合由桩 turnRunner 记录。 */
    private static final class ReplRig implements AutoCloseable {
        private final PipedOutputStream keys;
        private final TracingTerminal terminal;
        final AgentSession session;
        final CancellationRegistry cancellations = new CancellationRegistry();
        final RecordingRenderer renderer = new RecordingRenderer();
        final List<String> turns = new CopyOnWriteArrayList<>();
        final InteractiveRepl repl;

        ReplRig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("sigint-repl", "xterm-256color", in,
                    new ByteArrayOutputStream());
            terminal.setSize(new Size(100, 24));
            LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

            Agent agent = new Agent(new StubLlmClient(), new ToolRegistry());
            agent.setRenderer(renderer);
            session = new AgentSession(agent, new TaskRegistry(), output -> { });
            session.setTurnRunner(input -> {
                turns.add(input);
                return "turn-done";
            });
            session.start();

            repl = new InteractiveRepl(terminal, lineReader, renderer, session,
                    new ApprovalPump(),
                    new SessionInterruptGuard(cancellations, renderer, null, session),
                    (command, rawInput) -> InteractiveRepl.SlashCommandRouter.Outcome.HANDLED,
                    InteractiveRepl.PromptPreparation.Result::accepted);
        }

        void type(String text) throws Exception {
            keys.write(text.getBytes(StandardCharsets.UTF_8));
            keys.flush();
        }

        /** 回合期的键必须在 REPL 已经在读的时候才写进管道，否则会被行编辑器提前吞掉。 */
        Thread runInBackground() {
            Thread thread = new Thread(repl::run, "sigint-guard-repl");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        /** Ctrl+D：行编辑器抛 EndOfFileException，REPL 退出循环。 */
        void endOfFile() throws Exception {
            keys.write(4);
            keys.flush();
        }

        @Override
        public void close() throws Exception {
            session.stop();
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            terminal.close();
        }
    }

    /** 桩 turnRunner 顶替了 {@code Agent.run}，故 chat 永不被调用。 */
    private static final class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException("REPL seam never reaches the model");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            throw new UnsupportedOperationException("REPL seam never reaches the model");
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


    /** 记录调用的 Renderer 端口替身：guard 的可观察输出全部经这里。 */
    static class RecordingRenderer implements Renderer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        final List<String> calls = new CopyOnWriteArrayList<>();

        String text() {
            return out.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void start() {
        }

        @Override
        public void beginTurn() {
            calls.add("beginTurn");
        }

        @Override
        public void close() {
            calls.add("close");
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
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.approve();
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }
    }
}
