package com.cafecli.render.inline;

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
    void footerDescribesTheReadOnlyExpandedOverlayControls() throws Exception {
        TerminalFixture fixture = terminalReturning('q');

        new TranscriptOverlay(fixture.terminal).open(List.of());

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("wheel/j/k/↑/↓ line"), visible);
        assertTrue(visible.contains("Ctrl+T thinking"), visible);
        assertTrue(visible.contains("Ctrl+O/q/ESC close"), visible);
        assertEquals(false, visible.contains("Enter/Ctrl+O fold"), visible);
    }

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
    void overlayResetsScrollRegionAfterEnteringAlternateScreen() throws Exception {
        TerminalFixture fixture = terminalReturning('q');

        new TranscriptOverlay(fixture.terminal).open(List.of());

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        int alternate = bytes.indexOf("[?1049h");
        int resetRegion = bytes.indexOf("[r");
        assertTrue(alternate >= 0, bytes);
        assertTrue(resetRegion > alternate,
                "the fullscreen overlay must reset any bottom scroll region after entering alt screen: "
                        + bytes);
    }

    @Test
    void mouseTrackingIsEnabledInsideTheOverlayAndResetOnClose() throws Exception {
        TerminalFixture fixture = terminalReturning('q');

        new TranscriptOverlay(fixture.terminal).open(List.of());

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        int alternate = bytes.indexOf("\u001b[?1049h");
        int basicMouse = bytes.indexOf("\u001b[?1000h");
        int sgrMouse = bytes.indexOf("\u001b[?1006h");
        int resetSgr = bytes.indexOf("\u001b[?1006l");
        int resetBasic = bytes.indexOf("\u001b[?1000l");
        int leave = bytes.indexOf("\u001b[?1049l");
        assertTrue(alternate < basicMouse && basicMouse < sgrMouse, bytes);
        assertTrue(sgrMouse < resetSgr && resetSgr < resetBasic && resetBasic < leave, bytes);
    }

    @Test
    void overlayRendersFoldablesExpandedWithoutPersistingTheirState() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        ToolCallBlock block = new ToolCallBlock(fixture.terminal, List.of(
                new com.cafecli.llm.LlmClient.ToolCall("call-1",
                        new com.cafecli.llm.LlmClient.ToolCall.Function(
                                "read_file", "{\"path\":\"old.txt\"}")),
                new com.cafecli.llm.LlmClient.ToolCall("call-2",
                        new com.cafecli.llm.LlmClient.ToolCall.Function(
                                "read_file", "{\"path\":\"second.txt\"}"))));
        List<RetainedWindow.TurnView> turns = List.of(
                new RetainedWindow.TurnView("older prompt", List.of(block)));

        new TranscriptOverlay(fixture.terminal).open(turns);

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("older prompt"), visible);
        assertTrue(visible.contains("old.txt"), visible);
        assertEquals(FoldState.COLLAPSED, block.foldState());
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
    void escapeSequenceReaderDeliversACompleteSgrWheelEvent() throws Exception {
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenReturn(27);
        Mockito.when(reader.read(25)).thenReturn(
                (int) '[', (int) '<', (int) '6', (int) '5', (int) ';',
                (int) '2', (int) '0', (int) ';', (int) '5', (int) 'M',
                NonBlockingReader.READ_EXPIRED);

        String key = TranscriptOverlay.readKey(reader);

        assertEquals("\u001b[<65;20;5M", key);
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
        assertTrue(bytes.contains("\u001b[?1006l"), bytes);
        assertTrue(bytes.contains("\u001b[?1000l"), bytes);
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
    void overlayExpandsCodeAlreadyFrozenFromTheLiveStripWithoutMutatingIt() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        CodeBlock code = new CodeBlock(fixture.terminal, "java",
                String.join("\n", java.util.Collections.nCopies(12, "frozen-line")));
        code.freezeTopRows(1, 80);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("old turn", List.of(code))));

        fixture.writer.flush();
        assertEquals(FoldState.COLLAPSED, code.foldState());
        assertTrue(fixture.sink.toString(StandardCharsets.UTF_8).contains("frozen-line"));
    }

    @Test
    void overlayKeepsThinkingCollapsedByDefaultWithoutMutatingIt() throws Exception {
        TerminalFixture fixture = terminalReturning('q');
        ReasoningBlock reasoning = new ReasoningBlock(
                fixture.terminal, "old reasoning first\nold reasoning second\n");
        reasoning.freezeTopRows(1, 80);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("old turn", List.of(reasoning))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertEquals(FoldState.COLLAPSED, reasoning.foldState());
        assertTrue(visible.contains("思考 2 行"), visible);
        assertEquals(false, visible.contains("old reasoning first"), visible);
    }

    @Test
    void ctrlTInsideOverlayRevealsThinkingBodyWithoutMutatingTheBlock() throws Exception {
        TerminalFixture fixture = terminalReturning((char) 20, 'q');
        ReasoningBlock reasoning = new ReasoningBlock(
                fixture.terminal, "old reasoning first\nold reasoning second\n");

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("old turn", List.of(reasoning))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("old reasoning first"), visible);
        assertEquals(FoldState.COLLAPSED, reasoning.foldState());
    }

    @Test
    void togglingThinkingRemeasuresHeightSoLastKeyReachesTheExpandedTail() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, (char) 20, 'G', 'q');
        String reasoningText = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> "reason-" + i)
                .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL-THINK";
        ReasoningBlock reasoning = new ReasoningBlock(fixture.terminal, reasoningText);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("turn", List.of(reasoning))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("TAIL-THINK"), visible);
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
    void lastKeyShowsTheTailOfOneTallExpandedBlock() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'G', 'q');
        String codeText = java.util.stream.IntStream.rangeClosed(1, 29)
                .mapToObj(i -> "line-" + i)
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\nTAIL-SENTINEL";
        CodeBlock tall = new CodeBlock(fixture.terminal, "text", codeText);

        new TranscriptOverlay(fixture.terminal).open(List.of(
                new RetainedWindow.TurnView("turn", List.of(tall))));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("TAIL-SENTINEL"), visible);
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
