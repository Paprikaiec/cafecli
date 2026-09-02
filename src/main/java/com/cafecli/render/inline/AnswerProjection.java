package com.cafecli.render.inline;

import com.cafecli.util.TerminalMarkdownRenderer;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.List;

/**
 * One <b>view's</b> incremental layout of one streamed answer at one width (ADR 0016 D1).
 *
 * <p>It owns everything that turns {@link AnswerSource} into rows at that width: the accumulating
 * {@code TerminalMarkdownRenderer} and the wrapped rows per styled line. None of it is shared. The
 * [[活区]] holds its own set on the render thread ({@link LiveAnswer}); the [[转写浏览层]] holds its
 * own on the input thread. Two views at the same width therefore lay the same source out twice — that
 * is the priced cost of the split, and it buys the render thread's state being unreachable from
 * anywhere else.
 *
 * <p>It is deliberately <b>not</b> thread-safe, because it is never reached from more than one
 * thread: whoever creates one owns it.
 *
 * <p>Syncing is forward-only and idempotent, so a caller may {@link #sync} on every frame or every
 * poll: only source lines it has not consumed yet cost anything.
 */
final class AnswerProjection {

    private final Terminal terminal;
    private final int width;
    private final Runnable cacheHitRecorder;
    private final AnswerProjections owner;
    private final TerminalMarkdownRenderer markdown;
    private final List<List<AttributedString>> wrappedCompletedLines = new ArrayList<>();
    private final List<Integer> styledCountAfterSourceLine = new ArrayList<>();
    private int consumedSourceLines;
    private boolean finishApplied;

    AnswerProjection(Terminal terminal, int width, Runnable cacheHitRecorder,
                     AnswerProjections owner) {
        this.terminal = terminal;
        this.width = Math.max(1, width);
        this.cacheHitRecorder = cacheHitRecorder == null ? () -> { } : cacheHitRecorder;
        this.owner = owner;
        this.markdown = new TerminalMarkdownRenderer(this.width);
    }

    /** Consume whatever {@code source} has gained since the last call. */
    void sync(AnswerSource source) {
        List<String> completedLines = source.completedLines();
        while (consumedSourceLines < completedLines.size()) {
            markdown.append(completedLines.get(consumedSourceLines) + "\n");
            consumedSourceLines++;
            styledCountAfterSourceLine.add(markdown.styledLineCount());
        }
        if (source.finished() && !finishApplied) {
            markdown.finish();
            finishApplied = true;
            // Whatever the flush emits belongs to the finishing step, which is a commit step of its
            // own (see commitSteps) rather than being charged back to the last source line: that
            // line may already be committed, and marking rows committed without ever writing them
            // loses them outright.
        }
        while (wrappedCompletedLines.size() < markdown.styledLineCount()) {
            wrappedCompletedLines.add(null);
        }
    }

    /**
     * How many commit steps this projection has: one per completed source line, plus the finishing
     * step where a trailing markdown block flushes.
     */
    int commitSteps() {
        return styledCountAfterSourceLine.size() + (finishApplied ? 1 : 0);
    }

    int styledLineCount() {
        return markdown.styledLineCount();
    }

    /**
     * The [[冻结边界]] — held in source coordinates by {@link AnswerSegment} (ADR 0010 amendment A11)
     * — expressed in this projection's styled lines.
     */
    int styledLinesAfter(int sourceLines) {
        if (sourceLines <= 0) {
            return 0;
        }
        if (sourceLines > styledCountAfterSourceLine.size()) {
            return markdown.styledLineCount();
        }
        return styledCountAfterSourceLine.get(sourceLines - 1);
    }

    List<AttributedString> completedRows(int styledLineIndex) {
        List<AttributedString> cached = wrappedCompletedLines.get(styledLineIndex);
        if (cached != null) {
            cacheHitRecorder.run();
            return cached;
        }
        AttributedString prose = owner.highlight(markdown.styledLine(styledLineIndex));
        List<AttributedString> rendered = ProseEntityHighlighter.wrap(prose, width, terminal);
        wrappedCompletedLines.set(styledLineIndex, rendered);
        return rendered;
    }

    /** The still-growing tail, previewed through markdown without committing it. */
    List<AttributedString> previewRows(String sourceTail) {
        List<AttributedString> rows = new ArrayList<>();
        for (AttributedString previewLine : markdown.previewStyledLines(sourceTail)) {
            rows.addAll(ProseEntityHighlighter.wrap(previewLine, width, terminal));
        }
        return rows;
    }

    /** Rendered rows this view is currently holding for the answer. */
    int cachedRows() {
        int cached = 0;
        for (List<AttributedString> rows : wrappedCompletedLines) {
            if (rows != null) {
                cached += rows.size();
            }
        }
        return cached;
    }
}
