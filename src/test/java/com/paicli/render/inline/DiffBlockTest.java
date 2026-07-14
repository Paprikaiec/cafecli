package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffBlockTest {

    @Test
    void rendererOwnsDiffAsRetainedFoldableBlockToggledByCtrlO() {
        Terminal terminal = terminal(80, 24);
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
            renderer.appendDiff("src/App.java", "old\n", "new\n");
            pump.pump();

            RetainedWindow.TurnView turn = renderer.retainedTurns().get(0);
            assertEquals(1, turn.blocks().size());
            StripEntry retained = turn.blocks().get(0);
            assertInstanceOf(DiffBlock.class, retained);
            assertTrue(retained.isFoldable());
            assertEquals(FoldState.COLLAPSED, retained.foldState());

            assertTrue(renderer.toggleLastBlock());
            pump.pump();

            assertEquals(FoldState.EXPANDED, retained.foldState());
            assertTrue(retained.render(80, FoldState.EXPANDED).stream()
                    .anyMatch(line -> line.toString().contains("+new")));
        }
    }

    @Test
    void collapsedHeaderCountsLogicalChangesAtFileBoundaries() {
        Terminal terminal = terminal(80, 24);

        assertEquals("± new.txt (+2/-0)", collapsed(
                new DiffBlock(terminal, "new.txt", null, "one\ntwo\n")));
        assertEquals("± gone.txt (+0/-2)", collapsed(
                new DiffBlock(terminal, "gone.txt", "one\ntwo\n", null)));
        assertEquals("± same.txt (+0/-0)", collapsed(
                new DiffBlock(terminal, "same.txt", "same\n", "same\n")));
    }

    @Test
    void expandedBoundaryHunksUseUnifiedDiffZeroCoordinates() {
        Terminal terminal = terminal(80, 24);

        assertEquals("@@ -0,0 +1,2 @@", expanded(new DiffBlock(
                terminal, "new.txt", null, "one\ntwo\n")).get(0));
        assertEquals("@@ -1,2 +0,0 @@", expanded(new DiffBlock(
                terminal, "gone.txt", "one\ntwo\n", null)).get(0));
    }

    @Test
    void color256ExpandedChangesUseFullWidthBackgroundRows() {
        Terminal terminal = terminal(20, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(256);
        DiffBlock block = new DiffBlock(terminal, "App.java",
                "keep\nold\ntail\n", "keep\nnew\ntail\n");

        var rows = block.render(20, FoldState.EXPANDED);
        var hunk = rows.stream().filter(line -> line.toString().startsWith("@@")).findFirst().orElseThrow();
        var deleted = rows.stream().filter(line -> line.toString().startsWith("-old")).findFirst().orElseThrow();
        var added = rows.stream().filter(line -> line.toString().startsWith("+new")).findFirst().orElseThrow();
        var context = rows.stream().filter(line -> line.toString().startsWith(" keep")).findFirst().orElseThrow();

        assertEquals(20, deleted.columnLength());
        assertEquals(20, added.columnLength());
        assertTrue(deleted.subSequence(19, 20).toAnsi().contains("48;5;52m"),
                deleted.subSequence(19, 20).toAnsi());
        assertTrue(added.subSequence(19, 20).toAnsi().contains("48;5;22m"),
                added.subSequence(19, 20).toAnsi());
        assertTrue(deleted.toAnsi().contains("48;5;"), deleted.toAnsi());
        assertTrue(added.toAnsi().contains("48;5;"), added.toAnsi());
        assertTrue(hunk.toAnsi().contains("36m") || hunk.toAnsi().contains("38;5;6m"), hunk.toAnsi());
        assertTrue(!context.toAnsi().contains("48;5;"), context.toAnsi());
    }

    @Test
    void color16FallsBackToForegroundWithoutRawEscapeLeakage() {
        Terminal terminal = terminal(20, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        DiffBlock block = new DiffBlock(terminal, "App.java", "old\n", "new\n");

        var rows = block.render(20, FoldState.EXPANDED);
        var deleted = rows.stream().filter(line -> line.toString().startsWith("-old")).findFirst().orElseThrow();
        var added = rows.stream().filter(line -> line.toString().startsWith("+new")).findFirst().orElseThrow();

        assertEquals(4, deleted.columnLength());
        assertEquals(4, added.columnLength());
        assertTrue(deleted.toAnsi().contains("31m"), deleted.toAnsi());
        assertTrue(added.toAnsi().contains("32m"), added.toAnsi());
        assertFalse(deleted.toAnsi().contains("48;5;"), deleted.toAnsi());
        assertFalse(added.toAnsi().contains("48;5;"), added.toAnsi());
        assertFalse(deleted.toString().contains("\u001b"), deleted.toString());
        assertFalse(added.toString().contains("\u001b"), added.toString());
    }

    @Test
    void expandedLcsHunksMatchLegacyGoldenOrderingAndGrouping() {
        String before = "a\nb\nc\nd\ne\nf\ng";
        String after = "a\nb\nX\nd\ne\nY\ng";
        java.util.List<String> legacy = java.util.List.of(
                "@@ -1,7 +1,7 @@", " a", " b", "-c", "+X",
                " d", " e", "-f", "+Y", " g");

        Terminal terminal = terminal(80, 24);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(16);
        java.util.List<String> typed = expanded(new DiffBlock(
                terminal, "App.java", before, after));

        assertEquals(legacy, typed);
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

            StripEntry retained = renderer.retainedTurns().get(0).blocks().get(0);
            String expanded = retained.renderRetained(40, FoldState.EXPANDED).stream()
                    .map(org.jline.utils.AttributedString::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertTrue(expanded.contains("-old value"), expanded);
            assertTrue(expanded.contains("+new value"), expanded);
        }
    }

    private static java.util.List<String> expanded(DiffBlock block) {
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
