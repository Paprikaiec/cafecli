package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.List;

/**
 * One ordered entry in the bounded live strip, and — once committed — in the ordered commit log.
 *
 * <p><b>An entry is immutable source</b> (ADR 0016 D1). Laying it out at a width, and any cache kept
 * to avoid doing that twice, belongs to the view that is showing it: see {@link #newRendering()}.
 * Nothing here memoizes rendered rows, and adding such state to an implementation is a defect
 * regardless of who happens to be reading it today — {@code StripEntryContractTest} is the gate.
 *
 * <p>Two render surfaces, and the distinction is what makes cross-thread reading safe:
 * <ul>
 *   <li><b>Live</b> — {@link #render(int)}, {@link #freezeTopRows(int, int)},
 *       {@link #replayRows(int)}, {@link #sealForReplay()}. Render thread only; they may advance
 *       render-thread-owned view state such as the [[冻结边界]].</li>
 *   <li><b>Retained</b> — {@link #renderRetained(int, FoldState)} and the fold/seal queries. Pure:
 *       any thread may call them, and they mutate nothing.</li>
 * </ul>
 */
interface StripEntry {

    List<AttributedString> render(int width);

    /** Source-model render contract used by retained consumers with an explicit fold choice. */
    default List<AttributedString> render(int width, FoldState foldState) {
        return renderRetained(width);
    }

    default List<AttributedString> renderRetained(int width) {
        return render(width);
    }

    default List<AttributedString> renderRetained(int width, FoldState foldState) {
        return render(width, foldState);
    }

    /**
     * Whether this entry reaches [[冻结回卷]] a piece at a time, so it can occupy more than one
     * position in the ordered commit log (ADR 0016 D5). Only a streaming answer does; everything else
     * is committed as it stands and takes exactly one position, covering all of it.
     */
    default boolean commitsIncrementally() {
        return false;
    }

    /**
     * The [[冻结边界]] in <b>source</b> coordinates: how many source lines of this entry have reached
     * [[冻结回卷]]. Only meaningful for an entry that {@link #commitsIncrementally()}; it is what a
     * position of this entry is closed at when something else is committed after it.
     */
    default int committedSourceLines() {
        return 0;
    }

    /**
     * {@link #renderRetained(int)} restricted to the source range {@code span} covers (ADR 0016 D5).
     * An entry committed in one piece has exactly one position and ignores the range.
     */
    default List<AttributedString> renderRetained(int width, CommittedSpan span) {
        return renderRetained(width);
    }

    /** {@link #renderRetained(int, FoldState)} restricted to the source range {@code span} covers. */
    default List<AttributedString> renderRetained(int width, FoldState foldState, CommittedSpan span) {
        return renderRetained(width, foldState);
    }

    /** {@link #replayRows(int)} restricted to the source range {@code span} covers. */
    default List<AttributedString> replayRows(int width, CommittedSpan span) {
        return replayRows(width);
    }

    default boolean isFoldable() {
        return false;
    }

    /** Thinking blocks stay collapsed by default in the transcript overlay (Ctrl+T reveals them). */
    default boolean isThinking() {
        return false;
    }

    default FoldState foldState() {
        return FoldState.COLLAPSED;
    }

    default void persistFoldState(FoldState foldState) {
    }

    List<AttributedString> freezeTopRows(int minimumRows, int width);

    /**
     * The rows this entry contributes when a [[destructive resize reset]] repaints the
     * [[bounded replay window]] at {@code width} — that is, the part of it that has already been
     * committed to [[冻结回卷]] and would otherwise be lost with the erased screen.
     *
     * <p>For everything that is committed wholesale this is simply the entry's retained rendering.
     * A streaming answer commits incrementally, so it overrides this with its committed prefix and
     * keeps the rest in the live tail.
     */
    default List<AttributedString> replayRows(int width) {
        return renderRetained(width);
    }

    /**
     * Whether this entry has finished committing, so a reset re-emits the single separator blank
     * that follows it in scrollback. A still-streaming block has not earned one: the live tail
     * continues it directly below.
     */
    default boolean isReplaySealed() {
        return true;
    }

    /** Record that the strip has emitted this entry's single trailing separator blank. */
    default void sealForReplay() {
    }

    /**
     * Whether this entry still has content to commit, so anything already showing it must expect its
     * height to change (ADR 0010 amendment A13).
     *
     * <p>Deliberately <b>not</b> the negation of {@link #isReplaySealed()}. That answers a different
     * question — whether a separator blank follows this entry in [[冻结回卷]] — and the [[提交块]] echo
     * answers no to it while being finished the moment it is committed. Conflating the two makes the
     * [[转写浏览层]] re-measure the whole session on every live-follow poll.
     */
    default boolean isStillCommitting() {
        return false;
    }

    /**
     * The immutable-source entry the ordered commit log retains for this one — itself for everything
     * that is committed as it stands, and the source underneath for a live view that wraps one
     * ({@link LiveAnswer}). The log never holds a view.
     */
    default StripEntry committedSource() {
        return this;
    }

    /**
     * A <b>fresh</b>, view-private rendering of this entry's source (ADR 0016 D1). Each call returns a
     * new one, so no rendering state is ever shared between the [[活区]] and the [[转写浏览层]].
     */
    default EntryRendering newRendering() {
        return new MemoizedEntryRendering(this);
    }

    /**
     * A fresh, view-private rendering of just the source range {@code span} covers (ADR 0016 D5). The
     * span is read live rather than copied, because a position that is still open closes when
     * something else is committed and the rendering must follow it down.
     */
    default EntryRendering newRendering(CommittedSpan span) {
        return new MemoizedEntryRendering(span);
    }
}
