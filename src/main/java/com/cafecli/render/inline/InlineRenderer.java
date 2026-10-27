package com.cafecli.render.inline;

import com.cafecli.render.ApprovalRequest;
import com.cafecli.render.ApprovalResult;
import com.cafecli.render.PlainRenderer;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.ToolResultView;
import com.cafecli.render.inline.replay.TerminalReplayRecorder;
import com.cafecli.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；输入期状态区紧跟当前 prompt 渲染。
 * 工具调用块、行内 diff、HITL 单字符提示、palette 等高级特性在 Day 3 / Day 4 落地，
 * 现阶段对应方法委托给 {@link PlainRenderer} 兜底。
 */
public final class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final PrintStream stream;
    private final PrintStream out;
    private final Object streamLock = new Object();
    private final ActivityBlock activityBlock;
    private final LiveRegionStrip liveStrip;
    private final RenderLoop renderLoop;
    private final FramePump framePump;
    private final FrameTicker frameTicker;
    private final TerminalReplayRecorder replayRecorder;
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private final AtomicReference<StatusInfo> pendingStatus = new AtomicReference<>();
    private final AtomicBoolean statusEventQueued = new AtomicBoolean();
    private volatile LineReader lineReader;
    private volatile boolean inputLineOwnedByLiveRegion;
    private Terminal.SignalHandler previousWinchHandler;
    private Terminal.SignalHandler installedWinchHandler;
    private boolean winchHandlerInstalled;
    private volatile boolean started;
    private volatile boolean closed;
    // A pending [[审批应答]] closes the [[转写浏览层]] (ADR 0010 amendment A14). Counted rather than
    // flagged: the overlay compares against the count it opened on, so it closes for approvals raised
    // while it is up and never for one already answered — no one has to remember to clear anything.
    private final AtomicLong approvalsRaised = new AtomicLong();
    private volatile Runnable sessionInterrupt = () -> { };
    private volatile ApprovalBlock approvalCard;
    // Where the reader was when the overlay last closed, so an interruption costs them nothing (A14).
    private volatile TranscriptOverlay.ReadingPosition transcriptPosition =
            TranscriptOverlay.ReadingPosition.TAIL;
    private volatile boolean terminalWritesQuiesced;

    // Unstructured stream text is line-buffered so printAbove receives complete lines.
    private final StringBuilder lineBuffer = new StringBuilder();

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    /** Opt-in terminal replay recording boundary used by the deterministic resize harness. */
    public InlineRenderer(Terminal terminal, TerminalReplayRecorder recorder) {
        this(terminal, System.out, new ScheduledFramePump(), new ScheduledFrameTicker(),
                TerminalCapabilities.supportsScrollRegion(terminal)
                        ? new BottomStatusBar()
                        : null,
                recorder);
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    InlineRenderer(Terminal terminal, PrintStream out) {
        this(terminal, out, new ScheduledFramePump(), new ScheduledFrameTicker());
    }

    /** Deterministic test seam for frame scheduling and spinner ticks. */
    InlineRenderer(Terminal terminal, PrintStream out, FramePump framePump, FrameTicker frameTicker) {
        this(terminal, out, framePump, frameTicker,
                TerminalCapabilities.supportsScrollRegion(terminal)
                        ? new BottomStatusBar()
                        : null);
    }

    /**
     * Deterministic clock seam. [[geometry turbulence]] is timed — quiet interval, max defer — so
     * asserting it without real waiting means injecting the clock the renderer times against
     * (ADR 0010 amendment A3d).
     */
    InlineRenderer(Terminal terminal,
                   PrintStream out,
                   FramePump framePump,
                   FrameTicker frameTicker,
                   TimeSource clock) {
        this(terminal, out, framePump, frameTicker,
                TerminalCapabilities.supportsScrollRegion(terminal)
                        ? new BottomStatusBar()
                        : null,
                TerminalReplayRecorder.disabled(),
                clock);
    }

    /** Deterministic JLine status boundary seam. */
    InlineRenderer(Terminal terminal,
                   PrintStream out,
                   FramePump framePump,
                   FrameTicker frameTicker,
                   BottomStatusBar statusBar) {
        this(terminal, out, framePump, frameTicker, statusBar, TerminalReplayRecorder.disabled());
    }

    /** Deterministic recorder seam for terminal-boundary tests. */
    InlineRenderer(Terminal terminal,
                   PrintStream out,
                   FramePump framePump,
                   FrameTicker frameTicker,
                   BottomStatusBar statusBar,
                   TerminalReplayRecorder replayRecorder) {
        this(terminal, out, framePump, frameTicker, statusBar, replayRecorder, TimeSource.system());
    }

    private InlineRenderer(Terminal terminal,
                           PrintStream out,
                           FramePump framePump,
                           FrameTicker frameTicker,
                           BottomStatusBar statusBar,
                           TerminalReplayRecorder replayRecorder,
                           TimeSource clock) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.framePump = framePump;
        this.frameTicker = frameTicker;
        this.statusBar = statusBar;
        this.replayRecorder = replayRecorder == null
                ? TerminalReplayRecorder.disabled()
                : replayRecorder;
        if (statusBar == null) {
            this.activityBlock = null;
            this.liveStrip = null;
            this.renderLoop = null;
        } else {
            ActivityBlock activity = new ActivityBlock(terminal, frameTicker);
            LiveRegionStrip strip = new LiveRegionStrip(terminal, clock);
            if (this.replayRecorder.isEnabled()) {
                strip.bindResizeObserver(this.replayRecorder::observeResize);
            }
            RenderLoop loop = new RenderLoop(framePump,
                    strip::renderFrame,
                    new TerminalFrameWriter(terminal, out, this.replayRecorder),
                    TerminalCapabilities.supportsSynchronizedOutput(terminal));
            activity.bind(loop);
            // A deferred redraw is released by a frame, so while one is owed the strip must be able to
            // ask for the next one itself; nothing else is guaranteed to post an event after the last
            // dimension change of a gesture.
            strip.bindFrameRequest(() -> loop.post(() -> { }));
            strip.bindLeadingEntry(activity);
            // The dock is composed by the render thread as the trailing rows of the live region.
            strip.bindDock(statusBar::rows);
            this.activityBlock = activity;
            this.liveStrip = strip;
            this.renderLoop = loop;
        }
        this.stream = createTranscriptStream(out);
    }

    /**
     * 测试接缝：本渲染器绘制的[[活区]]。让屏幕真相回归可以直接接一个假的输入行帧，而不必为了拿到
     * {@code lastCursorPos >= 0} 这条分支去站起真实的 {@link CafeCliLineReader} 与读输入线程。
     */
    LiveRegionStrip liveRegion() {
        return liveStrip;
    }

    @Override
    public void beginTurn() {
        if (renderLoop != null) {
            renderLoop.resetStats();
        }
        synchronized (streamLock) {
            lineBuffer.setLength(0);
        }
    }

    @Override
    public void endTurn() {
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(liveStrip::finishTurn);
        }
        if (Boolean.parseBoolean(System.getProperty("paicli.render.stats", "false"))
                && activityBlock != null && renderLoop != null) {
            activityBlock.appendOutput(renderLoop.stats().formatLine() + "\n");
        }
    }

    @Override
    public void clearView() {
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(liveStrip::clear);
        }
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        if (renderLoop != null && terminal != null) {
            try {
                installedWinchHandler = signal -> {
                    Terminal.SignalHandler previous = previousWinchHandler;
                    try {
                        if (previous != null
                                && previous != installedWinchHandler
                                && previous != Terminal.SignalHandler.SIG_DFL
                                && previous != Terminal.SignalHandler.SIG_IGN) {
                            previous.handle(signal);
                        }
                    } finally {
                        onGeometrySignal();
                    }
                };
                previousWinchHandler = terminal.handle(Terminal.Signal.WINCH, installedWinchHandler);
                winchHandlerInstalled = true;
            } catch (RuntimeException ignored) {
                // Some fallback terminals do not expose signal handling.
            }
            // Geometry is sampled, not signalled (ADR 0010 amendment A3c). The signal handler above is
            // a wake-up hint into this sampler; the sampler itself is what decides an epoch ended, and
            // its slow heartbeat is the whole channel on a terminal that raises no signal at all.
            liveStrip.startGeometrySampling();
        }
        started = true;
    }

    /**
     * A SIGWINCH arrived, from whichever handler saw it.
     *
     * <p>It is a wake-up hint, not a record of a dimension change (ADR 0010 amendment A3c): it arms
     * the geometry sampler and asks for an ordinary frame. Whether a [[geometry epoch]] ended is
     * decided by the sample that frame takes, not by the signal — which is why coalesced or spurious
     * signals cost nothing. Bypassing the frame cadence retired along with the erase arithmetic it
     * protected: an absolute reset has no per-step dependency on the previous frame's width.
     */
    private void onGeometrySignal() {
        if (liveStrip == null || renderLoop == null) {
            return;
        }
        long generation = liveStrip.armGeometrySample();
        replayRecorder.recordResizeSignal(generation);
        renderLoop.post(() -> { });
    }

    @Override
    public void beforeInput() {
        // Ask the render thread for one more frame so the dock is up to date directly above the
        // prompt. The dock is drawn as live-region rows now, so nothing is written from this thread.
        if (renderLoop != null) {
            renderLoop.post(() -> { });
        }
    }

    @Override
    public String inputPrompt() {
        // 编辑态标记 `* ` 退役（ADR 0014）：编辑与提交都用 `>` 家族，靠位置（活区内 / 回卷）与
        // 暗色区分。标记本身取[[提交块]]的紫色（ADR 0016 D6），让活区里的 `>` 与回卷里的 `›`
        // 是同一族——提交因此读起来像这一行原地冻上去，而不是文字消失、另一个块出现。
        return InputBoxChrome.prompt(InputBoxChrome.Palette.of(terminal));
    }

    @Override
    public String inputRightPrompt() {
        return "message / @path / @image";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (liveStrip != null) {
            liveStrip.close();
        }
        if (activityBlock != null) {
            activityBlock.close();
        }
        if (renderLoop != null) {
            renderLoop.close();
        } else {
            frameTicker.close();
            framePump.close();
        }
        if (statusBar != null) {
            statusBar.close();
        }
        if (winchHandlerInstalled && terminal != null) {
            try {
                terminal.handle(Terminal.Signal.WINCH, previousWinchHandler == null
                        ? Terminal.SignalHandler.SIG_DFL
                        : previousWinchHandler);
            } catch (RuntimeException ignored) {
                // Terminal may already be shutting down.
            }
            winchHandlerInstalled = false;
            installedWinchHandler = null;
        }
        fallback.close();
    }

    /**
     * Establish the terminal-output endpoint used by the replay harness.
     *
     * <p>Unlike {@link #close()}, this deliberately leaves the last live frame on screen. It stops
     * activity/ticker producers, discards events that have not begun rendering, waits for any terminal
     * write already in flight, and blocks the direct transcript path. After it returns this renderer
     * cannot emit another terminal byte.</p>
     */
    void quiesceForReplayCapture() {
        if (closed) {
            return;
        }
        synchronized (out) {
            terminalWritesQuiesced = true;
        }
        closed = true;
        if (liveStrip != null) {
            liveStrip.close();
        }
        if (activityBlock != null) {
            activityBlock.quiescePreservingFrame();
        }
        if (renderLoop != null) {
            renderLoop.quiescePreservingFrame();
        } else {
            frameTicker.close();
            framePump.close();
        }
        if (statusBar != null) {
            statusBar.close();
        }
        if (winchHandlerInstalled && terminal != null) {
            try {
                terminal.handle(Terminal.Signal.WINCH, previousWinchHandler == null
                        ? Terminal.SignalHandler.SIG_DFL
                        : previousWinchHandler);
            } catch (RuntimeException ignored) {
                // Terminal may already be shutting down.
            }
            winchHandlerInstalled = false;
            installedWinchHandler = null;
        }
        fallback.close();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public int terminalColumns() {
        return Math.max(40, TerminalCapabilities.safeSize(terminal).getColumns());
    }

    @Override
    public boolean supportsThinkingPanel() {
        return activityBlock != null;
    }

    @Override
    public void beginThinking(String label) {
        if (activityBlock != null && !closed) {
            // A new reasoning burst in a later ReAct step: commit the previous step's collapsed
            // reasoning / tool blocks to scrollback so only the live spinner repaints. Otherwise a
            // completed "思考 N 行" header stacks directly on top of the active "Thinking..." line
            // and both smear when the wheel is scrolled mid-turn.
            if (liveStrip != null && renderLoop != null) {
                renderLoop.post(liveStrip::freezeSettled);
            }
            activityBlock.begin(label);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityBlock != null && !closed) {
            activityBlock.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (activityBlock != null) {
            activityBlock.end();
        }
    }

    @Override
    public BlockWriter beginReasoning() {
        if (activityBlock == null) {
            return Renderer.super.beginReasoning();
        }
        return new BlockWriter() {
            private final StringBuilder source = new StringBuilder();
            private boolean finished;

            @Override
            public void append(String sourceDelta) {
                if (!finished && sourceDelta != null && !sourceDelta.isEmpty()) {
                    source.append(sourceDelta);
                }
            }

            @Override
            public void finish() {
                if (finished) {
                    return;
                }
                finished = true;
                String completed = source.toString();
                if (completed.isBlank()) {
                    return;
                }
                ReasoningBlock block = new ReasoningBlock(terminal, completed);
                renderLoop.post(() -> liveStrip.add(block));
            }
        };
    }

    @Override
    public BlockWriter beginAnswer() {
        if (liveStrip == null || renderLoop == null) {
            return Renderer.super.beginAnswer();
        }
        AnswerSegment answer = new AnswerSegment(terminal, renderLoop::recordCacheHit);
        renderLoop.post(() -> liveStrip.add(answer));
        return new BlockWriter() {
            private boolean finished;

            @Override
            public void append(String sourceDelta) {
                if (!finished && sourceDelta != null && !sourceDelta.isEmpty()) {
                    renderLoop.post(() -> answer.append(sourceDelta));
                }
            }

            @Override
            public void finish() {
                if (!finished) {
                    finished = true;
                    renderLoop.post(answer::finish);
                }
            }
        };
    }

    /**
     * 绑定当前交互循环使用的 JLine LineReader。
     *
     * <p>绑定的若是{@link CafeCliLineReader}（交出画笔的子类），则把它接成活区的又一个**生产者**：
     * 输入框的帧作为活区末尾的常驻条目汇入合成帧，{@code printAbove} 改投[[冻结回卷]]队列。
     * 能力不足的终端传进来的是原生 {@code LineReader}，此时只保留
     * {@code printAbove} 作为异步输出的兼容边界，形态与今天一致。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
        if (liveStrip == null || renderLoop == null) {
            return;
        }
        if (lineReader instanceof CafeCliLineReader capture) {
            inputLineOwnedByLiveRegion = true;
            CaptureDisplay display = capture.capture();
            liveStrip.bindInputBox(display::lastFrame);
            // 输入线程只投事件，不画终端；帧由渲染线程合成。
            display.setFrameListener(frame -> renderLoop.post(() -> { }));
            capture.setPrintAboveSink(this::freezeExternalText);
            // While readLine runs it owns the WINCH handler and does not chain, so the hint reaches
            // the geometry sampler through the reader rather than through our own handler.
            capture.setGeometrySignalHint(this::onGeometrySignal);
        }
    }

    /**
     * 交给[[活区]]去冻结的普通文本：由渲染线程作为[[冻结回卷]]写出，并在写出的同时登记进有序提交
     * 日志，因此能被下一次[[destructive resize reset]]的[[bounded replay window]]重画出来。
     *
     * <p>行编辑器截获的 {@code printAbove} 与 {@link #stream()} 的行缓冲输出都汇到这里——两者都是
     * 「提交到回卷的普通文本」，没有第二种含义，也不该有第二条路径。
     */
    private void freezeExternalText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (closed || renderLoop == null || !renderLoop.post(() -> liveStrip.printAbove(text))) {
            writeDirect(text.endsWith("\n") ? text : text + "\n");
        }
    }

    /**
     * 在 LineReader 第一次进入 readLine 时打印首屏。
     *
     * <p>首屏不能在 readLine 之前用普通 stdout 打印：底部 Status 初始化后，LineReader
     * 第一次重绘会重新接管输入区，提前打印的 banner 容易被滚动或覆盖。挂到
     * {@link LineReader#CALLBACK_INIT} 后，JLine 会把首屏、输入行和底部 dock 放在同一个
     * 显示生命周期里处理。
     */
    public void installStartupScreen(List<String> lines) {
        LineReader reader = lineReader;
        if (reader == null || lines == null || lines.isEmpty()) {
            return;
        }
        startupScreenPrinted.set(false);
        List<String> snapshot = List.copyOf(lines);
        Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
        reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean ok = previous == null || previous.apply();
            if (startupScreenPrinted.compareAndSet(false, true)) {
                reader.printAbove(joinLines(snapshot));
            }
            return ok;
        });
    }

    @Override
    public void appendUserPrompt(String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            // The source text, not a rendering of it: the [[活区]] lays it out at the width of the
            // frame that commits it, and the ordered commit log keeps the source so a
            // [[destructive resize reset]] can re-render it at the new width (ADR 0010 amendment A11).
            renderLoop.post(() -> liveStrip.submitPrompt(visible));
        } else {
            int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
            emit(SubmittedPromptBlock.render(visible, cols, terminal));
        }
    }

    public void printSubmittedPrompt(String input) {
        appendUserPrompt(input);
    }

    /**
     * Echo a [[转向消息]]: a submission that went into a turn that is <b>already running</b>
     * (issue 10).
     *
     * <p>It is the same source-form [[提交块]] as an ordinary submission — so a
     * [[destructive resize reset]] re-renders it at the new width instead of replaying a wash padded
     * to the old one (ADR 0010 amendment A8b) — and it takes its position in the ordered commit log
     * exactly where it was submitted, which is the whole point: the user is asking "where did my
     * correction land?".
     *
     * <p>What differs from {@link #appendUserPrompt} is that the [[活区]] keeps its live entries: the
     * answer this correction interrupted is still streaming and must go on streaming below the echo.
     */
    public void printSteeringPrompt(String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(() -> liveStrip.steerPrompt(visible));
        } else {
            int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
            emit(SubmittedPromptBlock.render(visible, cols, terminal));
        }
    }

    @Override
    public void appendToolCalls(List<ToolCallView> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            ToolCallBlock block = new ToolCallBlock(terminal, toolCalls);
            renderLoop.post(() -> liveStrip.addRunningTools(block));
            return;
        }
        fallback.appendToolCalls(toolCalls);
    }

    /**
     * The batch has returned: fill its results in and let it be committed as one block (ADR 0017 D2).
     *
     * <p>The pair is matched by {@code tool_call_id} inside the [[活区]], so this event carries no
     * identity of its own; a batch whose header never reached the strip simply has nothing to
     * complete.
     */
    @Override
    public void appendToolResults(List<ToolResultView> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(() -> liveStrip.completeTools(results));
            return;
        }
        Renderer.super.appendToolResults(results);
    }

    /**
     * A running command's output line: post it as a render event and return (ADR 0017 D5).
     *
     * <p>The caller is the {@code cafecli-command-output} thread inside the command leaf, and the
     * render thread is the [[活区]]'s only writer — so nothing is written here, and no terminal is
     * touched. The line lands in the open [[工具块]]'s running tail and reaches the screen on the next
     * frame, exactly like every other producer event.
     */
    @Override
    public void appendCommandOutput(String command, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(() -> liveStrip.appendRunningCommandOutput(command, line));
        }
    }

    @Override
    public void appendCodeBlock(String language, String code) {
        if (liveStrip != null && renderLoop != null) {
            CodeBlock block = new CodeBlock(terminal, language, code);
            renderLoop.post(() -> liveStrip.add(block));
            return;
        }
        Renderer.super.appendCodeBlock(language, code);
    }

    /**
     * A file changed — which, for {@code write_file} and {@code edit_file}, <b>is</b> the tool's result
     * (ADR 0017 D4), so the strip hands it to the batch that is running rather than committing it as a
     * block of its own.
     *
     * <p>The diff is still computed here, on the producer thread: it is the {@link DiffBlock}
     * constructor that walks the two file versions, and a frame should not pay for that.
     */
    @Override
    public void appendDiff(String filePath, String before, String after) {
        if (liveStrip != null && renderLoop != null) {
            DiffBlock block = new DiffBlock(terminal, filePath, before, after);
            renderLoop.post(() -> liveStrip.addDiff(block));
            return;
        }
        fallback.appendDiff(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar == null || closed || status == null) {
            return;
        }
        // The dock is only ever mutated on the render thread: a dock exists exactly when the render
        // loop does, and the latest status is coalesced into one event per frame.
        pendingStatus.updateAndGet(previous -> BottomStatusBar.mergeEnvironment(status, previous));
        queueLatestStatus();
    }

    private void queueLatestStatus() {
        if (!statusEventQueued.compareAndSet(false, true)) {
            return;
        }
        if (!renderLoop.post(this::applyLatestStatus)) {
            statusEventQueued.set(false);
        }
    }

    private void applyLatestStatus() {
        StatusInfo latest = pendingStatus.getAndSet(null);
        if (latest != null) {
            statusBar.update(latest);
        }
        statusEventQueued.set(false);
        if (pendingStatus.get() != null) {
            queueLatestStatus();
        }
    }

    /**
     * Put the [[审批应答]] card in the [[活区]] and return — it is drawn, updated and removed by the
     * render thread and answered through the [[输入框]] (issue 07).
     *
     * <p>The card and the answer are two different threads' work on purpose. Before this, the single
     * writer of the live region also owned a raw-mode single-key read, which contends with the now
     * resident line editor for the same {@code NonBlockingReader}; the retired
     * {@code InlineApprovalPrompter} is where that read lived. <b>Any terminal read on the render
     * thread is a deadlock bug</b> — that discipline has not moved, and splitting the two makes it
     * structurally unbreakable rather than merely observed.
     *
     * <p>Showing the same request again is how "ask me again" is expressed: an empty submission
     * produces no decision, and an unrecognised one comes back with {@code notice} explaining why.
     */
    @Override
    public void showApproval(ApprovalRequest request, String notice) {
        if (terminal == null || liveStrip == null || renderLoop == null || request == null) {
            return;
        }
        ApprovalBlock existing = approvalCard;
        if (existing != null && existing.isFor(request)) {
            renderLoop.post(() -> existing.update(notice, ApprovalBlock.options(request)));
            return;
        }
        ApprovalBlock block = new ApprovalBlock(terminal, request);
        block.update(notice, ApprovalBlock.options(request));
        approvalCard = block;
        renderLoop.post(() -> liveStrip.showApproval(block));
    }

    @Override
    public void dismissApproval() {
        ApprovalBlock block = approvalCard;
        approvalCard = null;
        if (block != null && liveStrip != null && renderLoop != null) {
            renderLoop.post(() -> liveStrip.dismissApproval(block));
        }
    }

    /**
     * The synchronous port method, kept for the plain / degraded renderers that still own their own
     * terminal reading. Inline cannot honour it any more: the only consumer of terminal keys is the
     * resident line editor, and blocking a worker thread here for an answer that arrives through the
     * [[输入框]] is exactly the contention this issue removes. It draws the card and says so.
     */
    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null || liveStrip == null || renderLoop == null) {
            return fallback.promptApproval(request);
        }
        showApproval(request, "");
        return ApprovalResult.reject("审批经输入框应答（y / n / a），不再由渲染器同步读取按键");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        // The palette is a modal too: it prints its list at the cursor, reads keys, and erases itself
        // again, all from the input thread. A live-region frame written into the middle of that — and
        // a resize posts one regardless of idleness — would scroll the list out from under its own
        // erase (ADR 0010 amendment A12).
        try (ModalLease lease = leaseTerminalForModal()) {
            return new SlashPalette(out, terminal).open(title, items);
        }
    }

    /**
     * Hand the terminal to a modal until the returned lease is closed (ADR 0010 amendment A12).
     *
     * <p>Taken at a <b>frame boundary</b>: the render loop composes and writes one frame under the
     * same lock, so a frame already in flight finishes before the modal draws anything, and no frame
     * composed before the lease can be written after it. From here on frames write nothing at all, and
     * a geometry change is recorded but its [[destructive resize reset]] is deferred to the return —
     * exactly one if the geometry changed, none if it did not.
     */
    ModalLease leaseTerminalForModal() {
        if (liveStrip == null || renderLoop == null) {
            return () -> { };
        }
        renderLoop.atFrameBoundary(liveStrip::acquireModalLease);
        return () -> renderLoop.atFrameBoundary(liveStrip::releaseModalLease);
    }

    /** A modal's hold on the terminal; closing it hands the terminal back to the render loop. */
    interface ModalLease extends AutoCloseable {
        @Override
        void close();
    }

    /** 测试可见：当前实例是否启动了 status bar。 */
    public boolean hasStatusBar() {
        return statusBar != null;
    }

    /** Current render-loop counters; zeroed when the activity path is unavailable. */
    public RenderStats renderStats() {
        return renderLoop == null ? new RenderStats(0, 0, 0, 0, 0, 0, 0) : renderLoop.stats();
    }

    /** 测试 / Main.java 可见：拿到 terminal 用于其它 inline 组件。 */
    public Terminal terminal() {
        return terminal;
    }

    /**
     * Opens the [[转写浏览层]] over the retained session synchronously on the caller/input thread.
     *
     * <p>Two things are handed over: the ordered commit log — the whole session, not the newest five
     * turns (ADR 0010 amendment A10) — and the terminal itself, for as long as the overlay is up
     * (A12). The lease is what closes a defect predating this: the overlay installs no
     * suspend/restore pair, on the reasoning that its fullscreen Display already owns the screen,
     * which held only while nothing posted frames — and a resize posts one regardless of idleness.
     */
    public void openTranscript() {
        if (terminal == null || liveStrip == null || closed) {
            stream.println("Transcript overlay is unavailable in this renderer.");
            return;
        }
        LiveRegionStrip strip = liveStrip;
        GeometrySource sampled = strip.sampledGeometry();
        long browsingSince = approvalsRaised.get();
        try (ModalLease lease = leaseTerminalForModal()) {
            // The log is handed over as a live view, not a snapshot (ADR 0010 amendment A13): the
            // session keeps running while the overlay is up, and committing to the log sits above the
            // gate that stops the render loop writing, so the overlay's own poll is what shows it.
            transcriptPosition = new TranscriptOverlay(terminal, sampled, sessionInterrupt).open(
                    strip::retainedItems,
                    () -> approvalsRaised.get() != browsingSince,
                    transcriptPosition);
        }
    }

    /**
     * Told by the approval queue, at enqueue time, that an [[审批应答]] is waiting (ADR 0010
     * amendment A14).
     *
     * <p>This is the only thing that can say so: the requesting worker parks on a get with <b>no
     * timeout</b>, so an overlay holding the input thread would stall it for as long as the user keeps
     * reading, and from the agent's side the session would just stop with no visible cause. The
     * overlay closes and the user lands where the card and the [[输入框]] that answers it are; nothing
     * about the approval itself changes, including a background one's notification and Enter-to-drain.
     */
    /**
     * What a Ctrl+C read inside a modal means (ADR 0016 D3): stop the running turn, keep the screen.
     *
     * <p>Injected rather than known, because cancelling is a session concern and this renderer has no
     * {@code CancellationRegistry}. It is the same routine the session-level {@code Signal.INT} guard
     * runs in the gaps between reads; the [[转写浏览层]] needs its own hold of it because a resident
     * line editor shadows that guard everywhere (issue 07).
     */
    public void bindSessionInterrupt(Runnable interrupt) {
        this.sessionInterrupt = interrupt == null ? () -> { } : interrupt;
    }

    public void onApprovalPending() {
        approvalsRaised.incrementAndGet();
    }

    /** What the session has retained, for diagnostics and tests. */
    public RetainedLogSnapshot retainedLogSnapshot() {
        return new RetainedLogSnapshot(
                liveStrip == null ? 0 : liveStrip.replayLogSize(),
                liveStrip == null ? 0 : liveStrip.liveRenderCacheRows());
    }

    List<StripEntry> retainedItems() {
        return liveStrip == null ? List.of() : liveStrip.retainedItems();
    }

    /**
     * How much the session is holding: committed <b>positions</b>, and the rendered rows the [[活区]]
     * currently has cached for them.
     *
     * <p>The first number counts positions rather than entries (ADR 0016 D5): an answer interrupted by
     * a [[转向消息]] occupies two of them. That is the number that matters here — it is what the
     * [[bounded replay window]] and the [[转写浏览层]] iterate.
     *
     * <p>The second number is <b>per view</b> (ADR 0016 D1). Retained items hold no rendered rows at
     * all any more — laying an entry out belongs to whoever is showing it — so what this reports is
     * the live region's own cache, which is the streaming answers' projections. Items grow with the
     * session by design; cached rows must not.
     */
    public record RetainedLogSnapshot(int items, int liveRenderCacheRows) {
    }

    private PrintStream createTranscriptStream(PrintStream delegate) {
        return new PrintStream(new OutputStream() {
            private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            private ByteBuffer pendingBytes = ByteBuffer.allocate(256);

            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                synchronized (streamLock) {
                    ensureByteCapacity(len);
                    pendingBytes.put(b, off, len);
                    decodeAvailableText();
                }
            }

            private void ensureByteCapacity(int additionalBytes) {
                if (pendingBytes.remaining() >= additionalBytes) {
                    return;
                }
                pendingBytes.flip();
                int capacity = Math.max(pendingBytes.capacity() * 2,
                        pendingBytes.remaining() + additionalBytes);
                ByteBuffer expanded = ByteBuffer.allocate(capacity);
                expanded.put(pendingBytes);
                pendingBytes = expanded;
            }

            private void decodeAvailableText() {
                pendingBytes.flip();
                int charCapacity = Math.max(1,
                        (int) Math.ceil(pendingBytes.remaining() * decoder.maxCharsPerByte()));
                CharBuffer chars = CharBuffer.allocate(charCapacity);
                decoder.decode(pendingBytes, chars, false);
                pendingBytes.compact();
                chars.flip();
                if (chars.hasRemaining()) {
                    feedTranscriptText(chars.toString());
                }
            }

            @Override
            public void flush() {
                delegate.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void feedTranscriptText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            lineBuffer.append(ch);
            if (ch == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                emitTranscriptLine(line);
            }
        }
    }

    private void emitTranscriptLine(String line) {
        emit(line);
    }

    /**
     * The one configuration in which {@code emit} must not hand text to the [[活区]]: a terminal too
     * poor for {@link CafeCliLineReader}, whose stock line editor draws its own input line and can
     * only be asked to scroll text above it. When the live region owns the [[输入框]] — the normal
     * configuration — there is no such reader, so this returns {@code null} and the strip is the
     * single writer.
     *
     * <p>{@code isReading()} used to decide the routing of <b>all</b> streamed transcript text, and
     * that is the defect this narrowing fixes: outside {@code readLine} — which is most of a running
     * turn, and the whole of the resize-storm target — output went straight to the terminal from the
     * producer thread, never reaching {@link ReplayLog}. A [[destructive resize reset]] then had
     * nothing to repaint it from, so a resize destroyed the transcript outright.
     */
    private LineReader nativeReaderDrawingItsOwnInputLine() {
        LineReader reader = lineReader;
        if (reader == null || closed || inputLineOwnedByLiveRegion) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        LineReader reader = nativeReaderDrawingItsOwnInputLine();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        freezeExternalText(text);
    }

    private void writeDirect(String text) {
        synchronized (out) {
            if (terminalWritesQuiesced) {
                return;
            }
            replayRecorder.recordWrite(text);
            out.print(text);
            out.flush();
        }
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String block = String.join("\n", lines);
        return block.endsWith("\n") ? block : block + "\n";
    }

}
