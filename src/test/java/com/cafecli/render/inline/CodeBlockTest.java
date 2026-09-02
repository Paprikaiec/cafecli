package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class CodeBlockTest {

    @Test
    void overwideCodeLineIsClippedInsteadOfWrapped() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "text", "0123456789ABCDEFGHIJ");

        List<AttributedString> bodyRows = block.render(8, FoldState.EXPANDED).stream()
                .filter(line -> line.toString().matches(".*[0-9].*"))
                .toList();

        assertEquals(1, bodyRows.size());
        assertEquals(8, bodyRows.get(0).columnLength());
        assertEquals('…', bodyRows.get(0).charAt(bodyRows.get(0).length() - 1));
    }

    @Test
    void clippedBoxDrawingRowsKeepTheirVisibleGridAligned() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "text",
                "┌──────────┐\n"
                        + "│abcdefghij│\n"
                        + "└──────────┘");

        List<String> bodyRows = block.render(8, FoldState.EXPANDED).stream()
                .map(AttributedString::toString)
                .filter(line -> !line.isEmpty())
                .toList();

        assertEquals(List.of("┌──────…", "│abcdef…", "└──────…"), bodyRows);
    }

    @Test
    void defaultCodeUsesPlainHeaderAndBlankSeparatorsWithoutBordersOrIndentation() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "text", "value");

        List<String> rows = block.render(80).stream()
                .map(AttributedString::toString)
                .toList();

        assertEquals(List.of("code: text", "", "value"), rows);
    }

    @Test
    void collapsedCodeBlockHasNoLeadingOrTrailingEdgeBlank() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "text", "value");

        List<AttributedString> rows = block.render(80);

        // The live strip owns inter-block spacing, so a collapsed code unit must not carry its own
        // edge blanks: the first row is the accent header and the last row is real body content.
        // The single blank between the header and the body is intra-block and stays.
        assertFalse(rows.get(0).toString().isEmpty(),
                "collapsed code must start with the accent header, not a blank row");
        assertFalse(rows.get(rows.size() - 1).toString().isEmpty(),
                "collapsed code must end with body content, not a trailing edge blank");
    }

    @Test
    void defaultCodeViewCapsAtOneHundredLinesAndReportsOverflow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        String source = java.util.stream.IntStream.rangeClosed(1, 103)
                .mapToObj(line -> "line-%03d".formatted(line))
                .collect(java.util.stream.Collectors.joining("\n"));
        CodeBlock block = new CodeBlock(terminal, "text", source);

        List<String> rows = block.render(80).stream()
                .map(AttributedString::toString)
                .toList();

        assertEquals(103, rows.size());
        assertEquals("code: text", rows.get(0));
        assertEquals("", rows.get(1));
        assertEquals("line-001", rows.get(2));
        assertEquals("line-100", rows.get(101));
        assertEquals("… +3 行 (ctrl+o 查看全部)", rows.get(102));
    }

    @Test
    void expandedCodeRowsPreserveSyntaxStyleSpans() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "java",
                "public String value = \"pai\"; // note");

        List<AttributedString> rows = block.render(120, FoldState.EXPANDED);

        AttributedString code = rows.stream()
                .filter(line -> line.toString().contains("public String"))
                .findFirst().orElseThrow();
        AttributedStyle keyword = code.styleAt(code.toString().indexOf("public"));
        AttributedStyle string = code.styleAt(code.toString().indexOf("\"pai\""));
        AttributedStyle comment = code.styleAt(code.toString().indexOf("// note"));
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(keyword, string);
        assertNotEquals(string, comment);
    }

    /**
     * The block itself memoizes nothing — the memoization moved to the view (ADR 0016 D1). This test
     * used to assert the opposite ({@code assertSame} on the block, 100 cache hits on the block); the
     * contract it pinned is superseded, and the property it was protecting (a render storm costs one
     * layout) now belongs to whichever view is rendering.
     */
    @Test
    void completedHighlightedRowsAreMemoizedByTheViewAndNotByTheBlock() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "json", "{\"count\": 42}");

        List<AttributedString> first = block.render(120, FoldState.EXPANDED);
        assertNotSame(first, block.render(120, FoldState.EXPANDED),
                "the block lays its source out afresh; it holds no rows");

        EntryRendering view = block.newRendering();
        List<AttributedString> viewRows = view.rows(120, FoldState.EXPANDED);
        for (int frame = 0; frame < 100; frame++) {
            assertSame(viewRows, view.rows(120, FoldState.EXPANDED));
        }
        assertEquals(first.stream().map(AttributedString::toString).toList(),
                viewRows.stream().map(AttributedString::toString).toList());
    }
}
