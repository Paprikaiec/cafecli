package com.paicli.render.inline;

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
