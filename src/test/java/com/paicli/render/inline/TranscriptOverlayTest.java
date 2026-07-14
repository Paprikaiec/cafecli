package com.paicli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscriptOverlayTest {

    @Test
    void fullScreenDisplayRendersRetainedPromptsAndBlocks() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        CodeBlock block = new CodeBlock(fixture.terminal, "java", "int answer = 42;");
        List<RetainedWindow.TurnView> turns = List.of(
                new RetainedWindow.TurnView("show answer", List.of(block)));

        new TranscriptOverlay(fixture.terminal).open(turns);

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("show answer"), visible);
        assertTrue(visible.contains("int answer = 42;"), visible);
    }

    @Test
    void alternateScreenIsAlwaysLeftWithoutChangingMainScrollback() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        fixture.writer.print("main-scrollback\n");
        fixture.writer.flush();

        new TranscriptOverlay(fixture.terminal).open(List.of());

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\u001b[?1049h"), bytes);
        assertTrue(bytes.contains("\u001b[?1049l"), bytes);
        assertEquals(1, occurrences(bytes, "main-scrollback"));
        assertTrue(bytes.indexOf("main-scrollback") < bytes.indexOf("\u001b[?1049h"), bytes);
    }

    @Test
    void foldTogglePersistsAcrossCloseAndReopenWhilePromptRemainsVisible() throws Exception {
        TerminalFixture fixture = terminalReturning('\r', 'q', 'q');
        ToolCallBlock block = new ToolCallBlock(fixture.terminal, List.of(
                new com.paicli.llm.LlmClient.ToolCall("call-1",
                        new com.paicli.llm.LlmClient.ToolCall.Function(
                                "read_file", "{\"path\":\"old.txt\"}")),
                new com.paicli.llm.LlmClient.ToolCall("call-2",
                        new com.paicli.llm.LlmClient.ToolCall.Function(
                                "read_file", "{\"path\":\"second.txt\"}"))));
        List<RetainedWindow.TurnView> turns = List.of(
                new RetainedWindow.TurnView("older prompt", List.of(block)));

        TranscriptOverlay overlay = new TranscriptOverlay(fixture.terminal);
        overlay.open(turns);
        fixture.sink.reset();
        overlay.open(turns);

        fixture.writer.flush();
        String reopened = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(reopened.contains("older prompt"), reopened);
        assertTrue(reopened.contains("old.txt"), reopened);
        assertEquals(FoldState.EXPANDED, block.foldState());
    }

    @Test
    void escapeSequenceReaderDeliversAnArrowAsOneNavigationKey() throws Exception {
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenReturn(27);
        Mockito.when(reader.read(25)).thenReturn((int) '[', (int) 'B', NonBlockingReader.READ_EXPIRED);

        String key = TranscriptOverlay.readKey(reader);

        assertEquals("\u001b[B", key);
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode(key));
    }

    @Test
    void expandedBlockIsClippedToThePhysicalFullScreenViewport() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'q');
        String codeText = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "line-" + i).collect(java.util.stream.Collectors.joining("\n"));
        CodeBlock code = new CodeBlock(fixture.terminal, "text", codeText);
        code.persistFoldState(FoldState.EXPANDED);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("long", List.of(code))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("line-1"), visible);
        assertEquals(false, visible.contains("line-30"), visible);
    }

    @Test
    void exceptionalRenderStillLeavesAlternateScreenAndRestoresRawAttributes() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        org.jline.terminal.Attributes original = new org.jline.terminal.Attributes();
        Mockito.when(fixture.terminal.enterRawMode()).thenReturn(original);
        StripEntry broken = new StripEntry() {
            @Override
            public List<org.jline.utils.AttributedString> render(int width) {
                throw new IllegalStateException("broken block");
            }

            @Override
            public List<org.jline.utils.AttributedString> freezeTopRows(int rows, int width) {
                return List.of();
            }
        };

        assertThrows(IllegalStateException.class, () -> new TranscriptOverlay(fixture.terminal)
                .open(List.of(new RetainedWindow.TurnView("prompt", List.of(broken)))));

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\u001b[?1049h"), bytes);
        assertTrue(bytes.contains("\u001b[?1049l"), bytes);
        Mockito.verify(fixture.terminal).setAttributes(original);
    }

    @Test
    void failedAlternateScreenEntryFlushStillAttemptsToLeaveAlternateScreen() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        Mockito.doThrow(new IllegalStateException("entry flush failed"))
                .doNothing().when(fixture.terminal).flush();

        assertThrows(IllegalStateException.class,
                () -> new TranscriptOverlay(fixture.terminal).open(List.of()));

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\u001b[?1049h"), bytes);
        assertTrue(bytes.contains("\u001b[?1049l"), bytes);
    }

    @Test
    void failedRawAttributeRestoreStillLeavesAlternateScreen() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        org.jline.terminal.Attributes original = new org.jline.terminal.Attributes();
        Mockito.when(fixture.terminal.enterRawMode()).thenReturn(original);
        Mockito.doThrow(new IllegalStateException("restore failed"))
                .when(fixture.terminal).setAttributes(original);

        assertThrows(IllegalStateException.class,
                () -> new TranscriptOverlay(fixture.terminal).open(List.of()));

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\u001b[?1049h"), bytes);
        assertTrue(bytes.contains("\u001b[?1049l"), bytes);
    }

    @Test
    void overlayCanToggleABlockAlreadyFrozenFromTheLiveStrip() throws Exception {
        TerminalFixture fixture = terminalReturning('\r', 'q');
        CodeBlock code = new CodeBlock(fixture.terminal, "java",
                String.join("\n", java.util.Collections.nCopies(12, "frozen-line")));
        code.freezeTopRows(1, 80);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("old turn", List.of(code))));

        assertEquals(FoldState.EXPANDED, code.foldState());
    }

    @Test
    void overlayCanToggleReasoningAlreadyFrozenFromTheLiveStrip() throws Exception {
        TerminalFixture fixture = terminalReturning('\r', 'q');
        ReasoningBlock reasoning = new ReasoningBlock(
                fixture.terminal, "old reasoning first\nold reasoning second\n");
        reasoning.freezeTopRows(1, 80);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("old turn", List.of(reasoning))));

        fixture.writer.flush();
        assertEquals(FoldState.EXPANDED, reasoning.foldState());
        assertTrue(fixture.sink.toString(StandardCharsets.UTF_8)
                .contains("old reasoning first"));
    }

    @Test
    void promptOnlyRetainedTurnRemainsVisibleButHasNoBlockCursorTarget() throws Exception {
        TerminalFixture fixture = terminalReturning('q');

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("command-only prompt", List.of())));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("command-only prompt"), visible);
        assertEquals(false, visible.contains("› > command-only prompt"), visible);
    }

    @Test
    void movingPastATallBlockKeepsTheSelectedNextBlockVisible() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'j', 'q');
        CodeBlock tall = new CodeBlock(fixture.terminal, "text",
                String.join("\n", java.util.Collections.nCopies(30, "TALL")));
        tall.persistFoldState(FoldState.EXPANDED);
        CodeBlock target = new CodeBlock(fixture.terminal, "text", "SECOND-TARGET");

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("turn", List.of(tall, target))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("SECOND-TARGET"), visible);
        assertTrue(visible.contains("›"), visible);
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static TerminalFixture terminalReturning(char key) throws Exception {
        return terminalReturning(new char[]{key});
    }

    private static TerminalFixture terminalReturning(char... keys) throws Exception {
        return terminalReturningAtHeight(24, keys);
    }

    private static TerminalFixture terminalReturningAtHeight(int height, char... keys) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(80, height);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(height);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Integer[] values = new Integer[keys.length];
        for (int i = 0; i < keys.length; i++) {
            values[i] = (int) keys[i];
        }
        Mockito.when(reader.read()).thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
    }

    private record TerminalFixture(Terminal terminal, ByteArrayOutputStream sink, PrintWriter writer) {
    }
}
