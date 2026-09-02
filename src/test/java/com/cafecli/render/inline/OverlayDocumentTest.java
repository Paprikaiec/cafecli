package com.cafecli.render.inline;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the [[转写浏览层]] lays out the retained session (ADR 0010 amendment A10).
 *
 * <p>The property under test is a <b>cost</b> property, so it is asserted by counting layouts rather
 * than by timing: showing forty rows must not build ten thousand. A functional assertion cannot catch
 * a regression here — the old whole-document layout produced exactly the same rows, just by rendering
 * every item on every keystroke — so the counter is the regression gate and the wall-clock numbers in
 * the delivery notes are the measurement.
 *
 * <p>This is deliberately not the virtualization A5 excludes: no session-store paging, no
 * semantic-anchor positioning, no layout cache keyed by block revision. The viewport is still an
 * absolute row range.
 */
class OverlayDocumentTest {

    /** A retained item of known height that counts how often it is laid out. */
    private static final class CountingItem implements StripEntry {
        private final String text;
        /** Not final: a block that is still streaming grows while the overlay is showing it. */
        private int rows;
        private boolean sealed;
        private boolean streaming;
        private int layouts;

        private CountingItem(String text, int rows) {
            this.text = text;
            this.rows = rows;
        }

        private CountingItem sealed() {
            this.sealed = true;
            return this;
        }

        private CountingItem streaming() {
            this.streaming = true;
            return this;
        }

        @Override
        public List<AttributedString> render(int width) {
            layouts++;
            List<AttributedString> lines = new ArrayList<>(rows);
            for (int row = 0; row < rows; row++) {
                lines.add(new AttributedString(text + "-" + row));
            }
            return lines;
        }

        @Override
        public List<AttributedString> freezeTopRows(int minimumRows, int width) {
            return List.of();
        }

        @Override
        public boolean isReplaySealed() {
            return sealed;
        }

        @Override
        public boolean isStillCommitting() {
            return streaming;
        }
    }

    private static List<CountingItem> session(int items, int rowsEach) {
        List<CountingItem> log = new ArrayList<>(items);
        for (int item = 0; item < items; item++) {
            log.add(new CountingItem("item-" + item, rowsEach));
        }
        return log;
    }

    /** A session whose every item has finished committing, so its height cannot change again. */
    private static List<CountingItem> sealedSession(int items, int rowsEach) {
        List<CountingItem> log = session(items, rowsEach);
        log.forEach(CountingItem::sealed);
        return log;
    }

    private static OverlayDocument documentOver(List<CountingItem> log) {
        OverlayDocument document = new OverlayDocument(List.copyOf(log));
        document.layout(78, java.util.Collections.nCopies(log.size(), FoldState.EXPANDED), false);
        return document;
    }

    /**
     * Opening the overlay on a long session lays out a viewport, not a session.
     *
     * <p>Retention is now the whole session, so this is what makes it deliverable: the cost of opening
     * has to be independent of how long the session has run.
     */
    @Test
    void openingALongSessionLaysOutOnlyTheRowsTheViewportShows() {
        List<CountingItem> log = session(2_000, 4);
        OverlayDocument document = documentOver(log);

        List<AttributedString> viewport = document.viewport(0, 40);

        assertEquals(40, viewport.size());
        assertEquals("  item-0-0", viewport.get(0).toString(), "rows carry the overlay's indent");
        int laidOut = (int) log.stream().filter(item -> item.layouts > 0).count();
        assertTrue(laidOut <= 20,
                "opening must lay out a viewport's worth of items, not the session: " + laidOut);
        assertEquals(0, log.get(1_999).layouts,
                "the newest item of a 2000-item session is nowhere near the first viewport");
    }

    /**
     * And scrolling a line costs a line, not a document. The item that entered the viewport is laid
     * out; the ones the user scrolled past long ago are not touched again.
     */
    @Test
    void scrollingOneLineLaysOutOnlyWhatEnteredTheViewport() {
        List<CountingItem> log = session(2_000, 4);
        OverlayDocument document = documentOver(log);
        document.viewport(0, 40);
        List<Integer> before = log.stream().map(item -> item.layouts).toList();

        document.viewport(1, 40);

        int relaidOut = 0;
        for (int item = 0; item < log.size(); item++) {
            if (log.get(item).layouts > before.get(item)) {
                relaidOut++;
            }
        }
        assertTrue(relaidOut <= 12,
                "a one-line scroll may lay out the items that entered the viewport and no more: "
                        + relaidOut);
    }

    /**
     * Rendered rows are released outside the working set; what memory tracks is retained
     * <b>source</b>.
     *
     * <p>This is where the cost of full-session retention would otherwise accumulate, and it replaces
     * the two eviction tests that used to hold the property — the five-turn window released a turn's
     * rows when it evicted it, and there is no eviction any more.
     *
     * <p>The rows counted are <b>the document's own</b> (ADR 0016 D1). This assertion used to read
     * {@code item.renderCacheEntries()} on the entries themselves, which is exactly the sharing that
     * let this release reach into the [[活区]]'s state on another thread.
     */
    @Test
    void rowsOutsideTheWorkingSetAreReleasedWhileTheSourceIsKept() {
        List<CountingItem> log = session(500, 4);
        OverlayDocument document = documentOver(log);

        document.viewport(0, 40);
        assertTrue(document.cachedRows() > 0, "what is on screen keeps its rows");

        // Read to the end of the session: everything measured on the way past is released again.
        document.viewport(document.totalRows() - 40, 40);

        assertTrue(document.cachedItems() <= 12,
                "only the working set may hold rendered rows, but "
                        + document.cachedItems() + " items do");
        assertTrue(document.cachedRows() > 0, "and the working set does hold its own");
        assertEquals(500, log.size(), "the retained source itself is untouched");
    }

