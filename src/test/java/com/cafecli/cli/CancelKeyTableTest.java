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
import com.cafecli.render.inline.TracingTerminal;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.runtime.CancellationToken;
import com.cafecli.tool.ToolRegistry;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The [[取消键]] table (issue 08), cell by cell, at the S-B seam: a real {@link InteractiveRepl}
 * driven over pipe-backed streams with no TTY.
 *
 * <p>One rule: <b>有回合在跑就停回合</b>. The [[输入框]] is only cleared when there is nothing to
 * stop. What each cell asserts is what the user can observe — which text arrives when they press
 * Enter, whether the session is still serving, whether the turn's token was cancelled — never how
 * the keystroke was routed.
 *
 * <pre>
 *          | turn running        | idle, box has text | idle, box empty      | modal open
 *   ESC    | cancel              | nothing            | nothing              | modal only
 *   Ctrl+C | cancel, keep text   | clear the box      | hint, twice = exit   | same as left
 *   Ctrl+D | nothing             | nothing            | exit                 | nothing
 * </pre>
 *
 * <p>The modal column lives in {@code render/inline/ResidentInputRegimeTest} — it needs a real
 * [[转写浏览层]] holding the screen, which is exactly what that rig stands up.
 */
class CancelKeyTableTest {

    // --- ESC ---------------------------------------------------------------------------------

    /**
     * ESC 单一职责：**只做取消**。回合在跑时它停回合。
     */
    @Test
    @Timeout(30)
    void escDuringATurnStopsIt() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("long running\n");
            rig.awaitTurnToken();

            rig.pressEsc();
            waitUntil(() -> rig.turnToken.isCancelled());

