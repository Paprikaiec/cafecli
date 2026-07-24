package com.paicli.render.inline;

import com.paicli.util.TerminalMarkdownRenderer;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/** Append-only source for one streamed answer block. */
final class AnswerSegment implements StripEntry {

    private final Terminal terminal;
    private final Runnable cacheHitRecorder;
    private final StringBuilder source = new StringBuilder();
    private final List<String> completedLines = new ArrayList<>();
    private final StringBuilder tail = new StringBuilder();
    private final Map<Integer, MarkdownProjection> projections = new HashMap<>();
    private final Map<AttributedString, AttributedString> highlightedProseCache = new HashMap<>();
    private int printedAboveChars;
    private int printedCompletedSourceLines;
    private int frozenStyledLines;
    private int proseHighlightPasses;
    private boolean previousWasCarriageReturn;
    private boolean finished;

    AnswerSegment(Terminal terminal, Runnable cacheHitRecorder) {
        this.terminal = terminal;
        this.cacheHitRecorder = cacheHitRecorder == null ? () -> { } : cacheHitRecorder;
    }

    void append(String delta) {
        if (delta != null && !delta.isEmpty()) {
            source.append(delta);
            appendLogicalLines(delta);
        }
    }

    void finish() {
        if (!tail.isEmpty()) {
            completeTail();
        }
        finished = true;
    }

    String drainPrintAbove() {
        if (printedAboveChars >= source.length()) {
            return "";
        }
        int end = finished ? source.length() : lastCompletedSourceOffset();
        if (end <= printedAboveChars) {
            return "";
        }
        String unread = source.substring(printedAboveChars, end);
        printedAboveChars = end;
        printedCompletedSourceLines = Math.max(printedCompletedSourceLines, completedLines.size());
        return unread;
    }

    @Override
    public String drainPrintAbove(Terminal terminal, int width) {
        return drainPrintAbove();
    }

    boolean fullyPrintedAndFinished() {
        return finished && printedAboveChars >= source.length();
    }

    /**
     * Like {@link #fullyPrintedAndFinished()} but also requires that at least one character was
     * actually flushed via {@link #drainPrintAbove()}. The strip uses this on the idle printAbove
     * (background-push) commit path to append exactly one trailing separator blank after a fully
     * printed answer, matching the blank every foldable emits. An answer that produced no text (a
     * round with only tool calls) reports {@code false} so no stray blank is printed above the prompt.
     */
    boolean isFullyPrintedWithContent() {
        return finished && printedAboveChars > 0 && printedAboveChars >= source.length();
    }

    boolean isComplete() {
        return finished;
    }

    /**
     * True once the segment is finished and every styled line it will ever produce has already been
     * committed to scrollback (a subsequent {@link #freezeTopRows} would yield nothing). The strip
     * uses this to append exactly one trailing separator blank after a fully-drained answer, matching
     * the blank every foldable already emits so answer→block boundaries are not collapsed to zero
     * blanks. An answer that finished without producing any styled line reports {@code false}: there
     * is nothing to separate, so no stray blank is emitted.
     */
    boolean isFullyFrozen(int width) {
        if (!finished) {
            return false;
        }
        MarkdownProjection projection = projection(width);
        projection.sync();
        int total = projection.styledLineCount();
        return total > 0 && frozenStyledLines >= total;
    }

    @Override
    public int renderCacheEntries() {
        return highlightedProseCache.size() + projections.values().stream()
                .mapToInt(MarkdownProjection::cacheEntryCount)
                .sum();
    }

    @Override
    public int releaseRenderCache() {
        int released = renderCacheEntries();
        projections.clear();
        highlightedProseCache.clear();
        return released;
    }

    int proseHighlightPasses() {
        return proseHighlightPasses;
    }

