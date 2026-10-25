package com.cafecli.render.inline;

import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.History;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.Status;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 交出画笔的行编辑器（ADR 0014 决策 2）：继续负责编辑语义、补全、历史、高亮，但**不再自己写终端**。
 *
 * <p>做法是把继承来的 {@code protected Display display} 永久换成 {@link CaptureDisplay}，然后
 * {@code super.redisplay(flush)} 原样跑。{@code redisplay} 的全部私有助手（次级提示符、右提示符、
 * 补全菜单行、小终端偏移数学）因此原样复用，**零行方法体拷贝**，而它们的输出全部落进捕获帧、
 * 由渲染线程合成。
 *
 * <p>{@code private doDisplay()} 有四个调用点，其中三个（构造 / {@code readLine} 起始 / WINCH）都在
 * {@code redisplay} 之前，重装守卫够用；第四个在 {@code super.redisplay()} <em>内部</em>——JLine
 * {@code Status} 尺寸变化时重建 {@code display}，会把截获实例换掉（Wave 0 实测：未处理时该次按键的
 * 提示符与缓冲直接画进终端且零捕获帧）。故覆写顶部同步继承来的 {@code lastStatusSize} 使该分支不可达。
 * 状态坞离开 JLine {@code Status} 后（决策 4）{@code Status.getStatus(terminal, false)} 恒为 null，
 * 该分支另按构造不可达——同步 {@code lastStatusSize} 是纵深防御，不是唯一依赖。
 * 顺序有意义：重装 → 同步 {@code lastStatusSize} → {@code super} → 守卫计数。
 *
 * <p>JLine 的 {@code LineReaderBuilder} 不可用（final + 私有构造 + {@code build()} 硬编码基类），
 * 故建构走 {@link #create}；注意 {@code variables} 只能走构造器（{@code INPUT_RC_FILE_NAME} 构造期即被读取）。
 */
public class CafeCliLineReader extends org.jline.reader.impl.LineReaderImpl {

    private final CaptureDisplay capture;
    private final AtomicInteger captureSwapsAfterRedisplay = new AtomicInteger();
    private volatile Consumer<String> printAboveSink;
    private volatile Runnable geometrySignalHint;
    private volatile java.util.function.Supplier<String> rightPromptSupplier;

    public CafeCliLineReader(Terminal terminal, String appName, Map<String, Object> variables) {
        super(terminal, appName, variables);
        this.capture = new CaptureDisplay(terminal);
        installCapture();
    }

    /**
     * 手工建构，逐项对齐 {@code LineReaderBuilder.build()} 的构造后配置。
     *
     * @param variables 只能在构造期传入，不可事后 setter
     */
    public static CafeCliLineReader create(Terminal terminal,
                                           String appName,
                                           Map<String, Object> variables,
                                           History history,
                                           Completer completer,
                                           Highlighter highlighter) {
        CafeCliLineReader reader = new CafeCliLineReader(terminal,
                appName == null ? terminal.getName() : appName,
                variables == null ? new HashMap<>() : variables);
        if (history != null) {
            reader.setHistory(history);
        }
        if (completer != null) {
            reader.setCompleter(completer);
        }
        if (highlighter != null) {
            reader.setHighlighter(highlighter);
        }
        // parser / expander / completionMatcher 在 LineReaderImpl 字段初始化时已是同一批默认实现，
        // 与 LineReaderBuilder 不传时的结果一致，故不重复设置。
        return reader;
    }

    /**
     * 该终端是否可以让行编辑器交出画笔。能力不足时不安装本子类，走原生行编辑器 + 现有形态。
     * 判据与 {@link InlineRenderer} 是否拥有活区一致——没有活区就没有人来画输入行。
     */
    public static boolean supported(Terminal terminal) {
        return terminal != null && TerminalCapabilities.supportsScrollRegion(terminal);
    }

    public CaptureDisplay capture() {
        return capture;
    }

    /** {@code super.redisplay()} 返回后仍被换走截获 {@code Display} 的次数；恒应为 0。 */
    public int captureSwapsAfterRedisplay() {
        return captureSwapsAfterRedisplay.get();
    }

    /**
     * Where the right prompt comes from, re-asked on every re-lay of the [[输入框]].
     *
     * <p>{@code readLine} takes its right prompt once, at the call. But the two states worth
     * signposting — a turn is running, an [[审批应答]] is waiting — both begin on <em>other</em>
     * threads while this reader sits inside a single {@code readLine}, so a value fixed at the call
     * would be stale for exactly as long as it matters. Asking a supplier here keeps the decision on
     * the input thread (this method only ever runs there) while letting the answer come from the
     * session.
     */
    public void setRightPromptSupplier(java.util.function.Supplier<String> supplier) {
        this.rightPromptSupplier = supplier;
    }

    /** 覆写的 {@code printAbove} 的去处：投进[[冻结回卷]]队列而非直写终端。 */
    public void setPrintAboveSink(Consumer<String> sink) {
        this.printAboveSink = sink;
    }

    /**
     * Where a SIGWINCH delivered <em>while this reader is reading</em> is forwarded.
     *
     * <p>It has to be forwarded explicitly. {@code readLine} installs the base class's own WINCH
     * handler for its whole duration and that handler does not chain, so any handler the renderer
     * installed is shadowed for exactly as long as the [[输入框]] is live — which, since the input
     * box is permanent (ADR 0014 决策 2), is essentially always. The renderer used to notice a
     * resize anyway because it re-measured the terminal on every frame; now that geometry is
     * sampled rather than measured (ADR 0010 amendment A3c), the wake-up hint has to reach it.
     */
    public void setGeometrySignalHint(Runnable hint) {
        this.geometrySignalHint = hint;
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        super.handleSignal(signal);
        Runnable hint = geometrySignalHint;
        if (signal == Terminal.Signal.WINCH && hint != null) {
            hint.run();
        }
    }

    Display currentDisplay() {
        return display;
    }

    /**
     * Re-lay the [[输入框]] after something changed that this thread did not do — a turn ended, an
     * [[审批应答]] arrived — so the right prompt and the box follow the session's state rather than
     * the last keystroke.
     *
     * <p>It is JLine's own cross-thread redraw discipline: take the reader's lock, redisplay only
     * while it is actually reading. {@code printAbove} does exactly this. Having handed over the
     * brush, a redisplay writes no terminal bytes at all — it publishes one captured frame — so
     * this adds a producer, not a second writer. The render thread must never call it: composing a
     * frame would then depend on the input thread, which may be inside a modal.
     */
    public void refreshInputBox() {
        lock.lock();
        try { refreshCapturedInput(); }
        finally { lock.unlock(); }
    }

    /**
     * Queued UI presentation must never wait behind an input-thread modal: that same queue may own
     * the approval notification which releases the modal. Its exit widget redisplays the retained draft.
     */
    public void tryRefreshInputBox() {
        if (!lock.tryLock()) return;
        try { refreshCapturedInput(); }
        finally { lock.unlock(); }
    }

    private void refreshCapturedInput() {
        try {
            if (reading) redisplay(false);
        } catch (RuntimeException ignored) {
            // A refresh is cosmetic; the next keystroke lays the box out again.
        }
    }

    private void installCapture() {
        Size size = getTerminal().getBufferSize();
        capture.resize(size.getRows(), size.getColumns());
        if (isSet(Option.DELAY_LINE_WRAP)) {
            capture.setDelayLineWrap(true);
        }
        display = capture; // 继承来的 protected 字段
    }

    @Override
    protected void redisplay(boolean flush) {
        installCapture();
        java.util.function.Supplier<String> prompt = rightPromptSupplier;
        if (prompt != null) {
            try {
                setRightPrompt(prompt.get());
            } catch (RuntimeException ignored) {
                // 右提示符只是路标：算不出来就沿用上一次的，不能因此不画输入框。
            }
        }
        Status status = Status.getStatus(getTerminal(), false); // 状态坞退役后恒为 null
        lastStatusSize = status != null ? status.size() : 0;    // 使内部那次 doDisplay() 分支不可达
        super.redisplay(flush);
        if (display != capture) {
            // JLine 升级挪动了 doDisplay() 的调用位置：立刻计数（守卫测试据此变红），并自愈，
            // 免得静默退化成「输入行由 JLine 直画」的双画者。
            captureSwapsAfterRedisplay.incrementAndGet();
            installCapture();
        }
    }

    @Override
    public void printAbove(String str) {
        Consumer<String> sink = printAboveSink;
        if (sink == null) {
            super.printAbove(str);
            return;
        }
        if (str != null && !str.isEmpty()) {
            sink.accept(str);
        }
    }

    @Override
    public void printAbove(AttributedString str) {
        if (str != null) {
            printAbove(str.toAnsi(getTerminal()));
        }
    }

    @Override
    protected void doCleanup(boolean nl) {
        // nl=false：省掉基类直写终端的 println()——换行由活区自己的冻结纪律负责。
        super.doCleanup(false);
        capture.clearFrame();
    }

}
