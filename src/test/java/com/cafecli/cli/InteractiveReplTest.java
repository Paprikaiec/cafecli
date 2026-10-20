package com.cafecli.cli;

import com.cafecli.agent.Agent;
import com.cafecli.agent.AgentSession;
import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.inline.TracingTerminal;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.runtime.CancellationToken;
import com.cafecli.tool.ToolRegistry;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 06 的特征测试 seam：交互 REPL 从 CLI 入口抽出后，可在**无真实 TTY** 的环境下由管道流驱动。
 *
 * <p>这些断言钉住的是**抽取之前既有**的行为，不是新行为——06 是零行为变化的纯重构，它存在的唯一
 * 目的是让 07 的 regime 切换发生时就能被断言，而不是事后补测。
 */
class InteractiveReplTest {

    @Test
    void aPlainMessageIsPostedToTheEventLoopAsAUserSubmit() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            rig.type("hello\n");
            rig.awaitTurnsRecorded(1);
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertEquals(List.of("hello"), rig.turns);
        }
    }

    /**
     * 手动换行：组合键在框里插一个换行，Enter 才提交（真机手工验收 2026-08-28 报的第二个缺陷）。
     *
     * <p>此前一个都没绑，于是那串转义序列落到 {@code self-insert}，直接把字符打进缓冲区。判据因此是
     * <b>提交出来的那一句</b>：一次提交、中间有换行，而不是「按键被吃掉了」或「提交了两次」。
     */
    @Test
    void shiftEnterAndOptionEnterInsertANewlineInsteadOfSubmitting() throws Exception {
        // ESC CR（Option+Enter）/ CSI 13;2u（fixterms Shift+Enter）/ CSI 27;2;13~（modifyOtherKeys）。
        for (String newline : List.of("\033\r", "\033[13;2u", "\033[27;2;13~")) {
            try (Rig rig = new Rig()) {
                Thread repl = rig.runInBackground();
                rig.type("first" + newline + "second\n");
                rig.awaitTurnsRecorded(1);
                rig.endOfFile();
                rig.awaitReplReturn(repl);

                assertEquals(List.of("first\nsecond"), rig.turns,
                        "「" + newline.replace("\033", "ESC") + "」应插入换行而不是提交");
            }
        }
    }

    @Test
    void plainEnterStillSubmits() throws Exception {
        // 上一条的对照组：换行绑定不能把 Enter 自己变成插入换行，否则没人能提交了。
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            rig.type("first\nsecond\n");
            rig.awaitTurnsRecorded(2);
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertEquals(List.of("first", "second"), rig.turns);
        }
    }

    @Test
    void theSubmittedInputIsWrittenBackToTheTranscriptBeforeTheTurnRuns() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            rig.type("review the diff\n");
            rig.awaitTurnsRecorded(1);
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertTrue(rig.renderer.text().contains("review the diff"),
                    "提交后原文应作为转写块回写：" + rig.renderer.text());
            assertEquals(List.of(Boolean.TRUE), rig.transcriptWrittenWhenTurnRan,
                    "转写块必须先于回合开跑出现，否则用户按下回车后十几秒看不到任何反应");
            assertTrue(rig.renderer.calls.contains("beginTurn"),
                    "回写前应开启一次新回合：" + rig.renderer.calls);
        }
    }

    /**
     * [[审批应答]]经[[输入框]]提交（issue 07）：{@code y} / {@code n} / {@code a} + Enter 与普通提交
     * 走同一条通路，审批泵的 park/resolve 契约一字不改——worker 仍然 park 在 future 上，仍然由这条
     * 线程 resolve。
     *
     * <p>此前这条通路的形状是「回车 → 泵 drain → 提示器抢 raw mode 单键读取」，与常驻行编辑器抢同一个
     * 底层读取器；那个提示器随本 issue 退役。
     */
    @Test
    @Timeout(30)
    void yesNoAndAllTypedIntoTheBoxBecomeApproveRejectAndApproveAll() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();

            // 一次一条：审批是串行的（泵一次只呈现一张卡），而且并发入队的顺序本来就不确定，
            // 所以断言「哪条得到哪个决定」必须逐条驱动，不能靠三个 worker 的入队竞速。
            CompletableFuture<ApprovalResult> first = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);
            rig.type("y\n");
            assertEquals(ApprovalResult.Decision.APPROVED, first.get(10, TimeUnit.SECONDS).decision());

            CompletableFuture<ApprovalResult> second = rig.requestApproval("execute_command");
            waitUntil(rig.approvalPump::hasPending);
            rig.type("n\n");
            assertEquals(ApprovalResult.Decision.REJECTED, second.get(10, TimeUnit.SECONDS).decision());

            CompletableFuture<ApprovalResult> third = rig.requestApproval("edit_file");
            waitUntil(rig.approvalPump::hasPending);
            rig.type("a\n");
            assertEquals(ApprovalResult.Decision.APPROVED_ALL, third.get(10, TimeUnit.SECONDS).decision());

            assertEquals(List.of(), rig.turns, "审批应答不得落到事件循环上变成一个回合");

            rig.endOfFile();
            rig.awaitReplReturn(repl);
        }
    }

    /**
     * 空提交表示「再问一次」（issue 07 / PRD user story 17）：重新呈现审批卡而**不产生决定**。
     *
     * <p>这条以前是反过来的——空回车 drain 掉审批并当成批准。误触回车既不能被读成批准也不能被读成
     * 拒绝：请求方仍然 park 着，卡片再问一次，用户随后给出的答案才是决定。
     */
    @Test
    @Timeout(30)
    void anEmptySubmissionWhileAnApprovalIsPendingAsksAgainWithoutDeciding() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            CompletableFuture<ApprovalResult> worker = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);

            rig.type("\n");
            waitUntil(() -> rig.renderer.approvalsShown.size() >= 2);
            assertEquals(2, rig.renderer.approvalsShown.size(),
                    "空提交必须把同一张卡片再问一次");
            assertFalse(worker.isDone(), "空提交不得产生决定——既不是批准也不是拒绝");

            rig.type("n\n");
            assertEquals(ApprovalResult.Decision.REJECTED, worker.get(10, TimeUnit.SECONDS).decision());
            assertEquals(List.of(), rig.turns, "空提交不得开启回合");

            rig.endOfFile();
            rig.awaitReplReturn(repl);
        }
    }

    /**
     * 后台审批走的是同一条通路（前台 / 后台不再分叉），而请求方归属标注（{@code callerContext}）
     * 原样出现在被呈现的卡片上。
     */
    @Test
    @Timeout(30)
    void aBackgroundApprovalIsAnsweredThroughTheSameBoxAndKeepsItsCallerAttribution() throws Exception {
        try (Rig rig = new Rig()) {
            ApprovalRequest request = new ApprovalRequest("write_file", "{}", "high",
                    "writes to disk", null, "task_7", null);
            CompletableFuture<ApprovalResult> worker =
                    CompletableFuture.supplyAsync(() -> rig.approvalPump.requestApproval(request));
            Thread repl = rig.runInBackground();
            waitUntil(rig.approvalPump::hasPending);

            rig.type("y\n");
            assertEquals(ApprovalResult.Decision.APPROVED, worker.get(10, TimeUnit.SECONDS).decision());
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertEquals(List.of("task_7"), rig.renderer.approvalsShown.stream()
                            .map(ApprovalRequest::callerContext).distinct().toList(),
                    "卡片必须带上请求方归属：" + rig.renderer.approvalsShown);
        }
    }

    @Test
    void aSlashCommandGetsItsTranscriptBlockAndNeverReachesTheEventLoop() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            rig.type("/clear\n");
            rig.awaitRouted(1);
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertEquals(List.of(CliCommandParser.CommandType.CLEAR),
                    rig.routed.stream().map(CliCommandParser.ParsedCommand::type).toList());
            assertEquals(List.of(), rig.turns, "斜杠命令不得落到 Agent");
            assertTrue(rig.renderer.text().contains("/clear"),
                    "斜杠命令也要回写转写块：" + rig.renderer.text());
        }
    }

    @Test
    void anUnknownCommandIsRoutedAndNeverFallsBackToTheAgent() throws Exception {
        try (Rig rig = new Rig()) {
            Thread repl = rig.runInBackground();
            rig.type("/nope\n");
            rig.awaitRouted(1);
            rig.endOfFile();
            rig.awaitReplReturn(repl);

            assertEquals(List.of(CliCommandParser.CommandType.UNKNOWN_COMMAND),
                    rig.routed.stream().map(CliCommandParser.ParsedCommand::type).toList());
            assertEquals(List.of(), rig.turns, "未知命令在 CLI 层报错，不回退给 Agent");
        }
    }

    @Test
    void anExitOutcomeEndsTheLoopAndClosesTheRenderer() throws Exception {
        try (Rig rig = new Rig()) {
            rig.routerOutcome = InteractiveRepl.SlashCommandRouter.Outcome.EXIT;
            Thread repl = rig.runInBackground();
            rig.type("/exit\n");
            // 没有 EOF：循环必须靠 EXIT 自己结束——这正是 awaitReplReturn 在断言的那件事。
            rig.awaitReplReturn(repl);

            assertTrue(rig.renderer.calls.contains("close"),
                    "退出应关闭渲染器：" + rig.renderer.calls);
        }
    }

    @Test
    @Timeout(30)
    void escDuringAForegroundTurnCancelsIt() throws Exception {
        try (Rig rig = new Rig()) {
            // 回合跑起来后 park 在自己的取消令牌上——取消是协作式的，回合自己在边界停。
            rig.session.setTurnRunner(input -> {
                CancellationToken token = rig.cancellationRegistry.beginForegroundTurn();
                try {
                    long deadline = System.currentTimeMillis() + 5_000;
                    while (!token.isCancelled() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                    rig.turns.add(token.isCancelled() ? "CANCELLED" : "RAN_TO_COMPLETION");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    rig.cancellationRegistry.endForegroundTurn(token);
                }
                return "turn-done";
            });

            Thread repl = rig.runInBackground();
            rig.type("long running\n");
            waitUntil(() -> !rig.session.isIdle());
            rig.typeByte(27);   // 孤立的 ESC
            waitUntil(() -> !rig.turns.isEmpty());

            assertEquals(List.of("CANCELLED"), rig.turns);

            rig.endOfFile();
            rig.awaitReplReturn(repl);
        }
    }

    /**
     * 输入 regime 的核心承诺（issue 07）加上它的提交语义（issue 10）：**回合运行期间键入的字符不再
     * 被吞掉，而且它注入的是<b>正在跑的那个回合</b>，不是排在它后面**。
     *
     * <p>此前回合期由裸 raw-mode 轮询器读键，它只认 ESC / Ctrl+O / Ctrl+Y，其余字节 drain 丢弃——
     * 用户看着模型跑偏、想立刻纠正，敲进去的每一个字都石沉大海。issue 07 让它不再丢；本断言由
     * issue 10 **改写**：原文断言的是「照常入列成第二个回合」，那正是常驻输入框要取代的行为
     * （「眼睁睁看完一个已知是错的回合」）。这里的判据因此换成两条可观察的事实——提交的那一刻
     * **没有开启第二个回合**（[[转向消息]]不是回合），且转写里立刻出现回显。
     *
     * <p>末尾那半条是**回合结束兜底**：桩 turnRunner 站在 ReAct 循环的位置上但不 drain 那个槽，
     * 于是这条转向消息谁也没取走——它必须重新变成一次普通提交，而不是静默消失。
     */
    @Test
    @Timeout(30)
    void aSubmissionDuringARunningTurnSteersItInsteadOfQueueingBehindIt() throws Exception {
        try (Rig rig = new Rig()) {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            rig.session.setTurnRunner(input -> {
                rig.turns.add(input);
                if (input.startsWith("long")) {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "turn-done";
            });

            Thread repl = rig.runInBackground();
            rig.type("long running\n");
            waitUntil(() -> rig.turns.contains("long running"));
            assertEquals(1, rig.renderer.beginTurns(), "第一次提交开启一个回合");

            rig.type("steer me\n");
            waitUntil(() -> rig.renderer.text().contains("steer me"));

            assertEquals(List.of("long running"), rig.turns,
                    "回合运行期间的提交必须注入正在跑的回合，不得排成第二个回合");
            assertEquals(1, rig.renderer.beginTurns(),
                    "[[转向消息]]不开启新回合——它属于正在跑的那一个：" + rig.renderer.calls);

            release.countDown();
            waitUntil(() -> rig.turns.size() > 1);
            assertEquals(List.of("long running", "steer me"), rig.turns,
                    "没赶上迭代边界的转向消息必须被重新投递为普通提交，不得静默消失");

            rig.endOfFile();
            rig.awaitReplReturn(repl);
        }
    }

    /**
     * 回合中的 Ctrl+Y 仍然切 HITL（ADR 0016 D2）。
     *
     * <p>它今天的唯一通路是被本 issue 删掉的等待泵里的显示类控制处理；删掉 regime 不等于删掉骑在它上面
     * 的行为。常驻读取器之后，空闲与回合中合并成同一条通路——Ctrl+Y 只是 {@code Main.bindCtrlYToHitlToggle}
     * 装上去的一个普通 widget，而 widget 在回合运行期间照常被触发，因为读取器就在读。
     */
    @Test
    @Timeout(30)
    void ctrlYStillTogglesHitlWhileATurnIsRunning() throws Exception {
        try (Rig rig = new Rig()) {
            java.util.concurrent.atomic.AtomicInteger toggles =
                    new java.util.concurrent.atomic.AtomicInteger();
            Main.bindCtrlYToHitlToggle(rig.lineReader, toggles::incrementAndGet);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            rig.session.setTurnRunner(input -> {
                rig.turns.add(input);
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "turn-done";
            });

            Thread repl = rig.runInBackground();
            rig.type("keep going\n");
            waitUntil(() -> !rig.session.isIdle());

            rig.typeByte(25);   // Ctrl+Y
            waitUntil(() -> toggles.get() > 0);

            assertEquals(1, toggles.get(), "回合运行期间 Ctrl+Y 必须仍然切 HITL");

            release.countDown();
            rig.endOfFile();
            rig.awaitReplReturn(repl);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    // --- rig -------------------------------------------------------------------------------------

    /** 管道流驱动的 REPL 装配：没有真实 TTY，没有真实模型，回合由桩 turnRunner 记录。 */
    private static final class Rig implements AutoCloseable {
        private final PipedOutputStream keys;
        private final TracingTerminal terminal;
        final AgentSession session;
        final CancellationRegistry cancellationRegistry = new CancellationRegistry();
        final ApprovalPump approvalPump = new ApprovalPump();
        final RecordingRenderer renderer = new RecordingRenderer();
        final List<String> turns = new CopyOnWriteArrayList<>();
        final List<Boolean> transcriptWrittenWhenTurnRan = new CopyOnWriteArrayList<>();
        final List<CliCommandParser.ParsedCommand> routed = new CopyOnWriteArrayList<>();
        LineReader lineReader;
        volatile InteractiveRepl.SlashCommandRouter.Outcome routerOutcome =
                InteractiveRepl.SlashCommandRouter.Outcome.HANDLED;
        final SessionInterruptGuard interrupts;
        final InteractiveRepl repl;

        Rig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            terminal = new TracingTerminal("interactive-repl", "xterm-256color", in, sink);
            terminal.setSize(new Size(100, 24));
            lineReader = LineReaderBuilder.builder().terminal(terminal).build();

            ToolRegistry toolRegistry = new ToolRegistry();
            Agent agent = new Agent(new StubLlmClient(), toolRegistry);
            agent.setRenderer(renderer);
            session = new AgentSession(agent, new TaskRegistry(), output -> { });
            session.setTurnRunner(input -> {
                turns.add(input);
                transcriptWrittenWhenTurnRan.add(renderer.text().contains(input));
                return "turn-done";
            });
            session.start();

            interrupts = new SessionInterruptGuard(cancellationRegistry, renderer, approvalPump, session);
            repl = new InteractiveRepl(
                    terminal,
                    lineReader,
                    renderer,
                    session,
                    approvalPump,
                    interrupts,
                    (command, rawInput) -> {
                        routed.add(command);
                        return routerOutcome;
                    },
                    InteractiveRepl.PromptPreparation.Result::accepted);
            // 入队通知是[[审批应答]]的接缝：卡片在这一刻被呈现，右提示符也在这一刻切到 y / n / a。
            approvalPump.setPendingNotifier(repl::onApprovalPending);
        }

        /** 一个 worker park 在审批上（与生产路径同形：HitlToolRegistry 调 requestApproval 后阻塞）。 */
        CompletableFuture<ApprovalResult> requestApproval(String tool) {
            ApprovalRequest request = new ApprovalRequest(tool, "{}", "high",
                    "writes to disk", null, "main Agent", null);
            return CompletableFuture.supplyAsync(() -> approvalPump.requestApproval(request));
        }

        void type(String text) throws Exception {
            keys.write(text.getBytes(StandardCharsets.UTF_8));
            keys.flush();
        }

        void typeByte(int b) throws Exception {
            keys.write(b);
            keys.flush();
        }

        /**
         * REPL 跑在后台线程上——**本类每一条都走这里，一条都不在测试线程上同步调 {@code repl.run()}**。
         *
         * <p>两个理由，第二个是 issue 03 的教训：
         * <ul>
         *   <li>回合期的键（ESC 等）必须在 REPL 已经在读的时候才写进管道，否则会被行编辑器提前吞掉；</li>
         *   <li><b>「我敲进去的东西已经被处理完了」只能由处理结果的可观察量来判定</b>（{@link #awaitTurnsRecorded}
         *       / {@link #awaitRouted}），而那要求 REPL 与测试并发跑。此前那种「先把按键全塞进管道、
         *       再同步跑 REPL」的形状把这件事**假设**成了字节顺序，于是 Ctrl+D 到底撞上运行中的回合
         *       还是撞上空闲，取决于一场亚毫秒级的竞态；撞上前者就是永久挂死（见 {@link #endOfFile()}）。</li>
         * </ul>
         *
         * <p>顺带的结构性收益：{@code run()} 不返回时本类**不会挂死测试线程**，
         * {@link #awaitReplReturn} 会在 5s 内点名失败。
         */
        Thread runInBackground() {
            Thread thread = new Thread(repl::run, "interactive-repl-test");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        /**
         * 桩 turnRunner 已经**记下**了 {@code n} 条输入。
         *
         * <p>名字里的「Recorded」是要紧的：{@code turns.add(input)} 是桩回合的第一句，所以这只说明回合
         * **开跑了**，不说明它跑完了。「跑完了」由 {@link #endOfFile()} 自己那一等负责——两件事分开，
         * 是因为把它们混成一件正是这条竞态的成因。
         */
        void awaitTurnsRecorded(int n) throws Exception {
            waitUntil(() -> turns.size() >= n);
            assertEquals(n, turns.size(), "记下的回合数没在 5s 内到达 " + n + "：" + turns);
        }

        /** 被路由的斜杠命令数到达 {@code n}——斜杠命令不落事件循环，所以它们的可观察量在这里。 */
        void awaitRouted(int n) throws Exception {
            waitUntil(() -> routed.size() >= n);
            assertEquals(n, routed.size(), "路由的命令数没在 5s 内到达 " + n + "：" + routed);
        }

        /**
         * {@code run()} 真的返回了。
         *
         * <p>{@code join(5_000)} 单用是**静默**的：超时之后它照样往下走，于是「循环再也没退出」这件事
         * 会被读成绿。退出是本 rig 每一条的终止条件，所以它必须是一条会点名的断言。
         */
        void awaitReplReturn(Thread replThread) throws Exception {
            replThread.join(5_000);
            assertFalse(replThread.isAlive(), "REPL 循环没有在 5s 内退出");
        }

        /**
         * Ctrl+D：行编辑器抛 EndOfFileException，REPL 退出循环。
         *
         * <p><b>必须等到没有回合在跑才按下去</b>（issue 03）。Ctrl+D 在回合运行中是**无操作**
         * （{@code InteractiveRepl:337} 回去接着读），而这条管道交出这一个字节之后就再也不会有下一个
         * ——REPL 于是永久阻塞在 {@code readLine} 里，同步调用 {@code repl.run()} 的测试跟着挂死。
         * 本该救场的活性守卫 {@code inputStreamHasEnded()} 在这个 rig 里结构上不可能触发：它要求 5ms
         * 内连着来两次文件尾，管道只给得出一次（真实的死 stdin 会以循环自己的速度连续吐，所以那条守卫
         * 在生产路径上是对的——**不要**为了让测试绿去改它的语义）。
         *
         * <p>等的是 REPL 自己读的那个谓词 {@link SessionInterruptGuard#turnIsRunning()}，<b>不是</b>
         * {@code session.isIdle()}：后者只是它的一半（另一半是注册表里的前台令牌），在两者不一致的那
         * 一瞬间按下去，竞态原样还在。
         *
         * <p>被这一等摘掉的组合——「Ctrl+D 撞上运行中的回合」——由
         * {@code CancelKeyTableTest.ctrlDDuringATurnDoesNothing} 钉住，那个 rig 把 REPL 跑在后台线程上、
         * 不要求 {@code run()} 返回，所以在那里它是确定的而不是一场竞态。
         */
        void endOfFile() throws Exception {
            waitUntil(() -> !interrupts.turnIsRunning());
            assertFalse(interrupts.turnIsRunning(),
                    "回合没在 5s 内结束；此时写下 Ctrl+D 会让 rig 挂死");
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

    /** 记录调用的 Renderer 端口替身：REPL 的可观察输出全部经这里。 */
    private static final class RecordingRenderer implements Renderer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<ApprovalRequest> approvalsShown = new CopyOnWriteArrayList<>();

        String text() {
            return out.toString(StandardCharsets.UTF_8);
        }

        int beginTurns() {
            return (int) calls.stream().filter("beginTurn"::equals).count();
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
        public void appendToolCalls(List<ToolCallView> toolCalls) {
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
        public void showApproval(ApprovalRequest request, String notice) {
            approvalsShown.add(request);
        }

        @Override
        public void dismissApproval() {
            calls.add("dismissApproval");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
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
}
