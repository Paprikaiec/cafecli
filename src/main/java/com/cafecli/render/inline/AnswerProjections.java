package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * One <b>view's</b> set of {@link AnswerProjection}s of one streamed answer — one per width, sharing
 * one prose-entity cache (ADR 0016 D1).
 *
 * <p>The prose cache is shared across widths and not across views. Across widths because the styled
 * line is the same object at every width for everything except a table, so re-highlighting it per
 * width is pure waste; not across views because that is precisely the {@code HashMap} the render
 * thread and the input thread were both writing.
 *
 * <p>Owned by whoever created it — {@link LiveAnswer} on the render thread, the [[转写浏览层]]'s
 * rendering on the input thread — and therefore not synchronized.
 */
final class AnswerProjections {

    private final Terminal terminal;
    private final Runnable cacheHitRecorder;
    private final Map<AttributedString, AttributedString> highlightedProse = new HashMap<>();
    private final Map<Integer, AnswerProjection> byWidth = new HashMap<>();
    private int proseHighlightPasses;

    AnswerProjections(Terminal terminal, Runnable cacheHitRecorder) {
        this.terminal = terminal;
        this.cacheHitRecorder = cacheHitRecorder == null ? () -> { } : cacheHitRecorder;
    }

    AnswerProjection at(int width) {
        return byWidth.computeIfAbsent(Math.max(1, width),
                columns -> new AnswerProjection(terminal, columns, cacheHitRecorder, this));
    }

    /** The prose-entity pass, memoized per styled line for this view. */
    AttributedString highlight(AttributedString styled) {
        AttributedString prose = highlightedProse.get(styled);
        if (prose == null) {
            proseHighlightPasses++;
            prose = ProseEntityHighlighter.highlight(
                    styled,
                    TerminalCapabilities.supportsOsc8(terminal),
                    Path.of("").toAbsolutePath());
            highlightedProse.put(styled, prose);
        } else {
            cacheHitRecorder.run();
        }
        return prose;
    }

    /** Rendered rows this view is currently holding for the answer, over every width it has drawn at. */
    int cachedRows() {
        return byWidth.values().stream().mapToInt(AnswerProjection::cachedRows).sum();
    }

    /**
     * How many styled lines this view has ever had to run the prose-entity pass over.
     *
     * <p>This is the counter the D1 regression reads: a view whose state another view wiped replays
     * the whole answer, and that shows up here as passes this view never asked for.
     */
    int proseHighlightPasses() {
        return proseHighlightPasses;
    }
}
