package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only <b>source</b> for one streamed answer block, and nothing else (ADR 0016 D1).
 *
 * <p>It holds no rendered rows, no markdown renderer and no cache. Laying the source out at a width
 * is a view's job and the resulting {@link AnswerProjection} is that view's property: the [[活区]]
 * has one on the render thread ({@link LiveAnswer}), the [[转写浏览层]] has one on the input thread
 * ({@link AnswerRendering}). That is what makes A13's live follow safe — the input thread reads a
 * volatile immutable snapshot and touches nothing the render thread owns.
 *
 * <p>What does stay here is the [[冻结边界]] (ADR 0010 amendment A11) — how many <b>source</b> lines
 * have reached [[冻结回卷]]. It is a property of the answer, not of a layout: every projection maps
 * it into its own styled space, which is exactly what makes it survive a width change. It is written
 * by the render thread alone; the overlay renders the whole retained entry and never asks for it.
 */
final class AnswerSegment implements StripEntry {

    private final Terminal terminal;
    private final Runnable cacheHitRecorder;

    /**
     * The published source. Replaced, never mutated, so a reader on another thread sees a consistent
     * snapshot or an older consistent one — the volatile write/read pair is the happens-before edge.
     */
    private volatile AnswerSource source = AnswerSource.EMPTY;

    /** Writer-side scratch for the logical line being assembled. Producer thread only. */
    private final StringBuilder tail = new StringBuilder();
    private boolean previousWasCarriageReturn;

    /**
     * The freeze boundary, in <b>source</b> coordinates: how many source lines of this answer have
     * been committed to [[冻结回卷]] (ADR 0010 amendment A11). The last "source line" may be the
     * virtual finishing step, which is where a trailing markdown block flushes.
     *
     * <p>It is deliberately not a count of styled lines. The same source at a different width yields a
     * different number of styled lines — a table's row height is its tallest wrapped cell — so a styled
     * count carried across a width change over-counts and silently empties the live tail (measured: a
     * table committed at width 40 is 13 styled lines and only 7 at width 120, so at width 120 nothing
     * streamed after the resize was ever drawn again). Each projection maps this boundary into its own
     * styled space through the cumulative count it already records per source line.
     */
    private int frozenSourceLines;

    /** Read by the [[转写浏览层]] on the input thread, so published rather than merely written. */
    private volatile boolean separatorClaimed;

    AnswerSegment(Terminal terminal, Runnable cacheHitRecorder) {
        this.terminal = terminal;
        this.cacheHitRecorder = cacheHitRecorder == null ? () -> { } : cacheHitRecorder;
    }

