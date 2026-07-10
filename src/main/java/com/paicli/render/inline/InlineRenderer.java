package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final BlockRegistry blockRegistry;
    private final PrintStream stream;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final InlineActivityDisplay activityDisplay;
    private final RenderLoop renderLoop;
    private final FramePump framePump;
    private final FrameTicker frameTicker;
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private volatile LineReader lineReader;
    private int renderedRows;
    private boolean redrawing;
    private volatile boolean started;
    private volatile boolean closed;

    // Unstructured stream text is line-buffered only for transcript redraws.
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
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.framePump = framePump;
        this.frameTicker = frameTicker;
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal, out)
                : null;
        if (statusBar == null) {
            this.activityDisplay = null;
            this.renderLoop = null;
        } else {
            InlineActivityDisplay display = new InlineActivityDisplay(terminal, frameTicker);
            RenderLoop loop = new RenderLoop(framePump, display::renderFrame,
                    new TerminalFrameWriter(terminal, out),
                    TerminalCapabilities.supportsSynchronizedOutput(terminal));
            display.bind(loop);
            this.activityDisplay = display;
            this.renderLoop = loop;
        }
        this.blockRegistry = new BlockRegistry();
        this.stream = createTranscriptStream(out);
    }

    @Override
    public void beginTurn() {
        if (renderLoop != null) {
            renderLoop.resetStats();
        }
        synchronized (transcriptLock) {
            transcript.clear();
            renderedRows = 0;
            lineBuffer.setLength(0);
        }
        blockRegistry.clear();
    }

    @Override
    public void endTurn() {
        if (!Boolean.parseBoolean(System.getProperty("paicli.render.stats", "false"))) {
            return;
        }
        if (activityDisplay != null && renderLoop != null) {
            activityDisplay.appendOutput(renderLoop.stats().formatLine() + "\n");
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
        if (activityDisplay != null) {
            activityDisplay.close();
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
        return activityDisplay != null;
    }

    @Override
    public void beginThinking(String label) {
        if (activityDisplay != null && !closed) {
            activityDisplay.begin(label);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityDisplay != null && !closed) {
            activityDisplay.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (activityDisplay != null) {
            activityDisplay.end();
        }
    }

    @Override
    public BlockWriter beginReasoning() {
        if (activityDisplay == null) {
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
                appendCompletedReasoning(source.toString());
            }
        };
    }

    private void appendCompletedReasoning(String source) {
        String reasoning = source == null ? "" : source
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (reasoning.isEmpty()) {
            return;
        }
        StringBuilder rendered = new StringBuilder()
                .append(AnsiStyle.thinking("Thinking..."))
                .append('\n');
        for (String line : reasoning.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                rendered.append(AnsiStyle.subtle("│ " + normalized)).append('\n');
            }
        }
        rendered.append('\n');
        String text = rendered.toString();
        synchronized (transcriptLock) {
            transcript.add(new TextEntry(text));
            renderedRows += estimateRows(text);
            emit(text);
        }
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
        emit(AnsiStyle.userMessageBlock(visible, cols) + "\n");
    }

    public void printSubmittedPrompt(String input) {
        appendUserPrompt(input);
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallRenderer.group(toolCalls);
        FoldableBlock block = new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped));
        blockRegistry.register(block);
        TranscriptEntry entry = new BlockEntry(block);
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            renderedRows += estimateRows(rendered);
            emit(rendered);
        }
    }

    @Override
    public void appendCodeBlock(String language, String code) {
        String normalizedLanguage = language == null ? "" : language.trim();
        String normalizedCode = code == null ? "" : code.replace("\r\n", "\n").replace('\r', '\n');
        List<String> bodyLines = new ArrayList<>(List.of(normalizedCode.split("\n", -1)));
        if (!bodyLines.isEmpty() && bodyLines.get(bodyLines.size() - 1).isEmpty()
                && normalizedCode.endsWith("\n")) {
            bodyLines.remove(bodyLines.size() - 1);
        }
        if (bodyLines.size() == 1 && bodyLines.get(0).isEmpty() && normalizedCode.isEmpty()) {
            bodyLines.clear();
        }

        String label = normalizedLanguage.isEmpty() ? "code" : "code: " + normalizedLanguage;
        String collapsedHeader = AnsiStyle.subtle(
                "⏵ " + label + " (" + bodyLines.size() + " 行, ctrl+o to expand)");
        List<String> expandedLines = new ArrayList<>();
        expandedLines.add(AnsiStyle.codeLabel("┌─ " + label));
        for (String bodyLine : bodyLines) {
            expandedLines.add("    " + bodyLine);
        }
        expandedLines.add(AnsiStyle.codeLabel("└─ end"));

        FoldableBlock block = new FoldableBlock(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
        blockRegistry.register(block);
        BlockEntry entry = new BlockEntry(block);
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            transcript.add(new TextEntry("\n"));
            renderedRows += estimateRows(rendered + "\n");
            emit(rendered + "\n");
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(out).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar == null) {
            return;
        }
        if (renderLoop != null) {
            renderLoop.post(() -> statusBar.update(status));
        } else {
            statusBar.update(status);
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        return new InlineApprovalPrompter(out, terminal).prompt(request);
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

    /** Main.java 用：把 Ctrl+O 绑定到 toggleLast。 */
    public BlockRegistry blockRegistry() {
        return blockRegistry;
    }

    /** Main.java 用：Ctrl+O 触发内存态切换，然后重绘本轮 transcript。 */
    public boolean toggleLastBlock() {
        boolean changed = blockRegistry.toggleLastForRedraw();
        if (changed) {
            redrawTranscript();
        }
        return changed;
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
                synchronized (transcriptLock) {
                    if (redrawing) {
                        // Transcript redraw already selected each entry's visible form.
                        delegate.write(b, off, len);
                        return;
                    }
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
        transcript.add(new TextEntry(line));
        renderedRows += estimateRows(line);
    }

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || redrawing || closed) {
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
        String[] parts = text.split("\\R", -1);
        int rows = 0;
        for (int i = 0; i < parts.length; i++) {
            int cells = displayWidth(parts[i]) + (i == 0 ? displayWidth(inputPrompt()) : 0);
            rows += Math.max(1, (cells + cols - 1) / cols);
        }
        return Math.max(1, rows);
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

    private static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += isWideCodePoint(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWideCodePoint(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0xFF01 && cp <= 0xFF60);
    }

    private void redrawTranscript() {
        synchronized (transcriptLock) {
            if (transcript.isEmpty()) {
                return;
            }
            LineReader reader = activePrintAboveReader();
            if (reader != null) {
                StringBuilder snapshot = new StringBuilder();
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    snapshot.append(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                reader.printAbove(snapshot.toString());
                return;
            }
            redrawing = true;
            try {
                int rows = TerminalCapabilities.safeSize(terminal).getRows();
                int maxMove = Math.max(1, rows - (statusBar == null ? 1 : 2));
                int move = Math.min(renderedRows, maxMove);
                if (move > 0) {
                    out.print(AnsiSeq.moveUp(move));
                }
                out.print("\r");
                out.print(AnsiSeq.CLEAR_TO_EOS);
                int rowsAfter = 0;
                for (TranscriptEntry entry : transcript) {
                    String rendered = entry.render();
                    out.print(rendered);
                    rowsAfter += estimateRows(rendered);
                }
                renderedRows = rowsAfter;
                out.flush();
            } finally {
                redrawing = false;
            }
        }
    }

    private int estimateRows(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = 0;
        int col = 0;
        boolean sawVisible = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u001B') {
                i = skipAnsi(text, i);
                continue;
            }
            if (ch == '\r') {
                col = 0;
                continue;
            }
            if (ch == '\n') {
                rows++;
                col = 0;
                sawVisible = false;
                continue;
            }
            int width = displayWidth(ch);
            if (width <= 0) {
                continue;
            }
            sawVisible = true;
            col += width;
            if (col >= cols) {
                rows++;
                col = 0;
                sawVisible = false;
            }
        }
        if (sawVisible) {
            rows++;
        }
        return rows;
    }

    private int skipAnsi(String text, int escIndex) {
        int i = escIndex + 1;
        if (i < text.length() && text.charAt(i) == '[') {
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c >= '@' && c <= '~') {
                    return i;
                }
                i++;
            }
        }
        return escIndex;
    }

    private int displayWidth(char ch) {
        if (Character.isISOControl(ch)) {
            return 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA) {
            return 2;
        }
        return 1;
    }

    private interface TranscriptEntry {
        String render();
    }

    private record TextEntry(String text) implements TranscriptEntry {
        @Override
        public String render() {
            return text;
        }
    }

    private record BlockEntry(FoldableBlock block) implements TranscriptEntry {
        @Override
        public String render() {
            return String.join(System.lineSeparator(), block.currentLines()) + System.lineSeparator();
        }
    }
}