    /**
     * The document's height is what scroll clamping is measured against, so jumping to the end has to
     * know the real one — and a fold change that makes every thinking block taller changes it.
     */
    @Test
    void theDocumentHeightIsCorrectAndIsReMeasuredForTheOtherFoldMode() {
        List<CountingItem> log = session(50, 4);
        OverlayDocument document = documentOver(log);

        assertEquals(200, document.totalRows());
        assertTrue(document.fullyMeasured());

        // The same items in the other fold mode: heights are indexed per mode, not shared.
        document.layout(38, java.util.Collections.nCopies(log.size(), FoldState.COLLAPSED), true);
        assertEquals(200, document.totalRows(),
                "each fold mode carries its own measured heights");
    }

    /**
     * Output committed while the overlay is open enters the document (ADR 0010 amendment A13), and it
     * enters <b>incrementally</b>: absorbing three new items must not re-lay-out two thousand old ones,
     * or following live would cost a session per frame — which is the reason live following was
     * rejected before A10's viewport-only layout existed.
     */
    @Test
    void outputCommittedWhileTheOverlayIsOpenEntersTheDocumentIncrementally() {
        List<CountingItem> log = new ArrayList<>(sealedSession(2_000, 4));
        OverlayDocument document = documentOver(log);
        document.viewport(0, 40);
        List<Integer> before = log.stream().map(item -> item.layouts).toList();

        log.add(new CountingItem("fresh", 4).sealed());
        assertTrue(document.refresh(List.copyOf(log)), "the document changed");

        assertEquals(2_001, document.itemCount());
        assertEquals("  fresh-0", document.viewport(document.totalRows() - 5, 1).get(0).toString(),
                "and the newest item is reachable at the tail");
        int relaidOut = 0;
        for (int item = 0; item < before.size(); item++) {
            if (log.get(item).layouts > before.get(item) + 1) {
                relaidOut++;
            }
        }
        assertEquals(0, relaidOut,
                "absorbing new output must not re-lay-out items whose height cannot have changed");
    }

    /**
     * A block that is still streaming has no settled height, so the document re-measures it — and only
     * it — while it grows. Everything after it shifts down by the difference.
     */
    @Test
    void aBlockStillStreamingIsReMeasuredWhileItGrows() {
        CountingItem streaming = new CountingItem("stream", 2).streaming();
        List<CountingItem> log = List.of(new CountingItem("old", 3).sealed(), streaming);
        List<StripEntry> asLog = List.copyOf(log);
        OverlayDocument document = documentOver(log);
        assertEquals(6, document.totalRows(), "three rows, a separator blank, and two streamed rows");
        int settled = log.get(0).layouts;

        streaming.rows = 5;
        assertTrue(document.refresh(asLog), "the document changed under the reader");

        assertEquals(9, document.totalRows());
        assertEquals("  stream-4", document.viewport(8, 1).get(0).toString());
        assertEquals(settled, log.get(0).layouts,
                "a settled item is not re-measured because a later one grew");
        assertEquals(false, document.refresh(asLog),
                "and a poll that finds nothing new reports no change");
    }

    /**
     * "Carries no separator blank" is not "still growing", and conflating them costs a session per
     * poll.
     *
     * <p>The [[提交块]] echo brings its own trailing newline, so the strip never seals it for replay —
     * yet it is finished the moment it is committed and its height can never change again. Measured on
     * a 2000-turn session with the two conflated, one live-follow poll cost 3.1 ms and there is one
     * every 50 ms; separated, an idle poll re-measures nothing.
     */
    @Test
    void anItemThatMerelyCarriesNoSeparatorIsNotReMeasuredOnEveryPoll() {
        List<CountingItem> log = session(200, 4);
        OverlayDocument document = documentOver(log);
        document.totalRows();
        List<Integer> before = log.stream().map(item -> item.layouts).toList();

        assertEquals(false, document.refresh(List.copyOf(log)), "nothing arrived and nothing grew");

        int remeasured = 0;
        for (int item = 0; item < log.size(); item++) {
            if (log.get(item).layouts > before.get(item)) {
                remeasured++;
            }
        }
        assertEquals(0, remeasured, "an idle poll must re-measure nothing");
    }

    /** A viewport past the end of the document yields what there is, and never a phantom row. */
    @Test
    void aViewportBeyondTheEndOfTheDocumentStopsAtTheLastRow() {
        List<CountingItem> log = session(3, 4);
        OverlayDocument document = documentOver(log);

        assertEquals(2, document.viewport(10, 40).size());
        assertEquals(12, document.totalRows());
    }
}