    void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        AnswerSource growing = source;
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (ch == '\n' && previousWasCarriageReturn) {
                previousWasCarriageReturn = false;
                continue;
            }
            previousWasCarriageReturn = false;
            if (ch == '\r') {
                growing = completeTail(growing);
                previousWasCarriageReturn = true;
            } else if (ch == '\n') {
                growing = completeTail(growing);
            } else {
                tail.append(ch);
            }
        }
        source = growing.withTail(tail.toString());
    }

    void finish() {
        AnswerSource growing = source;
        if (!tail.isEmpty()) {
            growing = completeTail(growing);
        }
        source = growing.complete();
    }

    private AnswerSource completeTail(AnswerSource growing) {
        AnswerSource completed = growing.completing(tail.toString());
        tail.setLength(0);
        return completed;
    }

    boolean isComplete() {
        return source.finished();
    }

    /** A fresh, view-private set of projections of this answer — one per width the view draws at. */
    AnswerProjections newProjections() {
        return new AnswerProjections(terminal, cacheHitRecorder);
    }

    /**
     * Claim this segment's single trailing separator blank, once and only once.
     *
     * <p>True the first time the segment is finished and every styled line it will ever produce has
     * already been committed to scrollback (a subsequent {@link #freezeTopRows} would yield nothing).
     * The strip appends one blank on that frame, matching the blank every foldable already emits, so
     * answer→block boundaries are not collapsed to zero blanks. An answer that finished without
     * producing any styled line never claims one: there is nothing to separate.
     *
     * <p>The once-only flag lives here rather than in an identity set on the strip because the strip's
     * drain runs every frame and there is now exactly one commit path (ADR 0014 决策 5).
     */
    boolean claimTrailingSeparator(AnswerProjection projection) {
        AnswerSource snapshot = source;
        if (separatorClaimed || !snapshot.finished()) {
            return false;
        }
        projection.sync(snapshot);
        int total = projection.styledLineCount();
        if (total > 0 && projection.styledLinesAfter(frozenSourceLines) >= total) {
            separatorClaimed = true;
            return true;
        }
        return false;
    }

    /**
     * The part of this answer that has already been committed to [[冻结回卷]], laid out through
     * {@code projection}. A [[destructive resize reset]] repaints exactly this; the rest is still in
     * the live tail and is drawn there, so the two never overlap and never leave a hole.
     *
     * <p>The boundary is mapped into <em>that</em> width's projection rather than reused as a row
     * count, which is what makes a new [[geometry epoch]] re-derive it instead of inheriting it.
     */
    List<AttributedString> committedRows(AnswerProjection projection) {
        return committedRows(projection, 0, CommittedSpan.OPEN);
    }

    /**
     * The committed rows for source lines {@code [from, to)} — one <b>position</b> of this answer in
     * the ordered commit log (ADR 0016 D5). {@code to == }{@link CommittedSpan#OPEN} means "as far as
     * this answer has committed", which is the still-growing last position.
     *
     * <p>Generalising the lower bound is the whole of the entry-side change: the coordinates are the
     * same source coordinates A11 already holds the boundary in, so a position survives a width change
     * exactly as the boundary does.
     */
    List<AttributedString> committedRows(AnswerProjection projection, int from, int to) {
        AnswerSource snapshot = source;
        projection.sync(snapshot);
        int upper = to == CommittedSpan.OPEN ? frozenSourceLines : to;
        return styledRange(projection, from, upper);
    }

    private static List<AttributedString> styledRange(AnswerProjection projection,
                                                      int fromSourceLine,
                                                      int toSourceLine) {
        int first = projection.styledLinesAfter(Math.max(0, fromSourceLine));
        int last = projection.styledLinesAfter(Math.max(0, toSourceLine));
        List<AttributedString> rows = new ArrayList<>();
        for (int i = first; i < last; i++) {
            rows.addAll(projection.completedRows(i));
        }
        return List.copyOf(rows);
    }

    /** What the [[活区]] still has to draw: everything past the boundary, plus the growing tail. */
    List<AttributedString> liveTailRows(AnswerProjection projection) {
        AnswerSource snapshot = source;
        projection.sync(snapshot);
        List<AttributedString> lines = new ArrayList<>();
        for (int i = projection.styledLinesAfter(frozenSourceLines);
                i < projection.styledLineCount(); i++) {
            lines.addAll(projection.completedRows(i));
        }
        if (!snapshot.tail().isEmpty()) {
            lines.addAll(projection.previewRows(snapshot.tail()));
        }
        return List.copyOf(lines);
    }

    /** The whole answer, boundary ignored: what a retained viewer such as the overlay shows. */
    List<AttributedString> retainedRows(AnswerProjection projection) {
        return retainedRows(projection, 0, CommittedSpan.OPEN);
    }

    /**
     * The retained rows for source lines {@code [from, to)} — one position of this answer as the
     * [[转写浏览层]] shows it (ADR 0016 D5).
     *
     * <p>Unlike {@link #committedRows(AnswerProjection, int, int)} the open-ended last position runs to
     * the end of everything markdown has produced <b>plus the still-growing tail</b>, because the
     * overlay follows a running turn live and shows the answer as it stands, not as it has committed.
     */
    List<AttributedString> retainedRows(AnswerProjection projection, int from, int to) {
        AnswerSource snapshot = source;
        projection.sync(snapshot);
        if (to != CommittedSpan.OPEN) {
            return styledRange(projection, from, to);
        }
        List<AttributedString> lines = new ArrayList<>();
        for (int i = projection.styledLinesAfter(Math.max(0, from));
                i < projection.styledLineCount(); i++) {
            lines.addAll(projection.completedRows(i));
        }
        if (!snapshot.tail().isEmpty()) {
            lines.addAll(projection.previewRows(snapshot.tail()));
        }
        return List.copyOf(lines);
    }

    /**
     * Commit source lines until at least {@code minimumRows} rows have been produced, and hand back
     * exactly the rows committed.
     *
     * <p>Advancing happens in <b>source</b> coordinates, and the boundary is only ever left at a
     * position where markdown has actually emitted something. That is what makes it mean the same thing
     * at every width: a block emits nothing until it closes — a table's rows appear only once the block
     * ends — so a boundary inside an unclosed block would own rows that no projection can place. Where
     * the flushes happen is a property of the source, not of the width; only how many rows they produce
     * is width-dependent. So source lines that produced nothing are stepped over as part of committing
     * the line that flushed them, and the loop never advances while nothing has been emitted at all.
     */
    List<AttributedString> freezeTopRows(int minimumRows, AnswerProjection projection) {
        if (minimumRows <= 0) {
            return List.of();
        }
        projection.sync(source);
        List<AttributedString> frozen = new ArrayList<>();
        while (frozenSourceLines < projection.commitSteps()
                && projection.styledLinesAfter(frozenSourceLines) < projection.styledLineCount()
                && frozen.size() < minimumRows) {
            int from = projection.styledLinesAfter(frozenSourceLines);
            do {
                frozenSourceLines++;
            } while (frozenSourceLines < projection.commitSteps()
                    && projection.styledLinesAfter(frozenSourceLines) == from);
            for (int i = from; i < projection.styledLinesAfter(frozenSourceLines); i++) {
                frozen.addAll(projection.completedRows(i));
            }
        }
        return List.copyOf(frozen);
    }

    @Override
    public List<AttributedString> render(int width) {
        // Never live as itself: the [[活区]] draws the tail through its own view (LiveAnswer), which
        // owns the projection. What is retained in the ordered commit log is this bare source.
        return List.of();
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return retainedRows(newProjections().at(width));
    }

    @Override
    public List<AttributedString> replayRows(int width) {
        return committedRows(newProjections().at(width));
    }

    /**
     * A streamed answer is the one entry that reaches [[冻结回卷]] a piece at a time, so it is the one
     * entry that can occupy more than one position in the ordered commit log (ADR 0016 D5).
     */
    @Override
    public boolean commitsIncrementally() {
        return true;
    }

    @Override
    public int committedSourceLines() {
        return frozenSourceLines;
    }

    @Override
    public List<AttributedString> renderRetained(int width, CommittedSpan span) {
        return retainedRows(newProjections().at(width), span.from(), span.to());
    }

    @Override
    public List<AttributedString> renderRetained(int width, FoldState foldState, CommittedSpan span) {
        return renderRetained(width, span);
    }

    @Override
    public List<AttributedString> replayRows(int width, CommittedSpan span) {
        return committedRows(newProjections().at(width), span.from(), span.to());
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        // Freezing advances the boundary, which is the live view's business; it supplies its own
        // projection through the overload above.
        return List.of();
    }

    @Override
    public boolean isReplaySealed() {
        return separatorClaimed;
    }

    @Override
    public void sealForReplay() {
        separatorClaimed = true;
    }

    /**
     * A streamed answer is the one retained entry whose height moves while something is showing it: it
     * grows with every delta, and gains its separator blank when it claims one.
     */
    @Override
    public boolean isStillCommitting() {
        return !separatorClaimed;
    }

    @Override
    public EntryRendering newRendering() {
        return new AnswerRendering(this, null);
    }

    @Override
    public EntryRendering newRendering(CommittedSpan span) {
        return new AnswerRendering(this, span);
    }

    /**
     * A view's own incremental rendering of one answer — a projection per width, and nothing shared
     * with any other view.
     *
     * <p>It is incremental rather than a plain row cache because the entry it renders is the one that
     * keeps growing: a cache keyed by width alone would miss on every poll while the answer streams,
     * and re-running markdown over the whole answer every 50 ms is exactly the cost the split is
     * supposed to stay clear of.
     */
    private static final class AnswerRendering implements EntryRendering {

        private final AnswerSegment segment;
        private final AnswerProjections projections;
        /**
         * The position being rendered, or {@code null} for the whole answer. It is held rather than
         * copied because an open position closes when something else is committed, and the rows this
         * view shows have to follow it down at that moment.
         */
        private final CommittedSpan span;

        private AnswerRendering(AnswerSegment segment, CommittedSpan span) {
            this.segment = segment;
            this.projections = segment.newProjections();
            this.span = span;
        }

        @Override
        public List<AttributedString> rows(int width, FoldState foldState) {
            AnswerProjection projection = projections.at(width);
            return span == null
                    ? segment.retainedRows(projection)
                    : segment.retainedRows(projection, span.from(), span.to());
        }

        @Override
        public int cachedRows() {
            return projections.cachedRows();
        }
    }
}