    private int lastCompletedSourceOffset() {
        int completedOffset = printedAboveChars;
        for (int i = printedAboveChars; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                completedOffset = i + 2;
                i++;
            } else if (ch == '\r' || ch == '\n') {
                completedOffset = i + 1;
            }
        }
        return completedOffset;
    }

    @Override
    public List<AttributedString> render(int width) {
        MarkdownProjection projection = projection(width);
        projection.sync();
        frozenStyledLines = Math.max(frozenStyledLines, projection.printedStyledLineCount());
        List<AttributedString> lines = new ArrayList<>();
        for (int i = Math.min(frozenStyledLines, projection.styledLineCount());
             i < projection.styledLineCount(); i++) {
            lines.addAll(projection.completedRows(i));
        }
        if (!tail.isEmpty()) {
            for (AttributedString previewLine : projection.preview(tail.toString())) {
                lines.addAll(renderLine(previewLine, width));
            }
        }
        return List.copyOf(lines);
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        MarkdownProjection projection = projection(width);
        projection.sync();
        List<AttributedString> lines = new ArrayList<>();
        for (int i = 0; i < projection.styledLineCount(); i++) {
            lines.addAll(projection.completedRows(i));
        }
        if (!tail.isEmpty()) {
            for (AttributedString previewLine : projection.preview(tail.toString())) {
                lines.addAll(renderLine(previewLine, width));
            }
        }
        return List.copyOf(lines);
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        if (minimumRows <= 0) {
            return List.of();
        }
        MarkdownProjection projection = projection(width);
        projection.sync();
        frozenStyledLines = Math.max(frozenStyledLines, projection.printedStyledLineCount());
        List<AttributedString> frozen = new ArrayList<>();
        while (frozenStyledLines < projection.styledLineCount() && frozen.size() < minimumRows) {
            frozen.addAll(projection.completedRows(frozenStyledLines));
            frozenStyledLines++;
        }
        return List.copyOf(frozen);
    }

    private void appendLogicalLines(String delta) {
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (ch == '\n' && previousWasCarriageReturn) {
                previousWasCarriageReturn = false;
                continue;
            }
            previousWasCarriageReturn = false;
            if (ch == '\r') {
                completeTail();
                previousWasCarriageReturn = true;
            } else if (ch == '\n') {
                completeTail();
            } else {
                tail.append(ch);
            }
        }
    }

    private void completeTail() {
        completedLines.add(tail.toString());
        tail.setLength(0);
    }

    private MarkdownProjection projection(int width) {
        int columns = Math.max(1, width);
        return projections.computeIfAbsent(columns, MarkdownProjection::new);
    }

    private List<AttributedString> renderLine(String sourceLine, int width) {
        return renderLine(new AttributedString(sourceLine), width);
    }

    private List<AttributedString> renderLine(AttributedString sourceLine, int width) {
        int columns = Math.max(1, width);
        return ProseEntityHighlighter.wrap(sourceLine, columns, terminal);
    }

    private final class MarkdownProjection {
        private final int width;
        private final TerminalMarkdownRenderer markdown;
        private final List<List<AttributedString>> wrappedCompletedLines = new ArrayList<>();
        private final List<Integer> styledCountAfterSourceLine = new ArrayList<>();
        private int consumedSourceLines;
        private boolean finishApplied;

        private MarkdownProjection(int width) {
            this.width = width;
            this.markdown = new TerminalMarkdownRenderer(width);
        }

        private void sync() {
            while (consumedSourceLines < completedLines.size()) {
                int before = markdown.styledLineCount();
                markdown.append(completedLines.get(consumedSourceLines) + "\n");
                consumedSourceLines++;
                int after = markdown.styledLineCount();
                attributeDeferredOutput(before, after);
                styledCountAfterSourceLine.add(after);
            }
            if (finished && !finishApplied) {
                int before = markdown.styledLineCount();
                markdown.finish();
                finishApplied = true;
                attributeDeferredOutput(before, markdown.styledLineCount());
                if (!styledCountAfterSourceLine.isEmpty()) {
                    styledCountAfterSourceLine.set(styledCountAfterSourceLine.size() - 1,
                            markdown.styledLineCount());
                }
            }
            while (wrappedCompletedLines.size() < markdown.styledLineCount()) {
                wrappedCompletedLines.add(null);
            }
        }

        private void attributeDeferredOutput(int before, int after) {
            if (after <= before) {
                return;
            }
            for (int i = styledCountAfterSourceLine.size() - 1;
                 i >= 0 && styledCountAfterSourceLine.get(i) == before; i--) {
                styledCountAfterSourceLine.set(i, after);
            }
        }

        private int styledLineCount() {
            return markdown.styledLineCount();
        }

        private List<AttributedString> completedRows(int styledLineIndex) {
            List<AttributedString> cached = wrappedCompletedLines.get(styledLineIndex);
            if (cached != null) {
                cacheHitRecorder.run();
                return cached;
            }
            AttributedString styled = markdown.styledLine(styledLineIndex);
            AttributedString prose = highlightedProseCache.get(styled);
            if (prose == null) {
                proseHighlightPasses++;
                prose = ProseEntityHighlighter.highlight(
                        styled,
                        TerminalCapabilities.supportsOsc8(terminal),
                        Path.of("").toAbsolutePath());
                highlightedProseCache.put(styled, prose);
            } else {
                cacheHitRecorder.run();
            }
            List<AttributedString> rendered = renderLine(
                    prose, width);
            wrappedCompletedLines.set(styledLineIndex, rendered);
            return rendered;
        }

        private List<AttributedString> preview(String sourceTail) {
            return markdown.previewStyledLines(sourceTail);
        }

        private int printedStyledLineCount() {
            int sourceCount = Math.min(printedCompletedSourceLines, styledCountAfterSourceLine.size());
            return sourceCount == 0 ? 0 : styledCountAfterSourceLine.get(sourceCount - 1);
        }

        private int cacheEntryCount() {
            int cachedRows = 0;
            for (List<AttributedString> rows : wrappedCompletedLines) {
                if (rows != null) {
                    cachedRows++;
                }
            }
            return cachedRows;
        }
    }
}
