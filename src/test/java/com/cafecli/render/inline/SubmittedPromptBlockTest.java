package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmittedPromptBlockTest {

    @Test
    void color256WashesEveryRowToTheFullTerminalWidthAcrossTheFold() {
        Terminal terminal = terminal(20, "xterm-256color", 256);

        List<AttributedString> rows = SubmittedPromptBlock.rows(
                "please review the render loop carefully", 20, terminal);

        assertTrue(rows.size() >= 2, rows.toString());
        for (AttributedString row : rows) {
            assertEquals(20, row.columnLength(), row.toString());
            // The wash reaches the last column, so it never breaks at the fold.
            assertTrue(row.subSequence(19, 20).toAnsi().contains("48;5;"),
                    row.toAnsi());
        }
    }

    @Test
    void color16DegradesToAForegroundDistinctionWithoutBackgroundWash() {
        Terminal terminal = terminal(20, "xterm", 16);

        List<AttributedString> rows = SubmittedPromptBlock.rows(
                "please review the render loop carefully", 20, terminal);

        assertTrue(rows.size() >= 2, rows.toString());
        for (AttributedString row : rows) {
            String ansi = row.toAnsi(terminal);
            assertFalse(ansi.contains("48;5;"), ansi);
            // Still visually distinct from model prose: an explicit foreground colour.
            assertTrue(ansi.contains("36m"), ansi);
            assertTrue(row.toString().chars().noneMatch(ch -> ch == 27), row.toString());
        }
        assertTrue(rows.get(0).toString().startsWith("› please"), rows.toString());
    }

    @Test
    void markerCarriesItsOwnPurpleForegroundDistinctFromTheBody() {
        Terminal terminal = terminal(20, "xterm-256color", 256);

        List<AttributedString> rows = SubmittedPromptBlock.rows(
                "please review the render loop carefully", 20, terminal);

        AttributedString marker = rows.get(0).subSequence(0, 2);
        assertTrue(marker.toAnsi().contains("38;5;141"), marker.toAnsi());
        // Only the marker is purple: the body keeps the wash without a foreground override.
        assertFalse(rows.get(0).subSequence(2, 20).toAnsi().contains("38;5;141"),
                rows.get(0).toAnsi());
        assertFalse(rows.get(1).subSequence(0, 2).toAnsi().contains("38;5;141"),
                rows.get(1).toAnsi());
    }

    @Test
    void color16MarkerDegradesToMagentaAgainstTheCyanBody() {
        Terminal terminal = terminal(20, "xterm", 16);

        List<AttributedString> rows = SubmittedPromptBlock.rows(
                "please review the render loop carefully", 20, terminal);

        String firstRow = rows.get(0).toAnsi(terminal);
        assertTrue(firstRow.contains("35m"), firstRow);
        assertFalse(rows.get(1).toAnsi(terminal).contains("35m"), rows.get(1).toAnsi(terminal));
    }

    @Test
    void cjkWrapsByColumnWidthNotCharacterCount() {
        Terminal terminal = terminal(12, "xterm-256color", 256);

        // Width 12 - "› " (2 columns) leaves 10 body columns = 5 full-width characters per row.
        List<AttributedString> rows = SubmittedPromptBlock.rows("重绘提交块的底纹与标记", 12, terminal);

        assertEquals(List.of("› 重绘提交块", "  的底纹与标", "  记        "),
                rows.stream().map(AttributedString::toString).toList());
        for (AttributedString row : rows) {
            assertEquals(12, row.columnLength(), row.toString());
        }
    }

    @Test
    void ansiLessTerminalYieldsReadablePlainTextWithoutEscapeResidue() {
        Terminal terminal = terminal(20, "dumb", null);

        String emitted = SubmittedPromptBlock.render(
                "please review the render loop carefully", 20, terminal);

        List<String> emittedRows = List.of(emitted.split("\n", -1));
        assertTrue(emitted.chars().noneMatch(ch -> ch == 27), emitted);
        assertTrue(emittedRows.get(0).startsWith("› please"), emitted);
        assertTrue(emittedRows.get(1).startsWith("  "), emitted);
    }

    @Test
    void explicitNewlinesKeepTheMarkerOnTheFirstRowOnlyWithoutInnerSpacer() {
        Terminal terminal = terminal(40, "xterm-256color", 256);

        String emitted = SubmittedPromptBlock.render("第一行\n第二行\n第三行", 40, terminal);

        List<String> rows = List.of(AttributedString.stripAnsi(emitted).split("\n", -1));
        assertEquals(4, rows.size(), emitted);
        assertTrue(rows.get(0).startsWith("› 第一行"), emitted);
        assertTrue(rows.get(1).startsWith("  第二行"), emitted);
        assertTrue(rows.get(2).startsWith("  第三行"), emitted);
        // Trailing element is the empty tail after the final "\n" — no blank spacer row inside.
        assertEquals("", rows.get(3), emitted);
        assertEquals(1, rows.stream().filter(row -> row.contains("›")).count(), emitted);
    }

    @Test
    void blankInputProducesNoBlock() {
        Terminal terminal = terminal(20, "xterm-256color", 256);

        assertEquals(List.of(), SubmittedPromptBlock.rows("   ", 20, terminal));
        assertEquals("", SubmittedPromptBlock.render("   ", 20, terminal));
        assertEquals("", SubmittedPromptBlock.render("", 20, terminal));
        assertEquals("", SubmittedPromptBlock.render(null, 20, terminal));
    }

    private static Terminal terminal(int columns, String type, Integer maxColors) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn(type);
        Size size = new Size(columns, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors))
                .thenReturn(maxColors);
        return terminal;
    }
}
