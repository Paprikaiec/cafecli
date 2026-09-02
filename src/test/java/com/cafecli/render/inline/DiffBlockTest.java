package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffBlockTest {

    @Test
    void defaultDiffViewCapsAtOneHundredSourceLinesAndReportsOverflow() {
        Terminal terminal = terminal(80, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        String after = java.util.stream.IntStream.rangeClosed(1, 105)
                .mapToObj(line -> "line-%03d".formatted(line))
                .collect(java.util.stream.Collectors.joining("\n"));
        DiffBlock block = new DiffBlock(terminal, "new.txt", null, after);

        var rows = block.render(80).stream()
                .map(org.jline.utils.AttributedString::toString)
                .toList();

        // Plain "Update" summary header, a right-aligned line-number gutter (3 wide for 105 lines),
        // "+" markers, and the preserved overflow line — no "▎" gutter bar, no "@@" hunk headers.
        assertEquals(102, rows.size());
        assertEquals("Update new.txt  +105 -0", rows.get(0));
        assertEquals("  1  + line-001", rows.get(1));
        assertEquals("100  + line-100", rows.get(100));
        assertEquals("… +5 行 (ctrl+o 查看全部)", rows.get(101));
        assertTrue(rows.stream().noneMatch(row -> row.contains("@@")), rows.toString());
        assertTrue(rows.stream().noneMatch(row -> row.contains("▎")), rows.toString());
        assertTrue(rows.stream().noneMatch(row -> row.contains("±")), rows.toString());
    }

    @Test
    void diffProvidesFullExpandedViewWithoutMutatingItsDefaultState() {
        Terminal terminal = terminal(80, 24);
        DiffBlock block = new DiffBlock(terminal, "src/App.java", "old\n", "new\n");

        assertEquals(FoldState.COLLAPSED, block.foldState());
        assertTrue(block.render(80, FoldState.EXPANDED).stream()
                .anyMatch(line -> line.toString().equals("1  + new")));
        assertEquals(FoldState.COLLAPSED, block.foldState());
    }

    @Test
    void collapsedHeaderUsesPlainUpdateSummaryAtFileBoundaries() {
        Terminal terminal = terminal(80, 24);

        assertEquals("Update new.txt  +2 -0", collapsed(
                new DiffBlock(terminal, "new.txt", null, "one\ntwo\n")));
        assertEquals("Update gone.txt  +0 -2", collapsed(
                new DiffBlock(terminal, "gone.txt", "one\ntwo\n", null)));
        assertEquals("Update same.txt  +0 -0", collapsed(
                new DiffBlock(terminal, "same.txt", "same\n", "same\n")));
        assertEquals("Update (unnamed)  +1 -0", collapsed(
                new DiffBlock(terminal, "   ", null, "x\n")));
    }

    @Test
    void boundaryDiffsNumberRowsFromTheSurvivingFileWithoutHunkHeaders() {
        Terminal terminal = terminal(80, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);

        // New file: rows are added and numbered from the after-file (1, 2), no "@@" line.
        assertEquals(List.of("1  + one", "2  + two"),
                expanded(new DiffBlock(terminal, "new.txt", null, "one\ntwo\n")));
        // Deleted file: rows are removed and numbered from the before-file (1, 2), no "@@" line.
        assertEquals(List.of("1  - one", "2  - two"),
                expanded(new DiffBlock(terminal, "gone.txt", "one\ntwo\n", null)));
    }

    @Test
    void color256ExpandedChangesUseFullWidthBackgroundRows() {
        Terminal terminal = terminal(20, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(256);
        DiffBlock block = new DiffBlock(terminal, "App.java",
                "keep\nold\ntail\n", "keep\nnew\ntail\n");

        var rows = block.render(20, FoldState.EXPANDED);
        var deleted = rows.stream().filter(line -> line.toString().contains("- old")).findFirst().orElseThrow();
        var added = rows.stream().filter(line -> line.toString().contains("+ new")).findFirst().orElseThrow();
        var context = rows.stream().filter(line -> line.toString().contains("keep")).findFirst().orElseThrow();

        assertTrue(rows.stream().noneMatch(line -> line.toString().contains("@@")), rows.toString());
        assertEquals(20, deleted.columnLength());
        assertEquals(20, added.columnLength());
        assertNotEquals(AttributedStyle.DEFAULT, deleted.styleAt(0));
        assertNotEquals(AttributedStyle.DEFAULT, added.styleAt(0));
        assertTrue(deleted.subSequence(19, 20).toAnsi().contains("48;5;52m"),
                deleted.subSequence(19, 20).toAnsi());
        assertTrue(added.subSequence(19, 20).toAnsi().contains("48;5;22m"),
                added.subSequence(19, 20).toAnsi());
        assertTrue(deleted.toAnsi().contains("48;5;"), deleted.toAnsi());
        assertTrue(added.toAnsi().contains("48;5;"), added.toAnsi());
        assertFalse(context.toAnsi().contains("48;5;"), context.toAnsi());
    }

    @Test
    void color16FallsBackToForegroundWithoutRawEscapeLeakage() {
        Terminal terminal = terminal(20, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        DiffBlock block = new DiffBlock(terminal, "App.java", "old\n", "new\n");

        var rows = block.render(20, FoldState.EXPANDED);
        var deleted = rows.stream().filter(line -> line.toString().contains("- old")).findFirst().orElseThrow();
        var added = rows.stream().filter(line -> line.toString().contains("+ new")).findFirst().orElseThrow();

        assertEquals("1  - old", deleted.toString());
        assertEquals("1  + new", added.toString());
        assertTrue(deleted.toAnsi().contains("31m"), deleted.toAnsi());
        assertTrue(added.toAnsi().contains("32m"), added.toAnsi());
        assertFalse(deleted.toAnsi().contains("48;5;"), deleted.toAnsi());
        assertFalse(added.toAnsi().contains("48;5;"), added.toAnsi());
        assertTrue(deleted.toString().chars().noneMatch(c -> c == 27), deleted.toString());
        assertTrue(added.toString().chars().noneMatch(c -> c == 27), added.toString());
    }

    @Test
    void expandedLcsHunksRenderGutteredRowsInLegacyOrderingWithoutHunkHeaders() {
        String before = "a\nb\nc\nd\ne\nf\ng";
        String after = "a\nb\nX\nd\ne\nY\ng";
        List<String> expected = List.of(
                "1    a", "2    b", "3  - c", "3  + X",
                "4    d", "5    e", "6  - f", "6  + Y", "7    g");

        Terminal terminal = terminal(80, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        List<String> typed = expanded(new DiffBlock(terminal, "App.java", before, after));

        assertEquals(expected, typed);
    }

    @Test
    void nonContiguousHunksAreSeparatedBySingleDimGap() {
        Terminal terminal = terminal(80, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        String before = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj";
        String after = "a\nX\nc\nd\ne\nf\ng\nh\nY\nj";

        List<String> rows = expanded(new DiffBlock(terminal, "App.java", before, after));

        // Two-digit gutter (10 lines), two hunks separated by exactly one dim "⋮" gap, no "@@".
        assertEquals(List.of(
                " 1    a", " 2  - b", " 2  + X", " 3    c",
                " ⋮",
                " 7    g", " 8    h", " 9  - i", " 9  + Y", "10    j"), rows);
        assertEquals(1, rows.stream().filter(row -> row.equals(" ⋮")).count(), rows.toString());
        assertTrue(rows.stream().noneMatch(row -> row.contains("@@")), rows.toString());
    }

    @Test
    void retainedDiffSourceSurvivesLiveStripFreeze() {
        Terminal terminal = terminal(40, 8);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.beginTurn();
            renderer.appendUserPrompt("edit App.java");
            renderer.appendDiff("App.java", "old value\n", "new value\n");
            renderer.endTurn();
            pump.pump();

            // Item 0 is the submitted prompt echo; item 1 is the diff it produced.
            StripEntry retained = renderer.retainedItems().get(1);
            String expanded = retained.renderRetained(40, FoldState.EXPANDED).stream()
                    .map(org.jline.utils.AttributedString::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertTrue(expanded.contains("- old value"), expanded);
            assertTrue(expanded.contains("+ new value"), expanded);
        }
    }

    private static List<String> expanded(DiffBlock block) {
        return block.render(80, FoldState.EXPANDED).stream()
                .map(org.jline.utils.AttributedString::toString)
                .toList();
    }

    private static String collapsed(DiffBlock block) {
        return block.render(80, FoldState.COLLAPSED).get(0).toString();
    }

    private static Terminal terminal(int columns, int rows) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(columns, rows);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        return terminal;
    }
}
