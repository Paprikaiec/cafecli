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

        new TranscriptOverlay(fixture.terminal)
                .open(List.of(prompt(fixture.terminal, "show answer"), block));

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
        new TranscriptOverlay(fixture.terminal)
                .open(List.of(prompt(fixture.terminal, "older prompt"), block));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("older prompt"), visible);
        assertTrue(visible.contains("old.txt"), visible);
        assertEquals(FoldState.COLLAPSED, block.foldState());
    }

    @Test
    void escapeSequenceReaderDeliversAnArrowAsOneNavigationKey() throws Exception {
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenReturn(27);
        Mockito.when(reader.read(25)).thenReturn((int) '[', (int) 'B', NonBlockingReader.READ_EXPIRED);

        String key = TranscriptOverlay.readKey(reader);

        assertEquals("\u001b[B", key);
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode(key));
    }

    @Test
    void escapeSequenceReaderDeliversACompleteSgrWheelEvent() throws Exception {
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenReturn(27);
        Mockito.when(reader.read(25)).thenReturn(
                (int) '[', (int) '<', (int) '6', (int) '5', (int) ';',
                (int) '2', (int) '0', (int) ';', (int) '5', (int) 'M',
                NonBlockingReader.READ_EXPIRED);

        String key = TranscriptOverlay.readKey(reader);

        assertEquals("\u001b[<65;20;5M", key);
        assertEquals(TranscriptOverlayModel.Action.DOWN, TranscriptOverlayModel.decode(key));
    }

    /**
     * A block taller than the screen is clipped to the physical viewport rather than drawn whole.
     *
     * <p>The anchor is the <b>tail</b>, not the top (ADR 0010 amendment A13): the overlay opens where
     * the session is now, which is what makes live following the default rather than a mode the user has
     * to find. This assertion is the inverse of the one it replaces — same property, other end.
     */
    @Test
    void expandedBlockIsClippedToThePhysicalFullScreenViewport() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'q');
        String codeText = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "line-" + i).collect(java.util.stream.Collectors.joining("\n"));
        CodeBlock code = new CodeBlock(fixture.terminal, "text", codeText);
        code.persistFoldState(FoldState.EXPANDED);

        new TranscriptOverlay(fixture.terminal).open(List.of(code));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("line-30"), visible);
        assertEquals(false, visible.contains("line-1\n"), visible);
    }

    /**
     * The overlay opens at the tail, with no keystroke (ADR 0010 amendment A13).
     *
     * <p>The reason to open it during a turn is to see what is happening now, so opening at the top of
     * a long session and making the user find the end would leave live following unreachable by
     * default. The cost accepted for this is one full measure of the document at open — the same measure
     * a jump to the end already pays (A10).
     */
    @Test
    void overlayOpensAtTheTailOfTheSession() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'q');
        String codeText = java.util.stream.IntStream.rangeClosed(1, 29)
                .mapToObj(i -> "line-" + i)
                .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL-SENTINEL";
        CodeBlock tall = new CodeBlock(fixture.terminal, "text", codeText);

        new TranscriptOverlay(fixture.terminal).open(List.of(tall));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("TAIL-SENTINEL"), visible);
    }

    /**
     * Output committed while the overlay is open reaches the screen without the user reopening it
     * (ADR 0010 amendment A13) — this is the whole point of being able to open it mid-turn.
     *
     * <p>What is asserted here is only that the loop re-reads the log and repaints. The
     * follow-versus-browse distinction is a property of the pure model and is asserted there, with no
     * terminal.
     */
    @Test
    void outputCommittedWhileTheOverlayIsOpenAppearsWithoutReopening() throws Exception {
        java.util.List<StripEntry> log = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean committed =
                new java.util.concurrent.atomic.AtomicBoolean();
        TerminalFixture fixture = terminalPollingOnce(committed);
        log.add(prompt(fixture.terminal, "already committed"));
        StripEntry arriving = prompt(fixture.terminal, "ARRIVED-MID-TURN");

        new TranscriptOverlay(fixture.terminal).open(() -> {
            if (committed.get() && log.size() == 1) {
                log.add(arriving);
            }
            return List.copyOf(log);
        });

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("ARRIVED-MID-TURN"), visible);
    }

    /**
     * ESC closes the overlay and does nothing else (ADR 0010 amendment A13, criterion 7).
     *
     * <p>Now that the overlay opens during a running turn, ESC is ambiguous for the first time: it is
     * also the turn's cancel key. It resolves in favour of the overlay by construction rather than by
     * arbitration — the overlay holds the terminal and reads its own keys on the input thread, so the
     * byte is consumed here and the turn pump never sees it. Cancelling while browsing means closing
     * first, which is accepted.
     */
    @Test
    void escapeClosesTheOverlayAndLeavesTheTurnAlone() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        // A standalone ESC: the byte, then a follow-up read that finds nothing after it.
        Mockito.when(reader.read(Mockito.anyLong()))
                .thenReturn(27, NonBlockingReader.READ_EXPIRED);
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);

        new TranscriptOverlay(terminal).open(List.of(prompt(terminal, "browsing mid-turn")));

        writer.flush();
        String bytes = sink.toString(StandardCharsets.UTF_8);
        assertTrue(bytes.contains("[?1049l"), "ESC leaves the alternate screen: " + bytes);
        Mockito.verify(reader, Mockito.atLeastOnce()).read(Mockito.anyLong());
    }

    /**
     * A pending [[审批应答]] closes the [[转写浏览层]] (ADR 0010 amendment A14), with no key pressed.
     *
     * <p>The user is put back on the primary screen because that is where the approval card and the
     * [[输入框]] that answers it live — the overlay has no input box by construction (A13), and the
     * only reply path that would not need one, single-key approval, was rejected for needing a second
     * terminal reader. Letting the approval wait instead is the worse option it looks like it is: the
     * worker parks on a get with no timeout, so it stalls for as long as the user keeps reading.
     *
     * <p>What is asserted is that the user never had to do anything: the fixture holds a {@code q} in
     * reserve as the manual close, and the overlay must be gone before that key is ever consumed.
     */
    @Test
    void aPendingApprovalClosesTheOverlayWithoutTheUserPressingAnything() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean approvalPending =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean manualCloseUsed =
                new java.util.concurrent.atomic.AtomicBoolean();
        TerminalFixture fixture = terminalRaisingAnApproval(approvalPending, manualCloseUsed);

        new TranscriptOverlay(fixture.terminal)
                .open(() -> List.of(prompt(fixture.terminal, "reading while a tool waits")),
                        approvalPending::get);

        fixture.writer.flush();
        String bytes = fixture.sink.toString(StandardCharsets.UTF_8);
        assertEquals(false, manualCloseUsed.get(),
                "the approval closes the overlay by itself; the user pressed nothing: " + bytes);
        assertTrue(bytes.contains("reading while a tool waits"), bytes);
        assertTrue(bytes.contains("[?1049l"),
                "and the user is left on the primary screen, where the approval card is: " + bytes);
    }

    /**
     * The reader's place is kept when the overlay closes and restored when it reopens (ADR 0010
     * amendment A14): being interrupted by an [[审批应答]] must cost nothing.
     *
     * <p>The position is carried out of the modal and back into it, rather than stored anywhere: the
     * overlay stays a function of what it is given. What it carries is a position <em>and</em> whether
     * that position was the tail, because at the tail the position is the "following" mode (A13) — a
     * reader who was watching output arrive resumes watching it, not the rows that were the end when
     * they were interrupted.
     */
    @Test
    void theReadingPositionIsRestoredWhenTheOverlayIsReopened() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'g', 'q');
        CodeBlock tall = new CodeBlock(fixture.terminal, "text",
                "HEAD-SENTINEL\n" + java.util.stream.IntStream.rangeClosed(2, 29)
                        .mapToObj(i -> "line-" + i)
                        .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL-SENTINEL");

        TranscriptOverlay.ReadingPosition scrolledToTheTop =
                new TranscriptOverlay(fixture.terminal).open(List.of(tall));

        fixture.writer.flush();
        fixture.sink.reset();
        new TranscriptOverlay(fixture.terminal).open(
                () -> List.of(tall), () -> false, scrolledToTheTop);

        fixture.writer.flush();
        String reopened = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(reopened.contains("HEAD-SENTINEL"),
                "reopening resumes where the user was reading: " + reopened);
        assertEquals(false, reopened.contains("TAIL-SENTINEL"),
                "and does not jump back to the tail: " + reopened);
    }

    /**
     * The whole interruption, end to end (ADR 0010 amendment A14): reading history, an [[审批应答]]
     * takes the screen back, the user answers it, the session keeps producing, and reopening shows the
     * same page — with everything that arrived meanwhile now part of the document.
     */
    @Test
    void answeringTheApprovalAndReopeningShowsTheSamePlaceAndWhatArrivedSince() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean approvalPending =
                new java.util.concurrent.atomic.AtomicBoolean();
        TerminalFixture fixture = terminalScripted(6, approvalPending);
        java.util.List<StripEntry> log = new java.util.ArrayList<>();
        log.add(new CodeBlock(fixture.terminal, "text",
                "HEAD-SENTINEL\n" + java.util.stream.IntStream.rangeClosed(2, 29)
                        .mapToObj(i -> "line-" + i)
                        .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL-SENTINEL"));

        // Reading history, scrolled to the top, when a tool asks for approval.
        TranscriptOverlay.ReadingPosition interrupted = new TranscriptOverlay(fixture.terminal)
                .open(() -> List.copyOf(log), approvalPending::get);

        // The user answers it on the primary screen, and the turn carries on producing output.
        approvalPending.set(false);
        log.add(prompt(fixture.terminal, "ANSWERED-AND-CONTINUED"));

        fixture.writer.flush();
        fixture.sink.reset();
        new TranscriptOverlay(fixture.terminal)
                .open(() -> List.copyOf(log), () -> false, interrupted);

        fixture.writer.flush();
        String reopened = fixture.sink.toString(StandardCharsets.UTF_8);
        int samePlace = reopened.indexOf("HEAD-SENTINEL");
        int arrivedSince = reopened.indexOf("ANSWERED-AND-CONTINUED");
        assertTrue(samePlace >= 0, "the overlay reopens on the page the user was reading: " + reopened);
        assertTrue(arrivedSince > samePlace,
                "and what arrived while it was closed is there to be reached: " + reopened);
    }

    /**
     * The key script for the whole interruption: {@code g} to the top, one poll that raises the
     * approval, then {@code G} and {@code q} for the reopened overlay. Anything beyond it closes.
     */
    private static TerminalFixture terminalScripted(
            int height, java.util.concurrent.atomic.AtomicBoolean approvalPending) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(80, height);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(height);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        java.util.List<java.util.function.IntSupplier> script = List.of(
                () -> 'g',
                () -> {
                    approvalPending.set(true);
                    return NonBlockingReader.READ_EXPIRED;
                },
                () -> 'G',
                () -> 'q');
        java.util.concurrent.atomic.AtomicInteger step =
                new java.util.concurrent.atomic.AtomicInteger();
        Mockito.when(reader.read(Mockito.anyLong())).thenAnswer(ignored -> {
            int index = step.getAndIncrement();
            return index < script.size() ? script.get(index).getAsInt() : (int) 'q';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
    }

    /**
     * A terminal whose polls never deliver a key: an approval is raised at the first poll, and a
     * {@code q} is held in reserve from the fourth so a failure shows up as a red assertion rather
     * than a hung build.
     */
    private static TerminalFixture terminalRaisingAnApproval(
            java.util.concurrent.atomic.AtomicBoolean approvalPending,
            java.util.concurrent.atomic.AtomicBoolean manualCloseUsed) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        java.util.concurrent.atomic.AtomicInteger polls =
                new java.util.concurrent.atomic.AtomicInteger();
        Mockito.when(reader.read(Mockito.anyLong())).thenAnswer(ignored -> {
            int poll = polls.incrementAndGet();
            approvalPending.set(true);
            if (poll < 4) {
                return NonBlockingReader.READ_EXPIRED;
            }
            manualCloseUsed.set(true);
            return (int) 'q';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
    }

    /** A reader that expires once — one poll with no keystroke — and then delivers {@code q}. */
    private static TerminalFixture terminalPollingOnce(
            java.util.concurrent.atomic.AtomicBoolean expired) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenAnswer(ignored -> {
            if (!expired.getAndSet(true)) {
                return NonBlockingReader.READ_EXPIRED;
            }
            return (int) 'q';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
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

        assertThrows(IllegalStateException.class,
                () -> new TranscriptOverlay(fixture.terminal).open(List.of(broken)));

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

        new TranscriptOverlay(fixture.terminal).open(List.of(code));

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

        new TranscriptOverlay(fixture.terminal).open(List.of(reasoning));

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

        new TranscriptOverlay(fixture.terminal).open(List.of(reasoning));

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

        new TranscriptOverlay(fixture.terminal).open(List.of(reasoning));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("TAIL-THINK"), visible);
    }

    /**
     * The [[提交块]] is an item of the log like any other, held in <b>source</b> form, so the overlay
     * re-renders it at the width it is browsing rather than replaying rows baked at submission time
     * (ADR 0010 amendment A8/A11).
     */
    @Test
    void theSubmittedPromptEchoIsLaidOutAtTheOverlaysOwnWidth() throws Exception {
        TerminalFixture fixture = terminalReturning('q');

        new TranscriptOverlay(fixture.terminal)
                .open(List.of(prompt(fixture.terminal, "command-only prompt")));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("command-only prompt"), visible);
    }

    @Test
    void lastKeyShowsTheTailOfOneTallExpandedBlock() throws Exception {
        TerminalFixture fixture = terminalReturningAtHeight(6, 'G', 'q');
        String codeText = java.util.stream.IntStream.rangeClosed(1, 29)
                .mapToObj(i -> "line-" + i)
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\nTAIL-SENTINEL";
        CodeBlock tall = new CodeBlock(fixture.terminal, "text", codeText);

        new TranscriptOverlay(fixture.terminal).open(List.of(tall));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        assertTrue(visible.contains("TAIL-SENTINEL"), visible);
    }

    /**
     * A window resized while the overlay is open is the overlay's own problem to solve (ADR 0010
     * amendment A12): it holds the terminal, so nobody else is allowed to fix its screen.
     *
     * <p>It is the <b>easy</b> case, which is why it is handled here rather than by the epoch
     * machinery: the modal owns the whole alternate screen and there is no scrollback underneath it to
     * preserve, so it clears and repaints unconditionally instead of working out what the terminal
     * reflowed. What this asserts is the visible consequence — content laid out at the old width is
     * re-laid-out at the new one, without closing or losing the reader's place.
     */
    @Test
    void resizingWhileTheOverlayIsOpenReLaysOutTheDocumentAtTheNewWidth() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Size> geometry =
                new java.util.concurrent.atomic.AtomicReference<>(new Size(80, 24));
        TerminalFixture fixture = terminalPolling(geometry);
        String longLine = "x".repeat(60);
        CodeBlock code = new CodeBlock(fixture.terminal, "text", longLine);

        new TranscriptOverlay(fixture.terminal, geometry::get).open(List.of(code));

        fixture.writer.flush();
        String visible = fixture.sink.toString(StandardCharsets.UTF_8);
        int wide = visible.indexOf(longLine);
        assertTrue(wide >= 0, "the 60-column line fits before the resize: " + visible);
        assertTrue(visible.substring(wide + longLine.length()).contains("…"),
                "after narrowing, the same source must be re-laid-out and clipped: " + visible);
    }

    /**
     * A terminal whose reader expires once — a poll with no keystroke — and then delivers {@code q}.
     * The geometry moves in between, which is the sequence a window drag produces while the overlay
     * has the terminal.
     */
    private static TerminalFixture terminalPolling(
            java.util.concurrent.atomic.AtomicReference<Size> geometry) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenAnswer(ignored -> geometry.get());
        Mockito.when(terminal.getBufferSize()).thenAnswer(ignored -> geometry.get());
        Mockito.when(terminal.getWidth()).thenAnswer(ignored -> geometry.get().getColumns());
        Mockito.when(terminal.getHeight()).thenAnswer(ignored -> geometry.get().getRows());
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenAnswer(ignored -> {
            if (geometry.get().getColumns() == 80) {
                geometry.set(new Size(40, 12));
                return NonBlockingReader.READ_EXPIRED;
            }
            return (int) 'q';
        });
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
    }

    /** The user's submission as the log holds it: source, re-rendered by whoever displays it. */
    private static StripEntry prompt(Terminal terminal, String text) {
        return SubmittedPromptItem.of(terminal, text);
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
        // The overlay polls with a timeout instead of blocking, so it can notice a resize itself.
        Mockito.when(reader.read(Mockito.anyLong()))
                .thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
        Mockito.when(reader.read())
                .thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
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
