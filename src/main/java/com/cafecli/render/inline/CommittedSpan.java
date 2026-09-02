package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.List;

/**
 * One <b>position</b> in the ordered commit log: a source, and the range of that source which occupies
 * this position (ADR 0016 D5).
 *
 * <p>A position used to be a whole entry, fixed at the entry's <em>first</em> commit and idempotent by
 * identity. A streaming answer enters the log the moment its first row reaches [[冻结回卷]], so
 * anything committed while it was still growing took a position after the answer's <b>entire eventual
 * output</b>. ADR 0010 amendment A8b priced that as a coarsening visible only after a
 * [[destructive resize reset]]; it is worse than that, because the [[转写浏览层]] renders an answer's
 * whole retained rendering rather than its committed prefix, so the mis-ordering shows on every
 * viewing — and the overlay is exactly where a user goes to ask where their [[转向消息]] landed.
 *
 * <p>So an answer interrupted by another commit occupies <b>two</b> positions, with the interrupting
 * item between them. The range is expressed in the same <b>source</b> coordinates as the
 * [[冻结边界]] (A11), which is what makes it survive a width change: every projection maps it into its
 * own styled space.
 *
 * <p>It is itself a {@link StripEntry}, so both consumers — {@link ReplayWindow} and
 * {@link OverlayDocument} — keep working on the type they already had, and each position keeps a
 * stable identity for their identity-keyed caches. Everything not about the range is delegated
 * straight through; what it decides for itself is two things:
 *
 * <ul>
 *   <li><b>which rows</b> — the source restricted to {@code [from, to)}; and</li>
 *   <li><b>the trailing separator blank</b>, which belongs to a source's <em>last</em> position only.
 *       Without that suppression an interrupted answer would sprout a blank line wherever the
 *       correction happened to land and stop reading as a single block.</li>
 * </ul>
 *
 * <p>Two fields move after construction, both written by the render thread as it appends to the log
 * and read by the input thread while the [[转写浏览层]] follows a running turn, hence {@code volatile}.
 * Neither is touched by rendering: laying this position out mutates nothing, exactly as ADR 0016 D1
 * requires of everything the log holds.
 */
final class CommittedSpan implements StripEntry {

    /** No upper bound yet: this position still grows with its source. */
    static final int OPEN = -1;

    private final StripEntry source;
    private final int from;
    private volatile int to = OPEN;
    private volatile boolean superseded;

    CommittedSpan(StripEntry source, int from) {
        this.source = source;
        this.from = Math.max(0, from);
    }

    StripEntry source() {
        return source;
    }

    int from() {
        return from;
    }

    /** The exclusive upper bound, or {@link #OPEN} while this position is still growing. */
    int to() {
        return to;
    }

    /**
     * Something else has been committed, so this position stops where its source had got to. Only a
     * source that commits a piece at a time can be closed — everything else occupies exactly one
     * position covering all of it, forever.
     */
    void close() {
        if (to == OPEN && source.commitsIncrementally()) {
            to = source.committedSourceLines();
        }
    }

    /** A later position of the same source exists, so the trailing separator blank is not ours. */
    void supersede() {
        superseded = true;
    }

    @Override
    public List<AttributedString> render(int width) {
        // A retained position is never live; the [[活区]] draws through its own view.
        return List.of();
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return source.renderRetained(width, this);
    }

    @Override
    public List<AttributedString> renderRetained(int width, FoldState foldState) {
        return source.renderRetained(width, foldState, this);
    }

    @Override
    public List<AttributedString> render(int width, FoldState foldState) {
        return renderRetained(width, foldState);
    }

    @Override
    public List<AttributedString> replayRows(int width) {
        return source.replayRows(width, this);
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        // Freezing advances the live view's boundary and is never done through the log.
        return List.of();
    }

    @Override
    public boolean isFoldable() {
        return source.isFoldable();
    }

    @Override
    public boolean isThinking() {
        return source.isThinking();
    }

    @Override
    public FoldState foldState() {
        return source.foldState();
    }

    @Override
    public void persistFoldState(FoldState foldState) {
        source.persistFoldState(foldState);
    }

    @Override
    public boolean isReplaySealed() {
        return !superseded && source.isReplaySealed();
    }

    @Override
    public void sealForReplay() {
        source.sealForReplay();
    }

    @Override
    public boolean isStillCommitting() {
        return to == OPEN && source.isStillCommitting();
    }

    @Override
    public EntryRendering newRendering() {
        return source.newRendering(this);
    }
}
