package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReasoningBlockTest {

    @Test
    void expandedReasoningRetainsTheStyledVerticalQuote() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "inspect state\nverify result\n");

        var expanded = block.render(80, FoldState.EXPANDED);

        assertEquals("│ inspect state", expanded.get(1).toString());
        assertNotEquals(AttributedStyle.DEFAULT, expanded.get(1).styleAt(0));
    }

    @Test
    void releasingReasoningDropsEveryFoldAndWidthRenderCache() {
        ReasoningBlock block = new ReasoningBlock(
                Mockito.mock(Terminal.class), "one\ntwo\n");
        block.render(80, FoldState.COLLAPSED);
        block.render(80, FoldState.EXPANDED);

        assertEquals(2, block.renderCacheEntries());
        assertEquals(2, block.releaseRenderCache());
        assertEquals(0, block.renderCacheEntries());
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
