package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-height transient activity model for model thinking.
 *
 * <p>Producer methods only enqueue mutations. {@link #renderFrame()} assembles the complete
 * terminal payload on the render-loop writer thread; this class never writes terminal bytes.
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.italic();
    private static final AttributedStyle QUOTE_STYLE = AttributedStyle.DEFAULT.faint().italic();

    private final Terminal terminal;
    private final FrameTicker ticker;
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder pendingOutput = new StringBuilder();
    private RenderLoop loop;
    private FrameTicker.Cancellable tickTask;
    private boolean ownsLoop;
    private volatile boolean activeRequested;
    private volatile boolean producerClosed;

    // Writer-thread-owned state.
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private long startedNanos;
    private int frame;
    private int renderedRows;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this(terminal, renderLock, null);
    }

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock, BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.ticker = tick -> () -> { };
        FramePump immediatePump = Runnable::run;
        this.loop = new RenderLoop(immediatePump, this::renderFrame,
                new TerminalFrameWriter(terminal, renderLock), false);
        this.ownsLoop = true;
    }

    InlineActivityDisplay(Terminal terminal, FrameTicker ticker) {
        this.terminal = terminal;
        this.ticker = ticker;
    }

    void bind(RenderLoop loop) {
        if (this.loop != null) {
            throw new IllegalStateException("Activity display already has a render loop");
        }
        this.loop = loop;
    }

    boolean isActive() {
        return activeRequested && !producerClosed;
    }

    void refreshIfActive() {
        if (isActive()) {
            loop.post(() -> { });
        }
    }

    void begin(String requestedLabel) {
        if (producerClosed) {
            return;
        }
        activeRequested = true;
        cancelTick();
        loop.post(() -> {
            reasoning.setLength(0);
            label = requestedLabel == null || requestedLabel.isBlank()
                    ? "Thinking"
                    : requestedLabel.trim();
            startedNanos = System.nanoTime();
            frame = 0;
            active = true;
        });
        tickTask = ticker.start(() -> {
            if (isActive()) {
                loop.post(() -> frame++);
            }
        });
    }

    void appendThinking(String delta) {
        if (producerClosed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!activeRequested) {
            begin("Thinking");
        }
        loop.postReasoning(delta.length(), () -> {
            reasoning.append(delta);
            trimReasoning();
        });
    }

    void end() {
        if (producerClosed) {
            return;
        }
        activeRequested = false;
        cancelTick();
        loop.post(() -> {
            active = false;
            reasoning.setLength(0);
        });
    }

    void appendOutput(String text) {
        if (producerClosed || text == null || text.isEmpty()) {
            return;
        }
        loop.post(() -> pendingOutput.append(text));
    }

    @Override
    public void close() {
        if (producerClosed) {
            return;
        }
        producerClosed = true;
        activeRequested = false;
        cancelTick();
        loop.post(() -> {
            active = false;
            closed = true;
            reasoning.setLength(0);
        });
        ticker.close();
        if (ownsLoop) {
            loop.close();
        }
    }

    String renderFrame() {
        StringBuilder payload = new StringBuilder();
        appendClearRenderedArea(payload);
        if (!active || closed) {
            appendPendingOutput(payload);
            return payload.toString();
        }
        List<AttributedString> lines = buildLines();
        for (int i = 0; i < lines.size(); i++) {
            payload.append(lines.get(i).toAnsi(terminal)).append(AnsiSeq.CLEAR_TO_EOL);
            if (i < lines.size() - 1) {
                payload.append('\n');
            }
        }
        renderedRows = lines.size();
        appendPendingOutput(payload);
        return payload.toString();
    }

    private void appendPendingOutput(StringBuilder payload) {
        if (pendingOutput.isEmpty()) {
            return;
        }
        if (!payload.isEmpty() && payload.charAt(payload.length() - 1) != '\n'
                && payload.charAt(payload.length() - 1) != '\r') {
            payload.append('\n');
        }
        payload.append(pendingOutput);
        pendingOutput.setLength(0);
    }

    private void cancelTick() {
        FrameTicker.Cancellable task = tickTask;
        tickTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void appendClearRenderedArea(StringBuilder payload) {
        if (renderedRows <= 0) {
            return;
        }
        if (renderedRows > 1) {
            payload.append(AnsiSeq.moveUp(renderedRows - 1));
        }
        payload.append('\r');
        for (int i = 0; i < renderedRows; i++) {
            payload.append(AnsiSeq.CLEAR_LINE);
            if (i < renderedRows - 1) {
                payload.append('\n');
            }
        }
        if (renderedRows > 1) {
            payload.append(AnsiSeq.moveUp(renderedRows - 1));
        }
        payload.append('\r');
        renderedRows = 0;
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(fit("  " + spinner() + " " + label + "... (esc to cancel, " + elapsedSeconds() + "s)",
                cols, STATUS_STYLE));

        List<String> quoteLines = reasoningLines();
        int quoteWidth = Math.max(12, cols - 4);
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            AttributedString quote = new AttributedString("│ " + quoteLines.get(i), QUOTE_STYLE);
            for (AttributedString part : quote.columnSplitLength(quoteWidth, true, true, terminal)) {
                lines.add(fit("  " + part.toString(), cols, QUOTE_STYLE));
                if (lines.size() > MAX_REASONING_ROWS + 1) {
                    return lines;
                }
            }
        }
        return lines;
    }

    private List<String> reasoningLines() {
        String content = reasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private AttributedString fit(String text, int cols, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= cols) {
            return attributed;
        }
        if (cols <= 3) {
            return new AttributedString(".".repeat(Math.max(0, cols)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, cols - 3, terminal).toString() + "...",
                style);
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }

    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }

    private void trimReasoning() {
        if (reasoning.length() > MAX_REASONING_CHARS) {
            reasoning.delete(0, reasoning.length() - MAX_REASONING_CHARS);
        }
    }
}
