package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.List;

/**
 * The [[活区]]'s own view of one streaming {@link AnswerSegment} (ADR 0016 D1).
 *
 * <p>It is what sits in the strip's live entries, and it owns the width-keyed {@link AnswerProjection}
 * that lays the answer out. The segment underneath it is bare immutable source and is what reaches
 * the ordered commit log — so the [[转写浏览层]], reading that log from the input thread, can never
 * reach this object or anything in it.
 *
 * <p>Render thread only, and not synchronized, because nothing else has a reference to it.
 */
final class LiveAnswer implements StripEntry {

    private final AnswerSegment segment;
    private final AnswerProjections projections;

    LiveAnswer(AnswerSegment segment) {
        this.segment = segment;
        this.projections = segment.newProjections();
    }

    /** What the log retains for this entry: the source, never this view. */
    @Override
    public StripEntry committedSource() {
        return segment;
    }

    boolean isComplete() {
        return segment.isComplete();
    }

    @Override
    public List<AttributedString> render(int width) {
        return segment.liveTailRows(projection(width));
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return segment.retainedRows(projection(width));
    }

    @Override
    public List<AttributedString> replayRows(int width) {
        return segment.committedRows(projection(width));
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        return segment.freezeTopRows(minimumRows, projection(width));
    }

    boolean claimTrailingSeparator(int width) {
        return segment.claimTrailingSeparator(projection(width));
    }

    @Override
    public boolean isReplaySealed() {
        return segment.isReplaySealed();
    }

    @Override
    public void sealForReplay() {
        segment.sealForReplay();
    }

    @Override
    public boolean isStillCommitting() {
        return segment.isStillCommitting();
    }

    /** Rendered rows this view is holding, summed over every width it has drawn at. */
    int cachedRows() {
        return projections.cachedRows();
    }

    /** Styled lines this view has had to highlight — see {@link AnswerProjections}. */
    int proseHighlightPasses() {
        return projections.proseHighlightPasses();
    }

    private AnswerProjection projection(int width) {
        return projections.at(width);
    }
}
