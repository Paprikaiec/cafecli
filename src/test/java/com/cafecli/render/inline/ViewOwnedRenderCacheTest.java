package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entries in the ordered commit log are immutable source; laying that source out at a width — and the
 * cache that holds the result — belongs to the view that is showing it (ADR 0016 D1).
 *
 * <p>There are two views and they run on different threads: the [[活区]] on the render thread and the
 * [[转写浏览层]] on the input thread. What made one cache serving both unsafe is that committing sits
 * deliberately <b>above</b> the [[geometry turbulence]] gate, so a [[模态租借]] stops the render loop
 * writing to the terminal without stopping it mutating the source model — which is exactly what makes
 * live follow work.
 *
 * <p>These assertions are deliberately <b>not</b> about concurrency. Reproducing a {@code HashMap}
 * race would demonstrate a property of {@code HashMap}, not of Cafecli; the fix is to make the sharing
 * unreachable by construction, so what is pinned here is the construction.
 */
class ViewOwnedRenderCacheTest {

    private static Terminal dumbTerminal() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        return terminal;
    }

    private static AnswerSegment streamedAnswer(Terminal terminal, int lines) {
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        for (int line = 0; line < lines; line++) {
            answer.append("paragraph " + line + " mentioning src/Agent.java:" + line + " once.\n");
        }
        return answer;
    }

    private static List<StripEntry> logOf(StripEntry answer, int fillerItems) {
        List<StripEntry> log = new ArrayList<>();
        log.add(answer);
        for (int filler = 0; filler < fillerItems; filler++) {
            log.add(FrozenTextItem.ofAnsi("background task " + filler + " finished\n"));
        }
        return log;
    }

    private static OverlayDocument overlayOver(List<StripEntry> log, int width) {
        OverlayDocument document = new OverlayDocument(log);
        document.layout(width, Collections.nCopies(log.size(), FoldState.EXPANDED), false);
        return document;
    }

    /**
     * Reading the transcript while a turn streams must not cost the render thread anything.
     *
     * <p>With one cache serving both views, scrolling the answer out of the overlay's working set
     * released the [[活区]]'s own {@code MarkdownProjection} — its wrapped rows, its prose-highlight
     * cache and the accumulated {@code TerminalMarkdownRenderer} — so the next thing that touched the
     * answer replayed the whole of it. The counter is the existing prose-highlight pass count: it is
     * one pass per styled line ever laid out, so a replay is visible as passes the live view never
     * asked for.
     */
    @Test
    void scrollingTheOverlayDuringAStreamingTurnDoesNotReplayTheLiveAnswersMarkdown() {
        Terminal terminal = dumbTerminal();
        AnswerSegment answer = streamedAnswer(terminal, 40);
        LiveAnswer live = new LiveAnswer(answer);
        live.freezeTopRows(Integer.MAX_VALUE, 80);
        int liveWork = live.proseHighlightPasses();
        assertTrue(liveWork > 0, "the live view laid the answer out once");

        List<StripEntry> log = logOf(answer, 40);
        OverlayDocument document = overlayOver(log, 80);
        for (int scroll = 0; scroll < 10; scroll++) {
            document.viewport(0, 20);
            document.viewport(document.totalRows() - 20, 20);
        }

        assertEquals(liveWork, live.proseHighlightPasses(),
                "reading the transcript must not make the render thread lay the answer out again");
    }

    /**
     * Both views normally run at the terminal width, so this is the case where sharing looked like a
     * free cache hit: each holds its own rows at the same width, and the overlay releasing what has
     * left its viewport is a purely local operation.
     */
    @Test
    void theTwoViewsHoldTheirOwnRowsAtTheSameWidthAndNeitherReleasesTheOthers() {
        Terminal terminal = dumbTerminal();
        AnswerSegment answer = streamedAnswer(terminal, 40);
        LiveAnswer live = new LiveAnswer(answer);
        live.freezeTopRows(Integer.MAX_VALUE, 80);
        int liveRows = live.cachedRows();
        assertTrue(liveRows > 0, "the live view holds the rows it drew");

        List<StripEntry> log = logOf(answer, 40);
        OverlayDocument document = overlayOver(log, 80);
        document.viewport(0, 20);
        assertTrue(document.holdsRowsFor(answer), "the overlay holds the rows it drew");
        assertNotSame(document.viewport(0, 20), live.render(80),
                "the two views never hand out the same list");

        document.viewport(document.totalRows() - 20, 20);

        assertFalse(document.holdsRowsFor(answer),
                "the overlay drops its own rows for what it no longer shows");
        assertEquals(liveRows, live.cachedRows(),
                "and the live region keeps every row it drew");
    }
}
