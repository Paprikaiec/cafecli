package com.cafecli.render.inline;

import com.cafecli.agent.Agent;
import com.cafecli.agent.AgentSession;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.cli.InteractiveRepl;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.StatusInfo;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.tool.ToolRegistry;

import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常驻输入 regime 的端到端断言（issue 07）：真 {@link CafeCliLineReader} + 真 {@link InlineRenderer}
 * + 真 {@link InteractiveRepl}，由管道流键入、由手动帧泵合成，断言落在**用户看得见的合成帧**上。
 *
 * <p>与 {@code InteractiveReplTest}（桩渲染器 + 原生行编辑器）互补：那边断言路由与事件，这边断言
 * 输入框本身——[[输入框]]在回合运行期间还在不在、右提示符有没有跟着状态走。
 */
class ResidentInputRegimeTest {

    /**
     * 右提示符是这条通路的**可发现性**（issue 07）：空闲 → 回合中 → 审批待决三态各自不同，用户不必
     * 读文档就知道现在敲什么有用。
     *
     * <p>三态都在**同一次** {@code readLine} 里被观察到：回合与审批都发生在别的线程上，右提示符必须
     * 跟着会话状态走，而不是跟着「上一次进入 readLine 时是什么状态」走。
     */
    @Test
    @Timeout(60)
    void theRightPromptFollowsIdleThenRunningThenApprovalPending() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();

            rig.awaitBoxContains("message / @path / @image");

            CountDownLatch release = new CountDownLatch(1);
            rig.turnRunner = input -> {
                rig.turns.add(input);
                await(release);
                return "turn-done";
            };
            rig.type("do the thing\n");
            waitUntil(() -> !rig.session.isIdle());
            rig.awaitBoxContains("steer");

