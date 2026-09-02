package com.cafecli.render.inline;

import org.junit.jupiter.api.Test;
import org.jline.terminal.Terminal;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptOverlayModelTest {

    @Test
    void sgrWheelEventsDecodeAsLineScrollActions() {
        assertEquals(TranscriptOverlayModel.Action.UP,
                TranscriptOverlayModel.decodeSgrMouse("\u001b[<64;20;5M"));
        assertEquals(TranscriptOverlayModel.Action.DOWN,
                TranscriptOverlayModel.decodeSgrMouse("\u001b[<65;20;5M"));
    }

    @Test
    void regularInputDecoderRoutesSgrWheelEventsToLineScrolling() {
        assertEquals(TranscriptOverlayModel.Action.DOWN,
                TranscriptOverlayModel.decode("\u001b[<65;20;5M"));
    }

    @Test
    void overlayStartsWithThinkingCollapsedAndOtherFoldablesExpanded() {
        TranscriptOverlayModel.State state = TranscriptOverlayModel.start(
                List.of(false, true, true), List.of(false, true, false), 3);

        assertEquals(List.of(FoldState.COLLAPSED, FoldState.COLLAPSED, FoldState.EXPANDED),
                state.foldStates());
    }

    @Test
    void overlayForcesThinkingCollapsedAndNonThinkingExpandedButKeepsRetainedForPlainBlocks() {
        TranscriptOverlayModel.State state = TranscriptOverlayModel.start(
                List.of(false, true, true),
                List.of(false, false, true),
                List.of(FoldState.EXPANDED, FoldState.COLLAPSED, FoldState.EXPANDED), 3);

        assertEquals(List.of(FoldState.EXPANDED, FoldState.EXPANDED, FoldState.COLLAPSED),
                state.foldStates());
    }

    @Test
    void ctrlTDecodesAsToggleThinking() {
        assertEquals(TranscriptOverlayModel.Action.TOGGLE_THINKING,
                TranscriptOverlayModel.decode(String.valueOf((char) 20)));
    }

    @Test
    void toggleThinkingRevealsEveryThinkingBlockThenCollapsesThemAgain() {
        TranscriptOverlayModel.State collapsed = TranscriptOverlayModel.start(
                List.of(true, true), List.of(true, false), 2);

        TranscriptOverlayModel.State revealed = TranscriptOverlayModel.reduce(
                collapsed, TranscriptOverlayModel.Action.TOGGLE_THINKING);
        TranscriptOverlayModel.State hiddenAgain = TranscriptOverlayModel.reduce(
                revealed, TranscriptOverlayModel.Action.TOGGLE_THINKING);

        assertEquals(List.of(FoldState.COLLAPSED, FoldState.EXPANDED), collapsed.foldStates());
        assertEquals(List.of(FoldState.EXPANDED, FoldState.EXPANDED), revealed.foldStates());
        assertEquals(List.of(FoldState.COLLAPSED, FoldState.EXPANDED), hiddenAgain.foldStates());
    }

    @Test
    void togglingThinkingPreservesTheLineViewportPosition() {
        TranscriptOverlayModel.State scrolled = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(
                        List.of(true), List.of(true), List.of(FoldState.COLLAPSED), 2, 10),
                TranscriptOverlayModel.Action.PAGE_DOWN);

        TranscriptOverlayModel.State toggled = TranscriptOverlayModel.reduce(
                scrolled, TranscriptOverlayModel.Action.TOGGLE_THINKING);

        assertEquals(scrolled.viewportStart(), toggled.viewportStart());
    }

    /**
     * Scroll clamping is measured against the document's height, and a Ctrl+T changes that height:
     * every thinking block becomes its whole body instead of one header row.
     *
     * <p>This is the model half of ADR 0010 amendment A10's viewport-only layout. The overlay lays out
     * only what it shows, so the height it clamps against is whatever the layout has measured and
     * re-measured — and the end of the document has to stay reachable, exactly once, on both sides of
     * the toggle.
     */
    @Test
    void theEndOfTheDocumentStaysReachableAcrossAThinkingToggle() {
        TranscriptOverlayModel.State collapsed = TranscriptOverlayModel.start(
                List.of(true), List.of(true), List.of(FoldState.COLLAPSED), 4, 10);

        TranscriptOverlayModel.State atEnd = TranscriptOverlayModel.reduce(
                collapsed, TranscriptOverlayModel.Action.LAST);
        assertEquals(6, atEnd.viewportStart(), "the last page of a 10-row document at page size 4");
        assertEquals(6, TranscriptOverlayModel.reduce(atEnd, TranscriptOverlayModel.Action.DOWN)
                .viewportStart(), "and the viewport does not run off the end");

        // Ctrl+T reveals the thinking body: the same document is now 40 rows, re-measured by the
        // layout and handed back to the model.
        TranscriptOverlayModel.State revealed = TranscriptOverlayModel.withTotalLines(
                TranscriptOverlayModel.reduce(atEnd, TranscriptOverlayModel.Action.TOGGLE_THINKING),
                40);
        assertEquals(36, TranscriptOverlayModel.reduce(revealed, TranscriptOverlayModel.Action.LAST)
                .viewportStart(), "the taller document's end is reachable");

        // And collapsing again brings the end back where it was, rather than stranding the viewport
        // beyond a document that just got shorter.
        TranscriptOverlayModel.State hiddenAgain = TranscriptOverlayModel.withTotalLines(
                TranscriptOverlayModel.reduce(
                        TranscriptOverlayModel.reduce(revealed, TranscriptOverlayModel.Action.LAST),
                        TranscriptOverlayModel.Action.TOGGLE_THINKING),
                10);
        assertEquals(6, TranscriptOverlayModel.reduce(hiddenAgain, TranscriptOverlayModel.Action.LAST)
                .viewportStart());
    }

    /**
     * Live following, pinned at the tail (ADR 0010 amendment A13).
     *
     * <p>The overlay opens during an active turn to see what is happening now, so output that arrives
     * while it is open has to reach the viewport without the user reopening it.
     */
    @Test
    void pinnedAtTheTailTheViewportAdvancesAsOutputArrives() {
        TranscriptOverlayModel.State atTail = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(
                        List.of(false), List.of(false), List.of(FoldState.COLLAPSED), 10, 30),
                TranscriptOverlayModel.Action.LAST);
        assertEquals(20, atTail.viewportStart(), "the last page of a 30-row document at page size 10");
        assertTrue(TranscriptOverlayModel.pinnedToTail(atTail));

        TranscriptOverlayModel.State grown = TranscriptOverlayModel.follow(atTail, 35);

        assertEquals(25, grown.viewportStart(), "five more rows of output, five rows further down");
        assertEquals(35, grown.totalLines());
        assertTrue(TranscriptOverlayModel.pinnedToTail(grown), "and it is still following");
    }

    /**
     * Live following, scrolled up (ADR 0010 amendment A13): browsing is never yanked.
     *
     * <p>The two halves are one behaviour and one implementation — the viewport's position <i>is</i> the
     * mode — but each half is a separate regression: an overlay that always follows destroys reading,
     * and an overlay that never follows is not live.
     */
    @Test
    void scrolledUpTheViewportHoldsItsPositionWhileOutputArrives() {
        TranscriptOverlayModel.State browsing = TranscriptOverlayModel.withViewportStart(
                TranscriptOverlayModel.start(
                        List.of(false), List.of(false), List.of(FoldState.COLLAPSED), 10, 30),
                5);
        assertEquals(false, TranscriptOverlayModel.pinnedToTail(browsing));

        TranscriptOverlayModel.State grown = TranscriptOverlayModel.follow(browsing, 60);

        assertEquals(5, grown.viewportStart(), "the reader stays on the row they were reading");
        assertEquals(60, grown.totalLines(), "while the document below them keeps growing");
    }

    /** Returning to the tail resumes following, with nothing to switch back on. */
    @Test
    void returningToTheTailResumesFollowing() {
        TranscriptOverlayModel.State browsing = TranscriptOverlayModel.follow(
                TranscriptOverlayModel.withViewportStart(
                        TranscriptOverlayModel.start(
                                List.of(false), List.of(false), List.of(FoldState.COLLAPSED), 10, 30),
                        5),
                40);
        assertEquals(5, browsing.viewportStart());

        TranscriptOverlayModel.State backAtTheTail =
                TranscriptOverlayModel.reduce(browsing, TranscriptOverlayModel.Action.LAST);
        assertEquals(30, backAtTheTail.viewportStart());

        assertEquals(35, TranscriptOverlayModel.follow(backAtTheTail, 45).viewportStart(),
                "output that arrives after the return advances the viewport again");
    }

    /**
     * A resize while the overlay is open re-anchors the page size and re-clamps the viewport, so the
     * reader keeps their row rather than being closed or thrown to the top (ADR 0010 amendment A12).
     */
    @Test
    void resizingTheOverlayReAnchorsThePageSizeAndKeepsTheViewportInRange() {
        TranscriptOverlayModel.State scrolled = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(
                        List.of(true), List.of(false), List.of(FoldState.COLLAPSED), 4, 40),
                TranscriptOverlayModel.Action.LAST);
        assertEquals(36, scrolled.viewportStart());

        // The window grew: a taller page shows more rows, and the same content is now shorter.
        TranscriptOverlayModel.State taller =
                TranscriptOverlayModel.withPageSize(scrolled, 20);
        assertEquals(36, taller.viewportStart(), "a resize alone must not move the reader");
        assertEquals(20, TranscriptOverlayModel.withViewportStart(taller, 20).viewportStart());
        assertEquals(20, TranscriptOverlayModel.reduce(
                        TranscriptOverlayModel.withViewportStart(taller, 20),
                        TranscriptOverlayModel.Action.LAST).viewportStart(),
                "and the end of the document is still exactly one page from the bottom");
    }

    @Test
    void downScrollsTheLineViewportByOne() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(true), List.of(false), List.of(FoldState.COLLAPSED), 2, 10);

        TranscriptOverlayModel.State moved = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.DOWN);

        assertEquals(1, moved.viewportStart());
        assertEquals(10, moved.totalLines());
    }

    @Test
    void jAndDownArrowDecodeAsBlockCursorDown() {
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode("j"));
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode("\u001b[B"));
    }

    @Test
    void upScrollsPromptOnlyLineViewportBack() {
        TranscriptOverlayModel.State state = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(List.of(), List.of(), List.of(), 2, 10),
                TranscriptOverlayModel.Action.PAGE_DOWN);

        TranscriptOverlayModel.State moved = TranscriptOverlayModel.reduce(
                state, TranscriptOverlayModel.Action.UP);

        assertEquals(1, moved.viewportStart());
    }

    @Test
    void pageDownAndSpaceAdvanceOneViewport() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(true), List.of(false), List.of(FoldState.COLLAPSED), 2, 10);

        TranscriptOverlayModel.State paged = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.PAGE_DOWN);

        assertEquals(2, paged.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.PAGE_DOWN,
                TranscriptOverlayModel.decode("\u001b[6~"));
        assertEquals(TranscriptOverlayModel.Action.PAGE_DOWN,
                TranscriptOverlayModel.decode(" "));
    }

    @Test
    void pageUpMovesPromptOnlyViewportBackOnePage() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(), List.of(), List.of(), 2, 10);
        initial = TranscriptOverlayModel.reduce(initial, TranscriptOverlayModel.Action.PAGE_DOWN);
        initial = TranscriptOverlayModel.reduce(initial, TranscriptOverlayModel.Action.PAGE_DOWN);

        TranscriptOverlayModel.State paged = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.PAGE_UP);

        assertEquals(2, paged.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.PAGE_UP,
                TranscriptOverlayModel.decode("\u001b[5~"));
    }

    @Test
    void firstAndLastJumpToTheLineViewportBounds() {
        TranscriptOverlayModel.State middle = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(
                        List.of(true), List.of(false), List.of(FoldState.COLLAPSED), 3, 10),
                TranscriptOverlayModel.Action.PAGE_DOWN);

        TranscriptOverlayModel.State first = TranscriptOverlayModel.reduce(
                middle, TranscriptOverlayModel.Action.FIRST);
        TranscriptOverlayModel.State last = TranscriptOverlayModel.reduce(
                middle, TranscriptOverlayModel.Action.LAST);

        assertEquals(0, first.viewportStart());
        assertEquals(7, last.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.FIRST, TranscriptOverlayModel.decode("g"));
        assertEquals(TranscriptOverlayModel.Action.LAST, TranscriptOverlayModel.decode("G"));
    }

    @Test
    void enterDoesNothingAndCtrlOClosesTheExpandedOverlay() {
        assertNull(TranscriptOverlayModel.decode("\r"));
        assertEquals(TranscriptOverlayModel.Action.EXIT,
                TranscriptOverlayModel.decode(String.valueOf((char) 15)));
    }

    @Test
    void kAndUpArrowDecodeAsBlockCursorUp() {
        assertEquals(TranscriptOverlayModel.Action.UP, TranscriptOverlayModel.decode("k"));
        assertEquals(TranscriptOverlayModel.Action.UP, TranscriptOverlayModel.decode("\u001b[A"));
    }

    @Test
    void qAndStandaloneEscapeCloseTheOverlay() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(false), List.of(false), 1);

        assertEquals(true, TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.EXIT).closed());
        assertEquals(TranscriptOverlayModel.Action.EXIT, TranscriptOverlayModel.decode("q"));
        assertEquals(TranscriptOverlayModel.Action.EXIT, TranscriptOverlayModel.decode("\u001b"));
    }

    @Test
    void retainedFoldableRendersFromAnExplicitFoldChoice() {
        Terminal terminal = Mockito.mock(Terminal.class);
        ReasoningBlock reasoning = new ReasoningBlock(terminal, "first line\nsecond line");

        String collapsed = reasoning.render(80, FoldState.COLLAPSED).toString();
        String expanded = reasoning.render(80, FoldState.EXPANDED).toString();

        assertTrue(collapsed.contains("思考 2 行"));
        assertTrue(expanded.contains("first line"));
    }
}
