package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningBlockTest {

    @Test
    void reasoningReportsItselfAsThinkingSoTheOverlayCanCollapseItByDefault() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "inspect state\nverify result\n");

        assertTrue(block.isThinking());
    }

    @Test
    void collapsedReasoningHeaderIsPlainDimLabelWithoutGutterOrEmoji() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "inspect state\nverify result\n");

        AttributedString header = block.render(80, FoldState.COLLAPSED).getFirst();

        assertEquals("思考 2 行", header.toString());
        assertFalse(header.toString().contains("▎"));
        assertNotEquals(AttributedStyle.DEFAULT, header.styleAt(0));
    }

    @Test
    void expandedReasoningRetainsTheStyledVerticalQuote() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "inspect state\nverify result\n");

        var expanded = block.render(80, FoldState.EXPANDED);

        assertEquals("│ inspect state", expanded.get(1).toString());
        assertFalse(expanded.stream().anyMatch(line -> line.toString().contains("collapse")));
        assertNotEquals(AttributedStyle.DEFAULT, expanded.get(1).styleAt(0));
    }

    /**
     * Releasing rendered rows is a <b>view's</b> operation, not the block's (ADR 0016 D1). This test
     * used to call {@code block.releaseRenderCache()} and count the block's own cache entries; that
     * contract is superseded — a block that held rows would be one cache serving two threads.
     */
    @Test
    void releasingEveryFoldAndWidthIsAViewOperationAndTheBlockHoldsNothing() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "one\ntwo\n");
        RetainedRenderCache view = new RetainedRenderCache();
        view.rows(block, 80, FoldState.COLLAPSED);
        view.rows(block, 80, FoldState.EXPANDED);

        assertTrue(view.cachedRows() > 0);
        view.release(block);
        assertEquals(0, view.cachedRows());
        assertEquals(0, new RetainedRenderCache().cachedRows(),
                "a second view starts empty; nothing is carried on the block");
    }

    @Test
    void expandedReasoningHighlightsEntitiesWhilePreservingQuoteStyle() {
        var terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        ReasoningBlock block = new ReasoningBlock(
                terminal, "inspect src/Agent.java:12 with executeTools()\n");

        var line = block.render(120, FoldState.EXPANDED).get(1);

        assertNotEquals(line.styleAt(0), line.styleAt(line.toString().indexOf("src/Agent")));
        assertNotEquals(line.styleAt(line.toString().indexOf("src/Agent")),
                line.styleAt(line.toString().indexOf("executeTools")));
    }
}