            CompletableFuture<ApprovalResult> worker = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);
            rig.awaitBoxContains("y / n / a");

            rig.type("y\n");
            assertEquals(ApprovalResult.Decision.APPROVED, worker.get(10, TimeUnit.SECONDS).decision());
            release.countDown();
        }
    }

    /**
     * [[输入框]]在回合运行期间**还活着**：键入的字符出现在合成帧里，退格照常删掉它。
     *
     * <p>此前回合期由裸轮询器读键、输入行根本不存在，所以这一帧里既没有 `> `、也没有用户敲的字。
     */
    @Test
    @Timeout(60)
    void typingDuringATurnShowsUpInTheInputBoxAndCanBeEdited() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            rig.turnRunner = input -> {
                rig.turns.add(input);
                await(release);
                return "turn-done";
            };
            rig.type("start it\n");
            waitUntil(() -> !rig.session.isIdle());

            rig.type("correctionX");
            rig.awaitBoxContains("> correctionX");

            rig.type("\b");   // 退格：编辑在回合期间照常可用
            rig.awaitBox(frame -> frame.contains("> correction") && !frame.contains("correctionX"));

            release.countDown();
        }
    }

    /**
     * 后台[[任务]]完成的 push 输出落在[[输入框]]上方，**不打断正在编辑的内容**。
     *
     * <p>常驻读取器把这件事从「回合之间才可能发生」变成「随时」：用户可能正打到一半，一个后台任务
     * 完成、唤醒执行器跑一个自治回合、把输出提交进[[冻结回卷]]。草稿必须原样待在框里——一个字不多、
     * 一个字不少，也不会被复制一份。
     */
    @Test
    @Timeout(60)
    void aBackgroundPushLandsAboveTheBoxWithoutDisturbingWhatIsBeingTyped() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            rig.turnRunner = input -> {
                var answer = rig.renderer.beginAnswer();
                answer.append("background task finished while you were typing\n");
                answer.finish();
                return "turn-done";
            };

            rig.type("half a thought");
            rig.awaitBoxContains("> half a thought");
            rig.sink.reset();

            rig.session.onBackgroundTaskDone("task_1", "the crawl finished");
            waitUntil(rig.session::isIdle);
            rig.awaitFrameContains("background task finished while you were typing");

            String box = rig.box();
            assertTrue(box.startsWith("> half a thought"),
                    "后台 push 不得动到正在编辑的草稿：[" + box + "]");
            assertEquals(1, box.split("half a thought", -1).length - 1,
                    "草稿也不得被复制一份：[" + box + "]");

            // 而且框还能继续编辑。
            rig.type(" continued");
            rig.awaitBoxContains("> half a thought continued");
        }
    }

    /**
     * ESC 的**触发延迟不得高于今天**（ADR 0016 D2）。
     *
     * <p>被删掉的裸轮询器自己读到字节 27、取一次 80/20/120ms 突发再分类，所以取消在 ≤ ~120ms 内触发。
     * 作为 JLine 键绑定，ESC 是每个方向键 / CSI 序列的**前缀**，键映射要等 {@code ambiguous-binding}
     * 才触发较短的那个匹配，而默认是 1000ms——「停下这次输出」会变成整整一秒。
     *
     * <p>断言的是**这个值确实生效**，不是「setter 被调用过」：同一个装置跑两遍，一遍带 100ms 窗口、
     * 一遍不带（回落到 JLine 的 1000ms 默认），比对实际测到的取消延迟。控制组的存在正是「effective
     * 而非 set」的全部意义——只测一遍的话，任何一个恰好很快的实现都能过。
     */
    @Test
    @Timeout(90)
    void escCancelsWithinTheWindowWeConfigureAndTheDefaultWindowIsMeasurablySlower() throws Exception {
        long configured = measureEscCancelLatency(100L);
        long jlineDefault = measureEscCancelLatency(null);

        assertTrue(configured < 400,
                "ESC 取消延迟必须停在今天的量级（~120ms），实测 " + configured + "ms");
        assertTrue(jlineDefault > 700,
                "对照组：不设 ambiguous-binding 时 JLine 等的是 1000ms 默认，实测 "
                        + jlineDefault + "ms —— 若这里也很快，说明快不是我们配的那个值带来的");
    }

    /**
     * 同一个窗口的另一半：ESC 是方向键的前缀，所以**方向键不得被读成取消**。
     *
     * <p>这是手写分类器唯一在做的事，删掉它之后必须由同一个窗口继续做。
     */
    @Test
    @Timeout(60)
    void anArrowKeyIsNotReadAsACancel() throws Exception {
        try (Rig rig = new Rig(100L)) {
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("run it\n");
            waitUntil(() -> token[0] != null);

            rig.type("\u001b[A");   // 上方向键：ESC + CSI，一次写入
            Thread.sleep(600);      // 远超 100ms 窗口

            assertFalse(token[0].isCancelled(), "方向键不得被读成取消");
            release.countDown();
        }
    }

    /**
     * 层内 Ctrl+C 照常停回合、**且不关层**（ADR 0016 D3），在 reader 常驻之后仍然成立。
     *
     * <p>这条是 issue 12 交付的行为，而 issue 07 会把它的机制抽掉：D3 的会话级兜底只在 {@code readLine}
     * 的**间隙**里生效，而常驻之后[[转写浏览层]]永远跑在 {@code readLine} 里的一个 widget 中。issue 12
     * 实测过这一半（{@code SessionInterruptGuardTest#aWidgetBlockedOnItsOwnRead...}）：JLine 的 INT
     * handler 中断**读线程**，阻塞中的 {@code read()} 抛 {@code InterruptedIOException}，widget 吃掉它
     * 之后 {@code readLine} 继续读，会话级 handler 一个字节都没看到。所以取消必须由浏览层自己认下这个
     * 中断——本用例就是那条认领。
     *
     * <p>「层不关」在这里有可观察的形状：只有用户自己按的 {@code q} 能让终端离开 alternate screen。
     */
    @Test
    @Timeout(60)
    void ctrlCInsideTheOverlayStopsTheTurnAndLeavesTheOverlayOpen() throws Exception {
        try (Rig rig = new Rig()) {
            rig.bindCtrlOToOverlay();
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("show me what you are doing\n");
            waitUntil(() -> token[0] != null);

            rig.type("\u000f");                 // Ctrl+O：浏览层作为 widget 打开
            rig.awaitOverlayOpen();

            rig.terminal.raise(org.jline.terminal.Terminal.Signal.INT);
            waitUntil(() -> token[0].isCancelled());

            assertTrue(token[0].isCancelled(), "层内 Ctrl+C 必须停回合");
            assertTrue(rig.overlayIsOpen(),
                    "取消不关层：终端仍应停在 alternate screen 上");

            rig.type("q");                          // 层只应被用户自己按的键关掉
            waitUntil(() -> !rig.overlayIsOpen());
            assertFalse(rig.overlayIsOpen(), "q 之后层才关");
            release.countDown();
        }
    }

    /**
     * 「回合停下来了」这件事**在层里就看得见**（issue 08 验收条第 2 条）。
     *
     * <p>这是「取消不关层」能成立的前提：如果反馈只出现在主屏上，那么读者要么被迫关层去确认，要么
     * 不知道自己那一下有没有生效。它成立靠的是两条已经在的机制叠起来——反馈经 {@code Renderer.stream()}
     * 进有序提交日志（登记发生在渲染事件里，不受[[模态租借]]的闸约束），而浏览层每 50ms 重读那份
     * 日志（A13 的实时跟随），且开层落在末尾。
     *
     * <p>判据是**用户在 alternate screen 上看到的文字**，不是写了哪些字节：取最后一次进入 alternate
     * screen 之后写出的内容，剥掉 ANSI，问那句反馈在不在里面。
     */
    @Test
    @Timeout(60)
    void theCancellationIsVisibleInsideTheOverlayWithoutClosingIt() throws Exception {
        try (Rig rig = new Rig()) {
            rig.bindCtrlOToOverlay();
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("show me what you are doing\n");
            waitUntil(() -> token[0] != null);

            rig.type("\u000f");   // Ctrl+O：浏览层作为 widget 打开
            rig.awaitOverlayOpen();

            rig.terminal.raise(org.jline.terminal.Terminal.Signal.INT);
            waitUntil(() -> token[0].isCancelled());

            rig.awaitInsideTheOverlay("已请求取消");
            assertTrue(rig.overlayIsOpen(),
                    "反馈是在层里被看到的——层不该为了让它可见而关掉");

            rig.type("q");
            waitUntil(() -> !rig.overlayIsOpen());
            release.countDown();
        }
    }

    /**
     * 层内 ESC **只关层**，不取消回合（ADR 0010 修订 A13 不变）。
     *
     * <p>两键在这一格分岔，理由是**送达方式**而不是约定（ADR 0016 D3）：ESC 是一个字节，得由此刻
     * 占着字节流的人读到，而[[模态租借]]期间那个人是浏览层自己，取消通路根本看不到它。Ctrl+C 是
     * 信号，不经字节流，所以它在任何地方都保持同一个含义。
     */
    @Test
    @Timeout(60)
    void escInsideTheOverlayOnlyClosesTheLayerAndTheTurnKeepsRunning() throws Exception {
        try (Rig rig = new Rig()) {
            rig.bindCtrlOToOverlay();
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("keep going\n");
            waitUntil(() -> token[0] != null);

            rig.type("\u000f");   // Ctrl+O：浏览层作为 widget 打开
            rig.awaitOverlayOpen();

            rig.type("\u001b");   // 层内 ESC
            waitUntil(() -> !rig.overlayIsOpen());
            assertFalse(rig.overlayIsOpen(), "层内 ESC 关层");

            Thread.sleep(500);   // 远超 100ms 的 ambiguous-binding 窗口
            assertFalse(token[0].isCancelled(),
                    "那个字节被浏览层读走了，取消通路看不到它——回合照常在跑");
            release.countDown();
        }
    }

    /** 模态开着时 Ctrl+D 无操作：它既不关层，也不退出会话。 */
    @Test
    @Timeout(60)
    void ctrlDInsideTheOverlayDoesNothing() throws Exception {
        try (Rig rig = new Rig()) {
            rig.bindCtrlOToOverlay();
            rig.start();
            rig.type("one\n");
            waitUntil(rig.session::isIdle);

            rig.type("\u000f");   // Ctrl+O：浏览层作为 widget 打开
            rig.awaitOverlayOpen();

            rig.type("\u0004");   // Ctrl+D
            Thread.sleep(500);
            assertTrue(rig.overlayIsOpen(), "Ctrl+D 不关层");

            rig.type("q");
            waitUntil(() -> !rig.overlayIsOpen());
            // 会话还在服务：层关掉之后照常收下一次提交。
            rig.type("two\n");
            waitUntil(() -> rig.turns.contains("two"));
            assertTrue(rig.turns.contains("two"), "Ctrl+D 也没有把会话退掉：" + rig.turns);
        }
    }

    /**
     * 滚轮在[[转写浏览层]]里仍然可用——鼠标追踪的时序陷阱（PRD 实现决策）。
     *
     * <p>{@code LineReaderImpl.doCleanup} 每次都无条件发 49 字符的 mouse-disable，即便 {@code Option.MOUSE}
     * 关着且无 override 钩子；而浏览层是唯一主动**开启**鼠标追踪的地方。常驻读取器让每次提交都重入读取，
     * 该序列的发射频次显著上升，一旦与浏览层的开启交错，滚轮就被静默关掉。
     *
     * <p>断言的是终端此刻的**模式**（用户滚轮能不能用），不是写了哪些字节：浏览层正阻塞在读键上时，
     * 最后一次鼠标模式切换必须是「开」。
     */
    @Test
    @Timeout(60)
    void theWheelIsStillEnabledWhileTheOverlayIsReading() throws Exception {
        try (Rig rig = new Rig()) {
            rig.bindCtrlOToOverlay();
            rig.start();
            // 先提交几次，让行编辑器反复重入读取——这正是 mouse-disable 被高频发出的场景。
            rig.type("one\ntwo\nthree\n");
            waitUntil(rig.session::isIdle);

            rig.type("\u000f");
            rig.awaitOverlayOpen();

            assertTrue(rig.mouseTrackingEnabled(),
                    "浏览层读键时鼠标追踪必须是开的，否则滚轮被静默关掉：\n" + rig.rawSink());

            rig.type("q");
            waitUntil(() -> !rig.overlayIsOpen());
        }
    }

    /**
     * 审批待决时按 Ctrl+C：**停回合，并且拒掉那条待决的[[审批应答]]**。
     *
     * <p>这条用例被 issue 08 的[[取消键]]表**反转**了。它此前断言的是 issue 12 交付的语义（「停回合，
     * 但不替用户回答审批」），而表里 Ctrl+C 在「回合运行中」那一格写的是「同上」——与 ESC 同一个取消
     * 范围，唯一的差别是框内文字保留。理由不是对称好看：一个 park 在审批 future 上的 worker 不会因为
     * 令牌被取消而醒过来，所以不拒掉它，「回合停下来了」这句话就落不到实处，而屏幕上还立着一张卡片
     * 在等 y/n。两键之间没有第二套范围可讲。
     *
     * <p>请求方拿到的是**拒绝**，不是超时也不是批准：取消从不代表同意。
     */
    @Test
    @Timeout(60)
    void ctrlCWhileAnApprovalIsWaitingStopsTheTurnAndRejectsTheApproval() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("write the file\n");
            waitUntil(() -> token[0] != null);
            CompletableFuture<ApprovalResult> worker = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);

            rig.terminal.raise(org.jline.terminal.Terminal.Signal.INT);
            waitUntil(() -> token[0].isCancelled());

            assertTrue(token[0].isCancelled(), "审批待决时的 Ctrl+C 也要停回合");
            assertEquals(ApprovalResult.Decision.REJECTED,
                    worker.get(10, TimeUnit.SECONDS).decision(),
                    "park 在审批上的 worker 必须被放掉，否则回合停不下来");
            assertFalse(rig.approvalPump.hasPending(), "队列里不该还留着这条审批");
            release.countDown();
        }
    }

    private static long measureEscCancelLatency(Long ambiguousBindingMillis) throws Exception {
        try (Rig rig = new Rig(ambiguousBindingMillis)) {
            rig.start();
            CountDownLatch release = new CountDownLatch(1);
            com.cafecli.runtime.CancellationToken[] token = new com.cafecli.runtime.CancellationToken[1];
            rig.turnRunner = input -> {
                token[0] = rig.cancellations.beginForegroundTurn();
                await(release);
                return "turn-done";
            };
            rig.type("run it\n");
            waitUntil(() -> token[0] != null);

            long start = System.nanoTime();
            rig.type("\u001b");
            waitUntil(() -> token[0].isCancelled());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(token[0].isCancelled(), "ESC 必须最终取消回合");
            release.countDown();
            return elapsedMillis;
        }
    }

    // --- rig -----------------------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    static final class Rig implements AutoCloseable {
        final TracingTerminal terminal;
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final ManualFramePump pump = new ManualFramePump();
        final InlineRenderer renderer;
        final CafeCliLineReader reader;
        final AgentSession session;
        final ApprovalPump approvalPump = new ApprovalPump();
        final CancellationRegistry cancellations = new CancellationRegistry();
        final InteractiveRepl repl;
        final List<String> turns = new CopyOnWriteArrayList<>();
        volatile java.util.function.Function<String, String> turnRunner = input -> {
            turns.add(input);
            return "turn-done";
        };
        private final PipedOutputStream keys;
        private Thread replThread;

        Rig() throws Exception {
            this(100L);
        }

        Rig(Long ambiguousBindingMillis) throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("resident", "xterm-256color", in, sink);
            terminal.setSize(new Size(100, 20));
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                    new ManualClock());
            Map<String, Object> vars = new HashMap<>();
            vars.put(LineReader.INPUT_RC_FILE_NAME, "/nonexistent-cafecli-inputrc");
            if (ambiguousBindingMillis != null) {
                vars.put(LineReader.AMBIGUOUS_BINDING, ambiguousBindingMillis);
            }
            reader = CafeCliLineReader.create(terminal, "cafecli", vars, null, null, null);
            renderer.bindLineReader(reader);
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));

            session = new AgentSession(new Agent(new StubLlmClient(), new ToolRegistry()),
                    new TaskRegistry(), output -> { });
            session.setTurnRunner(input -> turnRunner.apply(input));
            session.start();

            // 与 Main 的装配同形：会话级中断既装在信号上（readLine 之间的间隙），也交给渲染器
            // （模态自持读取时由模态自己认领，ADR 0016 D3）。
            com.cafecli.cli.SessionInterruptGuard guard =
                    new com.cafecli.cli.SessionInterruptGuard(cancellations, renderer,
                            approvalPump, session);
            guard.installOn(terminal);
            renderer.bindSessionInterrupt(guard::interrupt);

            repl = new InteractiveRepl(terminal, reader, renderer, session, approvalPump,
                    guard,
                    (command, rawInput) -> InteractiveRepl.SlashCommandRouter.Outcome.HANDLED,
                    InteractiveRepl.PromptPreparation.Result::accepted);
            approvalPump.setPendingNotifier(repl::onApprovalPending);
        }

        void start() throws Exception {
            replThread = new Thread(repl::run, "resident-repl");
            replThread.setDaemon(true);
            replThread.start();
            waitUntil(reader::isReading);
        }

        /** 与 {@code Main.bindCtrlOToTranscript} 同形：Ctrl+O 是常驻读取器上的一个普通 widget。 */
        void bindCtrlOToOverlay() {
            reader.getWidgets().put("cafecli-transcript-overlay", () -> {
                renderer.openTranscript();
                reader.callWidget(LineReader.REDISPLAY);
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new org.jline.reader.Reference("cafecli-transcript-overlay"),
                            org.jline.keymap.KeyMap.ctrl('O'));
        }

        String rawSink() {
            return sink.toString(StandardCharsets.UTF_8);
        }

        /** 终端此刻是不是停在 alternate screen 上——也就是浏览层还开着没有。 */
        boolean overlayIsOpen() {
            String raw = rawSink();
            return raw.lastIndexOf("\u001b[?1049h") > raw.lastIndexOf("\u001b[?1049l");
        }

        /** 终端此刻的鼠标上报模式：最后一次切换是开还是关。 */
        boolean mouseTrackingEnabled() {
            String raw = rawSink();
            return raw.lastIndexOf("\u001b[?1000h") > raw.lastIndexOf("\u001b[?1000l");
        }

        /** 用户此刻在 alternate screen 上看到的文字（最后一次进层之后写出的内容，剥掉 ANSI）。 */
        String insideTheOverlay() {
            String raw = rawSink();
            int entered = raw.lastIndexOf("\u001b[?1049h");
            return entered < 0 ? "" : AttributedString.stripAnsi(raw.substring(entered));
        }

        /** 层里的内容是被 50ms 轮询重画上去的，而帧由手动泵驱动——两边都要推一下。 */
        void awaitInsideTheOverlay(String expected) throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                pump.drain();
                if (insideTheOverlay().contains(expected)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("overlay never showed " + expected + "; inside the overlay:\n"
                    + insideTheOverlay());
        }

        void awaitOverlayOpen() throws Exception {
            waitUntil(this::overlayIsOpen);
            if (!overlayIsOpen()) {
                throw new AssertionError("overlay never opened; sink:\n" + rawSink());
            }
        }

        void type(String text) throws Exception {
            keys.write(text.getBytes(StandardCharsets.UTF_8));
            keys.flush();
        }

        CompletableFuture<ApprovalResult> requestApproval(String tool) {
            ApprovalRequest request = new ApprovalRequest(tool, "{}", "high",
                    "writes to disk", null, "main Agent", null);
            return CompletableFuture.supplyAsync(() -> approvalPump.requestApproval(request));
        }

        /**
         * [[输入框]]这一刻的内容——行编辑器排好、交给渲染循环的那一帧本身。
         *
         * <p>读这里而不是读终端字节，是因为活区的 {@code Display} 只写增量：同一片内容第二次合成
         * 时字节为零，而框里写着什么与它这一帧有没有被重写无关。它进合成帧的位置由
         * {@code InputBoxLiveRegionTest} 钉住。
         */
        String box() {
            return String.join("\n", reader.capture().lastFrame().plain());
        }

        /** 累积地等某段文字被写上屏——活区的 {@code Display} 只写增量，所以不能中途清空 sink。 */
        void awaitFrameContains(String expected) throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                pump.drain();
                if (AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8))
                        .contains(expected)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("never wrote " + expected + "; sink:\n"
                    + AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8)));
        }

        void awaitBoxContains(String expected) throws Exception {
            awaitBox(box -> box.contains(expected), expected);
        }

        void awaitBox(java.util.function.Predicate<String> matcher) throws Exception {
            awaitBox(matcher, "the expected input box");
        }

        private void awaitBox(java.util.function.Predicate<String> matcher, String what)
                throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            String last = "";
            while (System.currentTimeMillis() < deadline) {
                pump.drain();
                last = box();
                if (matcher.test(last)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("input box never showed " + what + "; last box:\n[" + last + "]");
        }

        @Override
        public void close() throws Exception {
            session.stop();
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            if (replThread != null) {
                replThread.interrupt();
            }
            renderer.close();
            terminal.close();
        }
    }

    private static final class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException("never reaches the model");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            throw new UnsupportedOperationException("never reaches the model");
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
}
