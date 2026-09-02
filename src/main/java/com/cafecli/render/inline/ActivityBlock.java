package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Transient thinking block whose mutations and rendering are owned by the single render loop. */
final class ActivityBlock implements StripEntry, AutoCloseable {

    private static final int MAX_REASONING_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final String[] SPINNER_FRAMES =
            {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.italic();
    private static final AttributedStyle QUOTE_STYLE = AttributedStyle.DEFAULT.faint().italic();

    private final Terminal terminal;
    private final FrameTicker ticker;
    private final ReasoningPreview reasoning = new ReasoningPreview(MAX_REASONING_CHARS);
    private final StringBuilder pendingOutput = new StringBuilder();
    private RenderLoop loop;
    private FrameTicker.Cancellable tickTask;
    private volatile boolean activeRequested;
    private volatile boolean producerClosed;

    // Render-thread-owned state.
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private long startedNanos;
    private int frame;

    ActivityBlock(Terminal terminal, FrameTicker ticker) {
        this.terminal = terminal;
        this.ticker = ticker;
    }

    void bind(RenderLoop renderLoop) {
        if (loop != null) {
            throw new IllegalStateException("Activity block already has a render loop");
        }
        loop = renderLoop;
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
            reasoning.clear();
            label = requestedLabel == null || requestedLabel.isBlank()
                    ? "Thinking" : requestedLabel.trim();
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
        loop.postReasoning(delta, reasoning::append);
    }

    void end() {
        if (producerClosed) {
            return;
        }
        activeRequested = false;
        cancelTick();
        loop.post(() -> {
            active = false;
            reasoning.clear();
        });
    }

    void appendOutput(String text) {
        if (producerClosed || text == null || text.isEmpty()) {
            return;
        }
        loop.post(() -> pendingOutput.append(text));
    }

    @Override
    public List<AttributedString> render(int width) {
        List<AttributedString> lines = active && !closed
                ? new ArrayList<>(buildLines(width)) : new ArrayList<>();
        if (!pendingOutput.isEmpty()) {
            lines.addAll(AttributedString.fromAnsi(pendingOutput.toString())
                    .columnSplitLength(Math.max(1, width), true, true, terminal));
            pendingOutput.setLength(0);
        }
        return List.copyOf(lines);
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        return List.of();
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
            reasoning.clear();
        });
        ticker.close();
    }

    /**
     * Stop every activity producer without posting the usual final mutation that removes the live
     * block. The replay harness needs the last fully written frame to remain on Terminal.app's screen
     * while its history is captured.
     */
    void quiescePreservingFrame() {
        if (producerClosed) {
            return;
        }
        producerClosed = true;
        activeRequested = false;
        cancelTick();
        ticker.close();
    }

    private List<AttributedString> buildLines(int width) {
        int columns = Math.max(20, width - 1);
        List<AttributedString> lines = new ArrayList<>();
        lines.add(fit("  " + spinner() + " " + label + "... (esc to cancel, "
                + elapsedSeconds() + "s)", columns, STATUS_STYLE));

        List<String> quoteLines = reasoning.lines();
        int quoteWidth = Math.max(12, columns - 4);
        int start = Math.max(0, quoteLines.size() - MAX_REASONING_ROWS);
        for (int i = start; i < quoteLines.size(); i++) {
            AttributedString quote = new AttributedString("│ " + quoteLines.get(i), QUOTE_STYLE);
            for (AttributedString part : quote.columnSplitLength(quoteWidth, true, true, terminal)) {
                lines.add(fit("  " + part, columns, QUOTE_STYLE));
                if (lines.size() > MAX_REASONING_ROWS + 1) {
                    return lines;
                }
            }
        }
        return lines;
    }

    private AttributedString fit(String text, int columns, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= columns) {
            return attributed;
        }
        if (columns <= 3) {
            return new AttributedString(".".repeat(Math.max(0, columns)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, columns - 3, terminal) + "...", style);
    }

    private void cancelTick() {
        FrameTicker.Cancellable task = tickTask;
        tickTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }

    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }
}
