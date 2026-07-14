package com.paicli.render.inline;

import org.junit.jupiter.api.Test;
import org.jline.terminal.Terminal;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptOverlayModelTest {

    @Test
    void downMovesTheBlockCursorAndKeepsItVisible() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(false, true, false), 2);

        TranscriptOverlayModel.State moved = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.DOWN);

        assertEquals(1, moved.cursor());
        assertEquals(0, moved.viewportStart());
    }

    @Test
    void jAndDownArrowDecodeAsBlockCursorDown() {
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode("j"));
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode("\u001b[B"));
    }

    @Test
    void upMovesTheCursorAndScrollsTheViewportBack() {
        TranscriptOverlayModel.State state = new TranscriptOverlayModel.State(
                2, 2, 2, List.of(false, false, false));

        TranscriptOverlayModel.State moved = TranscriptOverlayModel.reduce(
                state, TranscriptOverlayModel.Action.UP);

        assertEquals(1, moved.cursor());
        assertEquals(1, moved.viewportStart());
    }

    @Test
    void pageDownAndSpaceAdvanceOneViewport() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(
                List.of(false, false, false, false, false), 2);

        TranscriptOverlayModel.State paged = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.PAGE_DOWN);

        assertEquals(2, paged.cursor());
        assertEquals(2, paged.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.PAGE_DOWN,
                TranscriptOverlayModel.decode("\u001b[6~"));
        assertEquals(TranscriptOverlayModel.Action.PAGE_DOWN,
                TranscriptOverlayModel.decode(" "));
    }

    @Test
    void pageUpMovesBackOneViewport() {
        TranscriptOverlayModel.State initial = new TranscriptOverlayModel.State(
                4, 4, 2, List.of(false, false, false, false, false));

        TranscriptOverlayModel.State paged = TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.PAGE_UP);

        assertEquals(2, paged.cursor());
        assertEquals(2, paged.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.PAGE_UP,
                TranscriptOverlayModel.decode("\u001b[5~"));
    }

    @Test
    void firstAndLastJumpToTheTranscriptBounds() {
        List<Boolean> blocks = List.of(false, false, false, false);
        TranscriptOverlayModel.State middle = new TranscriptOverlayModel.State(2, 1, 2, blocks);

        TranscriptOverlayModel.State first = TranscriptOverlayModel.reduce(
                middle, TranscriptOverlayModel.Action.FIRST);
        TranscriptOverlayModel.State last = TranscriptOverlayModel.reduce(
                middle, TranscriptOverlayModel.Action.LAST);

        assertEquals(0, first.cursor());
        assertEquals(0, first.viewportStart());
        assertEquals(3, last.cursor());
        assertEquals(2, last.viewportStart());
        assertEquals(TranscriptOverlayModel.Action.FIRST, TranscriptOverlayModel.decode("g"));
        assertEquals(TranscriptOverlayModel.Action.LAST, TranscriptOverlayModel.decode("G"));
    }

    @Test
    void enterAndCtrlOToggleOnlyTheSelectedFoldableBlock() {
        TranscriptOverlayModel.State selected = TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.start(List.of(false, true, true), 3),
                TranscriptOverlayModel.Action.DOWN);

        TranscriptOverlayModel.State toggled = TranscriptOverlayModel.reduce(
                selected, TranscriptOverlayModel.Action.TOGGLE);

        assertEquals(FoldState.EXPANDED, toggled.foldStates().get(1));
        assertEquals(FoldState.COLLAPSED, selected.foldStates().get(1));
        assertEquals(TranscriptOverlayModel.Action.TOGGLE, TranscriptOverlayModel.decode("\r"));
        assertEquals(TranscriptOverlayModel.Action.TOGGLE,
                TranscriptOverlayModel.decode(String.valueOf((char) 15)));
    }

    @Test
    void kAndUpArrowDecodeAsBlockCursorUp() {
        assertEquals(TranscriptOverlayModel.Action.UP, TranscriptOverlayModel.decode("k"));
        assertEquals(TranscriptOverlayModel.Action.UP, TranscriptOverlayModel.decode("\u001b[A"));
    }

    @Test
    void qAndStandaloneEscapeCloseTheOverlay() {
        TranscriptOverlayModel.State initial = TranscriptOverlayModel.start(List.of(false), 1);

        assertEquals(true, TranscriptOverlayModel.reduce(
                initial, TranscriptOverlayModel.Action.EXIT).closed());
        assertEquals(TranscriptOverlayModel.Action.EXIT, TranscriptOverlayModel.decode("q"));
        assertEquals(TranscriptOverlayModel.Action.EXIT, TranscriptOverlayModel.decode("\u001b"));
    }

    @Test
    void retainedFoldableRendersFromAnExplicitFoldChoice() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock code = new CodeBlock(terminal, "java",
                String.join("\n", java.util.Collections.nCopies(12, "line")));

        String collapsed = code.render(80, FoldState.COLLAPSED).toString();
        String expanded = code.render(80, FoldState.EXPANDED).toString();

        assertTrue(collapsed.contains("12 行"));
        assertTrue(expanded.contains("line"));
    }
}
