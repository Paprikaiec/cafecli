package com.cafecli.render.inline;

import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.PlainRenderer;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
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
    private final RetainedWindow retainedWindow;
    private final RenderLoop renderLoop;
    private final FramePump framePump;
    private final FrameTicker frameTicker;
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private final AtomicReference<StatusInfo> pendingStatus = new AtomicReference<>();
    private final AtomicBoolean statusEventQueued = new AtomicBoolean();
    private volatile LineReader lineReader;
    private Terminal.SignalHandler previousWinchHandler;
    private Terminal.SignalHandler installedWinchHandler;
    private boolean winchHandlerInstalled;
    private volatile boolean started;
    private volatile boolean closed;

    // Unstructured stream text is line-buffered so printAbove receives complete lines.
    private final StringBuilder lineBuffer = new StringBuilder();

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    InlineRenderer(Terminal terminal, PrintStream out) {
        this(terminal, out, new ScheduledFramePump(), new ScheduledFrameTicker());
    }

    /** Deterministic test seam for frame scheduling and spinner ticks. */
    InlineRenderer(Terminal terminal, PrintStream out, FramePump framePump, FrameTicker frameTicker) {
        this(terminal, out, framePump, frameTicker,
                TerminalCapabilities.supportsScrollRegion(terminal)
                        ? new BottomStatusBar(terminal, out)
                        : null);
    }

    /** Deterministic JLine status boundary seam. */
    InlineRenderer(Terminal terminal,
                   PrintStream out,
                   FramePump framePump,
                   FrameTicker frameTicker,
                   BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.framePump = framePump;
        this.frameTicker = frameTicker;
        this.statusBar = statusBar;
        if (statusBar == null) {
            this.activityBlock = null;
            this.liveStrip = null;
            this.retainedWindow = null;
            this.renderLoop = null;
        } else {
            ActivityBlock activity = new ActivityBlock(terminal, frameTicker);
            LiveRegionStrip strip = new LiveRegionStrip(terminal);
            RenderLoop loop = new RenderLoop(framePump,
                    strip::renderFrame,
                    new TerminalFrameWriter(terminal, out),
                    TerminalCapabilities.supportsSynchronizedOutput(terminal));
            activity.bind(loop);
            strip.bind(loop);
            strip.bindLeadingEntry(activity);
            // Size the live strip against the dock's REAL reserved-row count (Status height) instead
            // of a hardcoded guess, so its Display never overruns the bottom scroll region.
            strip.bindReservedRows(statusBar::reservedRows);
            this.activityBlock = activity;
            this.liveStrip = strip;
            this.retainedWindow = new RetainedWindow();
            this.renderLoop = loop;
        }
        this.stream = createTranscriptStream(out);
    }

    @Override
    public void beginTurn() {
        if (renderLoop != null) {
            renderLoop.resetStats();
            renderLoop.post(() -> liveStrip.evict(retainedWindow.beginTurn()));
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
        if (liveStrip != null && retainedWindow != null && renderLoop != null) {
            renderLoop.post(() -> {
                retainedWindow.clear();
                liveStrip.clear();
            });
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
                        renderLoop.post(() -> { });
                    }
                };
                previousWinchHandler = terminal.handle(Terminal.Signal.WINCH, installedWinchHandler);
                winchHandlerInstalled = true;
            } catch (RuntimeException ignored) {
                // Some fallback terminals do not expose signal handling.
            }
        }
        started = true;
    }

    @Override
    public void beforeInput() {
        if (statusBar != null) {
            statusBar.prepareInputLine();
            statusBar.flushNow();
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.finishInputLine();
        }
    }

    @Override
    public String inputPrompt() {
        return "* ";
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
                renderLoop.post(() -> {
                    liveStrip.add(block);
                    retainedWindow.add(block);
                });
            }
        };
    }

    @Override
    public BlockWriter beginAnswer() {
        if (liveStrip == null || renderLoop == null) {
            return Renderer.super.beginAnswer();
        }
        AnswerSegment answer = new AnswerSegment(terminal, renderLoop::recordCacheHit);
        renderLoop.post(() -> {
            liveStrip.add(answer);
            retainedWindow.add(answer);
        });
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
     * <p>绑定后，用户正在输入时的异步输出会优先通过
     * {@link LineReader#printAbove(String)} 显示在输入行上方；非读取态和
     * 测试/降级路径继续使用构造时注入的 {@link PrintStream}。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
        if (liveStrip != null) {
            liveStrip.bindLineReader(lineReader);
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

    /**
     * 清掉 JLine accept 后留在屏幕上的编辑态输入行。
     *
     * <p>普通任务会随后以 {@code > prompt} 的 transcript 块写回；这里清理的是编辑态
     * {@code * prompt}，避免同一条输入在屏幕上出现两次。
     */
    public void clearAcceptedInput(String input) {
        if (terminal == null || closed) {
            return;
        }
        int rows = acceptedInputRows(input);
        synchronized (out) {
            PrintWriter writer = terminal.writer();
            if (writer != null) {
                writer.print(clearAcceptedInputSequence(rows));
                writer.flush();
            } else {
                out.print(clearAcceptedInputSequence(rows));
                out.flush();
            }
            terminal.flush();
        }
    }

    @Override
    public void appendUserPrompt(String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        String rendered = AnsiStyle.userMessageBlock(visible, cols) + "\n";
        if (liveStrip != null && renderLoop != null) {
            renderLoop.post(() -> {
                retainedWindow.setPrompt(visible);
                liveStrip.submitPrompt(rendered);
            });
        } else {
            emit(rendered);
        }
    }

    public void printSubmittedPrompt(String input) {
        appendUserPrompt(input);
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        if (liveStrip != null && renderLoop != null) {
            ToolCallBlock block = new ToolCallBlock(terminal, toolCalls);
            renderLoop.post(() -> {
                liveStrip.add(block);
                retainedWindow.add(block);
            });
            return;
        }
        fallback.appendToolCalls(toolCalls);
    }

    @Override
    public void appendCodeBlock(String language, String code) {
        if (liveStrip != null && renderLoop != null) {
            CodeBlock block = new CodeBlock(terminal, language, code);
            renderLoop.post(() -> {
                liveStrip.add(block);
                retainedWindow.add(block);
            });
            return;
        }
        Renderer.super.appendCodeBlock(language, code);
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        if (liveStrip != null && renderLoop != null) {
            DiffBlock block = new DiffBlock(terminal, filePath, before, after);
            renderLoop.post(() -> {
                liveStrip.add(block);
                retainedWindow.add(block);
            });
            return;
        }
        fallback.appendDiff(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar == null || closed || status == null) {
            return;
        }
        if (renderLoop != null) {
            pendingStatus.updateAndGet(previous -> BottomStatusBar.mergeEnvironment(status, previous));
            queueLatestStatus();
        } else {
            statusBar.update(status);
        }
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

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null || liveStrip == null || renderLoop == null) {
            return fallback.promptApproval(request);
        }
        ApprovalBlock block = new ApprovalBlock(terminal, request);
        renderLoop.post(() -> liveStrip.showApproval(block));
        try {
            if (!block.awaitFirstRenderedFrame(5, TimeUnit.SECONDS)) {
                return ApprovalResult.reject("approval prompt did not render");
            }
            InlineApprovalPrompter reader = new InlineApprovalPrompter(terminal,
                    (notice, prompt) -> renderLoop.post(() -> block.update(notice, prompt)));
            return reader.prompt(request);
        } finally {
            renderLoop.post(() -> liveStrip.dismissApproval(block));
        }
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(out, terminal).open(title, items);
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

    /** Opens the retained transcript overlay synchronously on the caller/input thread. */
    public void openTranscript() {
        if (terminal == null || retainedWindow == null || closed) {
            stream.println("Transcript overlay is unavailable in this renderer.");
            return;
        }
        // Suspend the bottom dock so its scroll region does not confine the overlay's fullscreen
        // Display; always restore it once the overlay closes (small-terminal path has no dock).
        if (statusBar != null) {
            statusBar.suspend();
        }
        try {
            new TranscriptOverlay(terminal).open(retainedWindow.views());
        } finally {
            if (statusBar != null) {
                statusBar.restore();
            }
        }
    }

    /** Immutable retained-turn view for transcript consumers and diagnostics. */
    public RetainedWindowSnapshot retainedWindowSnapshot() {
        List<RetainedTurnSnapshot> turns = retainedWindow == null
                ? List.of() : retainedWindow.snapshot();
        return new RetainedWindowSnapshot(turns,
                retainedWindow == null ? 0 : retainedWindow.renderCacheEntries(),
                retainedWindow == null ? 0 : retainedWindow.releasedRenderCaches());
    }

    List<RetainedWindow.TurnView> retainedTurns() {
        return retainedWindow == null ? List.of() : retainedWindow.views();
    }

    public record RetainedWindowSnapshot(List<RetainedTurnSnapshot> turns,
                                         int renderCacheEntries,
                                         int releasedRenderCaches) {
        public RetainedWindowSnapshot {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    public record RetainedTurnSnapshot(String userPrompt, int blockCount) {
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

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || closed) {
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
        LineReader reader = activePrintAboveReader();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        out.print(text);
        out.flush();
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String block = String.join("\n", lines);
        return block.endsWith("\n") ? block : block + "\n";
    }

    private int acceptedInputRows(String input) {
        int cols = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
        String text = input == null ? "" : input;
        return Math.max(1, AttributedString.fromAnsi(inputPrompt() + text)
                .columnSplitLength(cols, true, true, terminal)
                .size());
    }

    static String clearAcceptedInputSequence(int rows) {
        int count = Math.max(1, rows);
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiSeq.moveUp(count)).append('\r');
        for (int i = 0; i < count; i++) {
            sb.append(AnsiSeq.CLEAR_LINE);
            if (i < count - 1) {
                sb.append('\n');
            }
        }
        if (count > 1) {
            sb.append(AnsiSeq.moveUp(count - 1));
        }
        sb.append('\r');
        return sb.toString();
    }

}