            assertTrue(rig.turnToken.isCancelled(), "回合运行中按 ESC 必须停回合");
        }
    }

    /**
     * ESC 空闲时**无操作**——它此前兼任的「清空输入」交给行编辑器内建的整行删除（issue 07 交付的
     * {@code cafecli-cancel-or-clear} widget 正是在这一格清空输入框的，本 issue 把它删掉）。
     *
     * <p>理由是 ESC 的含义从此不依赖状态。判据是用户看得见的那件事：按下 ESC 之后回车，送出去的
     * 仍然是他刚才敲的那一行。
     */
    @Test
    @Timeout(30)
    void escWhileIdleDoesNothingAndTheDraftSurvives() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            rig.type("draft that survives");
            rig.awaitBox("draft that survives");

            rig.pressEsc();
            Thread.sleep(400);   // 远超 100ms 的 ambiguous-binding 窗口

            assertEquals("draft that survives", rig.box(), "空闲时的 ESC 不得清空[[输入框]]");
            rig.type("\n");
            waitUntil(() -> !rig.submitted.isEmpty());
            assertEquals(List.of("draft that survives"), rig.submitted);
        }
    }

    /** 空闲且框为空时的 ESC 同样什么都不做——尤其**不**退出。 */
    @Test
    @Timeout(30)
    void escWhileIdleAndEmptyDoesNothingAtAll() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            rig.pressEsc();
            Thread.sleep(400);

            assertFalse(rig.replReturned, "ESC 不是退出键");
            rig.type("still here\n");
            waitUntil(() -> !rig.submitted.isEmpty());
            assertEquals(List.of("still here"), rig.submitted, "会话必须照常继续服务");
        }
    }

    // --- Ctrl+C ------------------------------------------------------------------------------

    /**
     * 回合运行中按 Ctrl+C：回合停止，**框内已键入的文字原样保留**。
     *
     * <p>「先停下，然后按我说的做」必须是一个连续动作——取消若清空输入就破坏了它。机制上这是
     * {@code UserInterruptException.getPartialLine()}：中断把当前 {@code readLine} 打断，那一行文本
     * 随异常带出来，再被 seed 回下一次读取。
     */
    @Test
    @Timeout(30)
    void ctrlCDuringATurnStopsItAndKeepsWhatWasTyped() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("long running\n");
            rig.awaitTurnToken();

            rig.type("do it this way instead");
            rig.awaitBox("do it this way instead");

            rig.pressCtrlC();
            waitUntil(() -> rig.turnToken.isCancelled());
            assertTrue(rig.turnToken.isCancelled(), "回合运行中按 Ctrl+C 必须停回合");

            rig.awaitBox("do it this way instead");
            assertEquals("do it this way instead", rig.box(),
                    "取消不得清空[[输入框]]——「先停下，然后按我说的做」是一个连续动作");

            // 而且它是真的可以接着编辑并提交的，不只是显示上还在。
            rig.releaseTurn.countDown();
            waitUntil(rig.session::isIdle);
            rig.type("!\n");
            waitUntil(() -> rig.submitted.size() > 1);
            assertEquals("do it this way instead!", rig.submitted.get(rig.submitted.size() - 1));
        }
    }

    /**
     * 空闲且框内有文字时按 Ctrl+C：**清空输入框**。没有回合可停，那一行就是它能停的东西。
     */
    @Test
    @Timeout(30)
    void ctrlCWhileIdleClearsTheBox() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            rig.type("throw this away");
            rig.awaitBox("throw this away");

            rig.pressCtrlC();
            rig.awaitBox("");

            assertFalse(rig.replReturned, "框里有东西时的 Ctrl+C 不得退出");
            rig.type("a fresh line\n");
            waitUntil(() -> !rig.submitted.isEmpty());
            assertEquals(List.of("a fresh line"), rig.submitted,
                    "被清掉的那一行不得跟着下一次提交一起送出去");
        }
    }

    /**
     * 空闲且框为空时按 Ctrl+C：**首次给提示，约 2 秒内再次按下才退出**。
     *
     * <p>这是把「Ctrl+C 不再是逃生口」这个明码标价的代价收窄回来的那一格（ADR 0016 D3）：装上会话级
     * 中断之后 Ctrl+C 不再杀进程，于是必须有一条不靠信号的退出路。要按两次是为了不误触。
     */
    @Test
    @Timeout(30)
    void ctrlCOnAnEmptyBoxHintsFirstAndTheSecondPressExits() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();

            rig.pressCtrlC();
            waitUntil(() -> rig.renderer.text().contains("再按一次 Ctrl+C 退出"));
            assertTrue(rig.renderer.text().contains("再按一次 Ctrl+C 退出"),
                    "第一次按下必须先给提示：" + rig.renderer.text());
            assertFalse(rig.replReturned, "第一次按下不得退出");

            rig.pressCtrlC();
            waitUntil(() -> rig.replReturned);

            assertTrue(rig.replReturned, "窗口内的第二次按下必须退出");
            assertTrue(rig.renderer.calls.contains("close"),
                    "退出要道别并关掉渲染器：" + rig.renderer.calls);
        }
    }

    /**
     * 超时之后**重新计数**：过了窗口再按下的那一下又是「第一次」，不会因为几分钟前按过一次就把
     * 会话退掉。
     */
    @Test
    @Timeout(60)
    void theExitConfirmationReArmsAfterItsWindowPasses() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();

            rig.pressCtrlC();
            waitUntil(() -> rig.hints() == 1);

            Thread.sleep(2_400);   // 越过约 2 秒的确认窗口

            rig.pressCtrlC();
            waitUntil(() -> rig.hints() == 2);

            assertEquals(2, rig.hints(), "超时后的那一下必须重新算作「第一次」：" + rig.renderer.text());
            assertFalse(rig.replReturned, "超时后的第二次按下不得退出");

            // 而且它确实重新武装了：紧接着的这一下就退出。
            rig.pressCtrlC();
            waitUntil(() -> rig.replReturned);
            assertTrue(rig.replReturned, "重新计数之后窗口内的第二次按下照常退出");
        }
    }

    // --- 取消的范围，两键共享 -------------------------------------------------------------------

    /**
     * 取消**连带拒掉待决的[[审批应答]]**，ESC 与 Ctrl+C 一样（表里 Ctrl+C 那一格写的是「同上」，
     * 唯一的差别是框内文字保留）。
     *
     * <p>这不只是对称：一个 park 在审批 future 上的 worker 不会因为令牌被取消而醒过来，所以不拒掉
     * 它，「回合停下来」就永远落不到实处。
     */
    @Test
    @Timeout(30)
    void escDuringATurnRejectsThePendingApproval() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("write the file\n");
            rig.awaitTurnToken();
            CompletableFuture<ApprovalResult> worker = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);

            rig.pressEsc();
            waitUntil(() -> rig.turnToken.isCancelled());

            assertEquals(ApprovalResult.Decision.REJECTED,
                    worker.get(10, TimeUnit.SECONDS).decision(),
                    "取消必须把 park 在审批上的 worker 放掉，否则回合停不下来");
        }
    }

    /**
     * 取消**丢弃尚未生效的[[转向消息]]**：它瞄准的是这个回合，带进用户下一句话就成了挂在新请求上
     * 的陈旧指令。
     *
     * <p>判据是端到端的：没被取消时，一条没赶上迭代边界的转向消息会由回合结束兜底**重新投递为普通
     * 提交**（issue 10，静默丢弃比被吞掉的按键更糟）。所以「取消之后它没有作为提交出现」正是丢弃
     * 生效的可观察形状。
     */
    @Test
    @Timeout(30)
    void cancellingDiscardsSteeringThatNeverTookEffect() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("long running\n");
            rig.awaitTurnToken();

            rig.type("this correction is abandoned\n");
            Thread.sleep(200);
            assertEquals(List.of("long running"), rig.submitted,
                    "[[转向消息]]不开启新回合——它属于正在跑的那一个");

            rig.pressEsc();
            waitUntil(() -> rig.turnToken.isCancelled());
            rig.releaseTurn.countDown();
            waitUntil(rig.session::isIdle);
            Thread.sleep(200);

            assertEquals(List.of("long running"), rig.submitted,
                    "被取消的回合带走它的转向消息，不得作为下一次提交冒出来");
        }
    }

    @Test
    @Timeout(30)
    void ctrlCDuringATurnRejectsThePendingApprovalToo() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("write the file\n");
            rig.awaitTurnToken();
            CompletableFuture<ApprovalResult> worker = rig.requestApproval("write_file");
            waitUntil(rig.approvalPump::hasPending);

            rig.pressCtrlC();
            waitUntil(() -> rig.turnToken.isCancelled());

            assertEquals(ApprovalResult.Decision.REJECTED,
                    worker.get(10, TimeUnit.SECONDS).decision(),
                    "两键的取消范围一致：Ctrl+C 也拒掉待决审批");
        }
    }

    // --- Ctrl+D ------------------------------------------------------------------------------

    /**
     * Ctrl+D 在回合运行中**无操作**：正在跑的工作不该被一个「输入结束」按坏，而且它就在 Ctrl+C
     * 旁边——按错的代价是整个会话。
     */
    @Test
    @Timeout(30)
    void ctrlDDuringATurnDoesNothing() throws Exception {
        try (Rig rig = new Rig()) {
            rig.holdTheTurn();
            rig.start();
            rig.type("long running\n");
            rig.awaitTurnToken();

            rig.pressCtrlD();
            Thread.sleep(400);

            assertFalse(rig.replReturned, "回合运行中的 Ctrl+D 不得退出");
            assertFalse(rig.turnToken.isCancelled(), "它也不是取消键");

            rig.releaseTurn.countDown();
            waitUntil(rig.session::isIdle);
            rig.type("still serving\n");
            waitUntil(() -> rig.submitted.size() > 1);
            assertEquals("still serving", rig.submitted.get(rig.submitted.size() - 1));
        }
    }

    /**
     * 框里有文字时 Ctrl+D 也不退出——这一格由行编辑器内建的 {@code delete-char-or-list} 承担：
     * buffer 非空时 JLine 根本不抛 EOF。
     */
    @Test
    @Timeout(30)
    void ctrlDWithTextInTheBoxDoesNotExit() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();
            rig.type("keep me");
            rig.awaitBox("keep me");

            rig.pressCtrlD();
            Thread.sleep(400);

            assertFalse(rig.replReturned, "框里有东西时的 Ctrl+D 不得退出");
            assertEquals("keep me", rig.box(), "光标在行尾时它也不该吃掉字符");
        }
    }

    /** 空闲且框为空：Ctrl+D 退出。这是它唯一做事的一格。 */
    @Test
    @Timeout(30)
    void ctrlDOnAnEmptyBoxExits() throws Exception {
        try (Rig rig = new Rig()) {
            rig.start();

            rig.pressCtrlD();
            waitUntil(() -> rig.replReturned);

            assertTrue(rig.replReturned, "空闲且框为空时 Ctrl+D 退出");
            assertTrue(rig.renderer.calls.contains("close"),
                    "退出要道别并关掉渲染器：" + rig.renderer.calls);
        }
    }

    // --- rig -----------------------------------------------------------------------------------

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /** 管道流驱动的 REPL 装配：没有真实 TTY，没有真实模型，回合由桩 turnRunner 记录。 */
    static final class Rig implements AutoCloseable {
        private final PipedOutputStream keys;
        final TracingTerminal terminal;
        final LineReader lineReader;
        final AgentSession session;
        final CancellationRegistry cancellations = new CancellationRegistry();
        final ApprovalPump approvalPump = new ApprovalPump();
        final RecordingRenderer renderer = new RecordingRenderer();
        final SessionInterruptGuard interrupts;
        final InteractiveRepl repl;
        /** 到达事件循环的提交（也就是用户按下回车时框里到底是什么）。 */
        final List<String> submitted = new CopyOnWriteArrayList<>();
        final CountDownLatch releaseTurn = new CountDownLatch(1);
        volatile CancellationToken turnToken;
        volatile boolean replReturned;
        private Thread replThread;

        Rig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            terminal = new TracingTerminal("cancel-key-table", "xterm-256color", in, sink);
            terminal.setSize(new Size(100, 24));
            // 与 Main 同一条约束（ADR 0016 D2）：ambiguous-binding 只能经构造器的 variables 传入。
            lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.AMBIGUOUS_BINDING, 100L)
                    .build();

            Agent agent = new Agent(new StubLlmClient(), new ToolRegistry());
            agent.setRenderer(renderer);
            session = new AgentSession(agent, new TaskRegistry(), output -> { });
            session.setTurnRunner(input -> {
                submitted.add(input);
                return "turn-done";
            });
            session.start();

            interrupts = new SessionInterruptGuard(cancellations, renderer, approvalPump, session);
            repl = new InteractiveRepl(terminal, lineReader, renderer, session, approvalPump,
                    interrupts,
                    (command, rawInput) -> InteractiveRepl.SlashCommandRouter.Outcome.HANDLED,
                    InteractiveRepl.PromptPreparation.Result::accepted);
            approvalPump.setPendingNotifier(repl::onApprovalPending);
        }

        /** 回合跑起来后 park 在自己的取消令牌上——取消是协作式的，回合自己在边界停。 */
        void holdTheTurn() {
            session.setTurnRunner(input -> {
                submitted.add(input);
                CancellationToken token = cancellations.beginForegroundTurn();
                turnToken = token;
                try {
                    long deadline = System.currentTimeMillis() + 20_000;
                    while (!token.isCancelled() && releaseTurn.getCount() > 0
                            && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    cancellations.endForegroundTurn(token);
                }
                return "turn-done";
            });
        }

        void start() throws Exception {
            replThread = new Thread(() -> {
                repl.run();
                replReturned = true;
            }, "cancel-key-table-repl");
            replThread.setDaemon(true);
            replThread.start();
            waitUntil(() -> lineReader.isReading());
        }

        void awaitTurnToken() throws Exception {
            waitUntil(() -> turnToken != null);
            if (turnToken == null) {
                throw new AssertionError("turn never started");
            }
        }

        /** 「再按一次就退出」这条提示至今出现过几次。 */
        int hints() {
            return renderer.text().split("再按一次 Ctrl\\+C 退出", -1).length - 1;
        }

        /** [[输入框]]此刻的内容。 */
        String box() {
            return lineReader.getBuffer().toString();
        }

        void awaitBox(String expected) throws Exception {
            waitUntil(() -> expected.equals(box()));
            assertEquals(expected, box(), "input box never reached the expected draft");
        }

        void type(String text) throws Exception {
            keys.write(text.getBytes(StandardCharsets.UTF_8));
            keys.flush();
        }

        void pressEsc() throws Exception {
            keys.write(27);
            keys.flush();
        }

        /** Ctrl+C 是**信号**，不经字节流——真机上它也不是被 reader 读到的一个字节。 */
        void pressCtrlC() throws Exception {
            // 信号得落在一次**正在进行**的读取上，否则 JLine 自己的 INT handler 还没装上去，这一下
            // 就打在空处。真机上不会有这个问题（用户按键时读取器一直在读），是驱动方式的产物。
            waitUntil(lineReader::isReading);
            Thread.sleep(30);
            terminal.raise(Terminal.Signal.INT);
        }

        /** Ctrl+D：VEOF 字节。空 buffer 时 JLine 抛 {@code EndOfFileException}。 */
        void pressCtrlD() throws Exception {
            keys.write(4);
            keys.flush();
        }

        CompletableFuture<ApprovalResult> requestApproval(String tool) {
            ApprovalRequest request = new ApprovalRequest(tool, "{}", "high",
                    "writes to disk", null, "main Agent", null);
            return CompletableFuture.supplyAsync(() -> approvalPump.requestApproval(request));
        }

        @Override
        public void close() throws Exception {
            releaseTurn.countDown();
            session.stop();
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            if (replThread != null) {
                replThread.join(2_000);
                replThread.interrupt();
            }
            terminal.close();
        }
    }

    /** 记录调用的 Renderer 端口替身：REPL 的可观察输出全部经这里。 */
    static final class RecordingRenderer implements Renderer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<ApprovalRequest> approvalsShown = new CopyOnWriteArrayList<>();

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
