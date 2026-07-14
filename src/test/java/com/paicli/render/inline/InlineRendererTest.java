package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.Status;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    @Test
    void retainedWindowKeepsTheLatestFiveUserTurns() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            for (int turn = 1; turn <= 6; turn++) {
                renderer.beginTurn();
                renderer.appendUserPrompt("turn-" + turn);
                Renderer.BlockWriter answer = renderer.beginAnswer();
                answer.append("answer-" + turn + "\n");
                answer.finish();
                renderer.endTurn();
                pump.pump();
            }

            InlineRenderer.RetainedWindowSnapshot snapshot = renderer.retainedWindowSnapshot();
            assertEquals(5, snapshot.turns().size());
            assertEquals(List.of("turn-2", "turn-3", "turn-4", "turn-5", "turn-6"),
                    snapshot.turns().stream()
                            .map(InlineRenderer.RetainedTurnSnapshot::userPrompt)
                            .toList());
        }
    }

    @Test
    void evictingOldestTurnReleasesItsBlockRenderCache() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            for (int turn = 1; turn <= 5; turn++) {
                renderer.beginTurn();
                renderer.appendUserPrompt("turn-" + turn);
                renderer.appendCodeBlock("java", "int value = " + turn + ";\n");
                renderer.endTurn();
                pump.pump();
            }
            int cacheEntriesAtCapacity = renderer.retainedWindowSnapshot().renderCacheEntries();
            assertTrue(cacheEntriesAtCapacity > 0);

            renderer.beginTurn();
            renderer.appendUserPrompt("turn-6");
            renderer.appendCodeBlock("java", "int value = 6;\n");
            renderer.endTurn();
            pump.pump();

            InlineRenderer.RetainedWindowSnapshot snapshot = renderer.retainedWindowSnapshot();
            assertEquals(cacheEntriesAtCapacity, snapshot.renderCacheEntries());
            assertEquals(1, snapshot.releasedRenderCaches());
        }
    }

    @Test
    void clearViewEmptiesRetainedTurnsAndRemovesLiveFoldState() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendCodeBlock("java", String.join("\n", java.util.Collections.nCopies(11,
                    "int retained = 1;")));
            pump.pump();
            renderer.toggleLastBlock();
            pump.pump();
            assertTrue(renderer.retainedWindowSnapshot().renderCacheEntries() > 0);

            renderer.clearView();
            pump.pump();
            sink.reset();
            renderer.toggleLastBlock();
            pump.pump();

            InlineRenderer.RetainedWindowSnapshot snapshot = renderer.retainedWindowSnapshot();
            assertTrue(snapshot.turns().isEmpty());
            assertEquals(0, snapshot.renderCacheEntries());
            assertEquals("", org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void blockFrozenOutOfLiveStripRemainsRenderableFromRetainedSource() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 8);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendUserPrompt("show retained code");
            renderer.appendCodeBlock("java", "int retainedSource = 42;\n");
            renderer.endTurn();
            pump.pump();

            RetainedWindow.TurnView turn = renderer.retainedTurns().get(0);
            List<org.jline.utils.AttributedString> rows =
                    turn.blocks().get(0).renderRetained(80);

            assertTrue(rows.stream()
                    .map(org.jline.utils.AttributedString::toString)
                    .anyMatch(line -> line.contains("retainedSource")));
        }
    }

    @Test
    void retainedAnswerIncludesPrefixAlreadyFrozenFromLiveStrip() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 8);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendUserPrompt("retain answer");
            Renderer.BlockWriter answer = renderer.beginAnswer();
            answer.append("first retained line\nsecond retained line\n");
            answer.finish();
            renderer.endTurn();
            pump.pump();

            List<org.jline.utils.AttributedString> rows = renderer.retainedTurns().get(0)
                    .blocks().get(0).renderRetained(80);
            String retained = rows.stream()
                    .map(org.jline.utils.AttributedString::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertTrue(retained.contains("first retained line"), retained);
            assertTrue(retained.contains("second retained line"), retained);
        }
    }

    @Test
    void evictedBlockCacheStaysReleasedWhenBlockWasStillInTallLiveStrip() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 50);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendCodeBlock("java", "int first = 1;\n");
            renderer.endTurn();
            pump.pump();
            StripEntry first = renderer.retainedTurns().get(0).blocks().get(0);
            assertTrue(first.renderCacheEntries() > 0);

            for (int turn = 2; turn <= 6; turn++) {
                renderer.beginTurn();
                renderer.appendCodeBlock("java", "int value = " + turn + ";\n");
                renderer.endTurn();
                pump.pump();
            }

            assertEquals(0, first.renderCacheEntries());
        }
    }

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            // updateStatus should still not throw
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void statusStormAppliesOnlyTheLatestUpdateOncePerFrame() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        Status dock = Mockito.mock(Status.class);
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        BottomStatusBar statusBar = new BottomStatusBar(terminal, out, dock);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal, out, pump,
                new ManualFrameTicker(), statusBar)) {
            renderer.start();
            Mockito.clearInvocations(dock);

            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "first")
                    .withEnvironment("MCP 1/1", "Skill 2/2"));
            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "second"));
            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "latest"));
            pump.pump();

            Mockito.verify(dock, Mockito.times(1)).update(Mockito.anyList());
            assertEquals("latest", statusBar.currentStatus().phase());
            assertEquals("MCP 1/1", statusBar.currentStatus().mcpSummary());
            assertEquals("Skill 2/2", statusBar.currentStatus().skillSummary());
            RenderStats stats = renderer.renderStats();
            assertEquals(1, stats.frames());
            assertEquals(0, stats.writes());
            assertEquals(0, stats.bytes());
            assertEquals(0, stats.flushes());
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamUsesPrintAboveWhenLineReaderIsReading() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("异步通知");

            Mockito.verify(lineReader).printAbove("异步通知\n");
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("异步通知"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlineRendererKeepsPromptInTranscriptFlow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.start();
            sink.reset();
            renderer.beforeInput();
            renderer.afterInput();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertEquals("* ", renderer.inputPrompt());
            assertTrue(renderer.inputRightPrompt().contains("@path"));
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void acceptedInputRowsUseJLineWidthForCombiningCharacters() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(20, 12));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8))) {
            renderer.clearAcceptedInput("e\u0301".repeat(18));

            assertTrue(sink.toString(StandardCharsets.UTF_8).startsWith(AnsiSeq.moveUp(1)),
                    sink.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void thinkingPanelRendersJLineActivityReasoningAndClears() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        ManualFrameTicker ticker = new ManualFrameTicker();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, ticker);
        try {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("先分析用户输入\n再检查状态栏边界");
            pump.pump();

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsThinkingPanel());
            assertTrue(rendered.contains("Thinking"), rendered);
            assertTrue(rendered.contains("先分析用户输入"), rendered);
            assertTrue(rendered.contains("再检查状态栏边界"), rendered);
            assertTrue(rendered.contains("|") || rendered.contains("│"),
                    "activity display should show live reasoning quote content: " + rendered);

            sink.reset();
            renderer.endThinking();
            pump.pump();
            String cleared = sink.toString(StandardCharsets.UTF_8);
            assertFalse(cleared.contains(AnsiSeq.CLEAR_TO_EOS),
                    "activity clearing must not clear to screen end and erase transcript scrollback: " + cleared);
        } finally {
            renderer.close();
        }
    }

    @Test
    void collapsedToolBlockTogglesInPlaceOnTheNextRenderFrame() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(false);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();
            String collapsed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(collapsed.contains("ReadFile(README.md)"), collapsed);
            sink.reset();

            assertTrue(renderer.toggleLastBlock());
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String expanded = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(expanded.contains("README.md"), expanded);
            assertEquals(1, occurrences(expanded, "README.md"), expanded);
            assertFalse(expanded.contains(AnsiSeq.CLEAR_TO_EOS), expanded);
            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
        }
    }

    @Test
    void liveStripPreservesAnswerToolAnswerInsertionOrder() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var first = renderer.beginAnswer();
            first.append("answer A\n");
            first.finish();
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            var second = renderer.beginAnswer();
            second.append("answer B\n");
            second.finish();
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            int answerA = visible.indexOf("answer A");
            int tool = visible.indexOf("ReadFile(README.md)");
            int answerB = visible.indexOf("answer B");
            assertTrue(answerA >= 0 && answerA < tool && tool < answerB, visible);
        }
    }

    @Test
    void togglingAfterFoldableLeavesStripEmitsOneCtrlTHistoryHint() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 6);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            var answer = renderer.beginAnswer();
            answer.append("line one\nline two\nline three\nline four\n");
            pump.pump();
            sink.reset();

            assertTrue(renderer.toggleLastBlock());
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(visible, "Ctrl+T"), visible);
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream("n\ntest rejection\n"
                    .getBytes(StandardCharsets.UTF_8)));
            try (InlineRenderer renderer = new InlineRenderer(terminal)) {
                ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
                ApprovalResult result = renderer.promptApproval(req);
                assertNotNull(result);
                assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
                assertEquals("test rejection", result.reason());
            }
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void shortCodeBlockDefaultsExpandedInsideTheLiveStrip() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendCodeBlock("java", "int one = 1;\nint two = 2;\n");

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("code: java"), visible);
            assertTrue(visible.contains("int one = 1;"), visible);
            assertTrue(visible.contains("int two = 2;"), visible);
            assertTrue(visible.contains("collapse (ctrl+o)"), visible);
        }
    }

    @Test
    void longCodeBlockDefaultsCollapsedWithLineCountAndToggleHint() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        String code = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "line " + i)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.appendCodeBlock("text", code);
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("code: text"), visible);
            assertTrue(visible.contains("11 行"), visible);
            assertTrue(visible.contains("ctrl+o to expand"), visible);
            assertFalse(visible.contains("line 11"), visible);
        }
    }

    @Test
    void toolThenCodeRemainIndependentWhileBothAreLive() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenReturn(Terminal.SignalHandler.SIG_DFL);
        var winch = ArgumentCaptor.forClass(Terminal.SignalHandler.class);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            pump.pump();
            sink.reset();
            renderer.toggleLastBlock();
            pump.pump();
            assertTrue(org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8)).contains("README.md"));

            String code = java.util.stream.IntStream.rangeClosed(1, 11)
                    .mapToObj(i -> "line " + i)
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            renderer.appendCodeBlock("bash", code);
            pump.pump();
            sink.reset();
            renderer.toggleLastBlock();
            pump.pump();
            assertTrue(org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8)).contains("line 11"));

            sink.reset();
            size.setColumns(79);
            winch.getValue().handle(Terminal.Signal.WINCH);
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("README.md"), visible);
            assertTrue(visible.contains("code: bash"), visible);
            assertTrue(visible.contains("line 11"), visible);
        }
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("普通段落 1"), emitted);
            assertTrue(emitted.contains("普通段落 2"), emitted);
            // 不应出现折叠箭头
            assertFalse(emitted.contains("⏵"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void unstructuredStreamDoesNotInterpretDecoratedCodeText() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("┌─ code: text");
            renderer.stream().println("    literal content");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("┌─ code: text"), emitted);
            assertTrue(emitted.contains("literal content"), emitted);
            assertTrue(emitted.contains("└─ end"), emitted);
            assertFalse(emitted.contains("ctrl+o"), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamPreservesCjkAcrossArbitraryUtf8ByteBoundaries() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            byte[] utf8 = "中文边界\n".getBytes(StandardCharsets.UTF_8);
            for (byte value : utf8) {
                renderer.stream().write(new byte[]{value});
            }
            renderer.stream().flush();

            assertEquals("中文边界\n", sink.toString(StandardCharsets.UTF_8));
        } finally {
            renderer.close();
        }
    }

    @Test
    void typedUserPromptKeepsSubmittedPromptVisual() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.appendUserPrompt("inspect Renderer.java");
            pump.pump();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("> "), emitted);
            assertTrue(emitted.contains("inspect Renderer.java"), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void expandedCompletedReasoningKeepsThinkingQuoteVisual() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.UTF_8);
        Size size = new Size(120, 40);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> { writer.flush(); return null; }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker())) {
            renderer.beginTurn();
            var reasoning = renderer.beginReasoning();
            reasoning.append("first   line\r\nsecond line\n");
            reasoning.finish();
            pump.pump();
            sink.reset();

            renderer.toggleLastBlock();
            pump.pump();

            String rendered = sink.toString(StandardCharsets.UTF_8)
                    .replaceAll("\\u001B\\[[;\\d]*m", "");
            assertTrue(rendered.contains("Thinking..."), rendered);
            assertTrue(rendered.contains("│ first line") || rendered.contains("| first line"), rendered);
            assertTrue(rendered.contains("│ second line") || rendered.contains("| second line"), rendered);
        }
    }

    @Test
    void completedReasoningBecomesOneDefaultCollapsedSummaryWithLogicalLineCount() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(120, 40);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> { writer.flush(); return null; }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker())) {
            renderer.beginTurn();
            var reasoning = renderer.beginReasoning();
            reasoning.append("first line\r\nsecond line\n");
            reasoning.finish();
            pump.pump();

            String rendered = sink.toString(StandardCharsets.UTF_8)
                    .replaceAll("\\u001B\\[[;\\d]*m", "");
            assertTrue(rendered.contains("🧠 思考 2 行 (ctrl+o to expand)"), rendered);
            assertFalse(rendered.contains("first line"), rendered);
            assertFalse(rendered.contains("second line"), rendered);
        }
    }

    @Test
    void ctrlOExpandsCompletedReasoningInPlaceWithoutPrintAboveReplay() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(100, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> { writer.flush(); return null; }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(false);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.beginTurn();
            Renderer.BlockWriter reasoning = renderer.beginReasoning();
            reasoning.append("inspect state\nverify result\n");
            reasoning.finish();
            pump.pump();
            sink.reset();
            Mockito.clearInvocations(reader);
            reading.set(true);

            renderer.toggleLastBlock();
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("inspect state"), visible);
            assertEquals(1, occurrences(visible, "inspect state"), visible);
            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
        }
    }

    @Test
    void contentStartAndTurnEndKeepReasoningBeforeAnswerAsDistinctBlocks() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(100, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> { writer.flush(); return null; }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump,
                new ManualFrameTicker())) {
            renderer.beginTurn();
            Renderer.BlockWriter reasoning = renderer.beginReasoning();
            reasoning.append("reason before content\n");
            reasoning.finish();
            Renderer.BlockWriter answer = renderer.beginAnswer();
            answer.append("final answer\n");
            answer.finish();
            renderer.endTurn();
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.indexOf("🧠 思考 1 行") < visible.indexOf("final answer"), visible);
            assertEquals(2, renderer.retainedWindowSnapshot().turns().get(0).blockCount());
        }
    }

    @Test
    void turnEndDoesNotEmitStatsByDefault() {
        String previous = System.getProperty("paicli.render.stats");
        System.clearProperty("paicli.render.stats");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.endTurn();
            pump.pump();

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        } finally {
            renderer.close();
            if (previous == null) {
                System.clearProperty("paicli.render.stats");
            } else {
                System.setProperty("paicli.render.stats", previous);
            }
        }
    }

    @Test
    void turnEndEmitsOneStatsLineWhenEnabled() {
        String previous = System.getProperty("paicli.render.stats");
        System.setProperty("paicli.render.stats", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("reasoning");
            pump.pump();
            renderer.endThinking();
            pump.pump();
            sink.reset();

            renderer.endTurn();
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.startsWith("Render stats:"), output);
            assertTrue(output.contains("frames=2"), output);
            assertTrue(output.contains("reasoning_chars=9"), output);
            assertEquals(1, output.lines().filter(line -> line.startsWith("Render stats:")).count());
        } finally {
            renderer.close();
            if (previous == null) {
                System.clearProperty("paicli.render.stats");
            } else {
                System.setProperty("paicli.render.stats", previous);
            }
        }
    }

    @Test
    void streamedAnswerFreezesIntoTerminalHistoryExactlyOnce() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var answer = renderer.beginAnswer();
            answer.append("streamed exactly once\n");

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();
            answer.finish();
            renderer.endTurn();
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(visible, "streamed exactly once"), visible);
        }
    }

    @Test
    void readingStatePushUsesOneBatchedPrintAbovePerFrame() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("first background line\n");
            answer.append("second background line\n");
            answer.finish();

            Mockito.verifyNoInteractions(reader);
            pump.pump();

            var batch = ArgumentCaptor.forClass(String.class);
            Mockito.verify(reader, Mockito.times(1)).printAbove(batch.capture());
            assertTrue(batch.getValue().contains("first background line"), batch.getValue());
            assertTrue(batch.getValue().contains("second background line"), batch.getValue());
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void readingStateToolPushFreezesInOneBatchedPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.endTurn();
            pump.pump();

            var batch = ArgumentCaptor.forClass(String.class);
            Mockito.verify(reader, Mockito.times(1)).printAbove(batch.capture());
            assertTrue(batch.getValue().contains("ReadFile(README.md)"), batch.getValue());
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void nextPromptDoesNotReplayToolAlreadyFrozenByPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(true);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.endTurn();
            pump.pump();
            Mockito.verify(reader, Mockito.times(1)).printAbove(Mockito.contains("ReadFile(README.md)"));

            reading.set(false);
            renderer.beginTurn();
            renderer.appendUserPrompt("next request");
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(visible.contains("README.md"), visible);
            assertEquals(1, occurrences(visible, "next request"), visible);
        }
    }

    @Test
    void readingStateShortCodePushPrintsExpandedBlockOnce() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendCodeBlock("bash", "echo one\necho two\n");
            renderer.endTurn();
            pump.pump();

            var batch = ArgumentCaptor.forClass(String.class);
            Mockito.verify(reader, Mockito.times(1)).printAbove(batch.capture());
            assertTrue(batch.getValue().contains("code: bash"), batch.getValue());
            assertTrue(batch.getValue().contains("echo one"), batch.getValue());
            assertTrue(batch.getValue().contains("echo two"), batch.getValue());
            assertEquals(1, occurrences(batch.getValue(), "echo one"), batch.getValue());
        }
    }

    @Test
    void readLineWidgetToggleUsesDisplayDiffWithoutPrintAboveReplay() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(false);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            pump.pump();
            renderer.endTurn();
            pump.pump();
            sink.reset();
            Mockito.clearInvocations(reader);

            reading.set(true);
            assertTrue(renderer.toggleLastBlock());
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("README.md"), visible);
            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
        }
    }

    @Test
    void nextTurnSubmissionFreezesLiveFoldableAndPromptInOneRenderFrame() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            pump.pump();
            renderer.endTurn();
            pump.pump();
            sink.reset();

            renderer.beginTurn();
            renderer.appendUserPrompt("next request");
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
            pump.pump();

            String frozen = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(frozen, "ReadFile(README.md)"), frozen);
            assertEquals(1, occurrences(frozen, "next request"), frozen);
            sink.reset();

            var answer = renderer.beginAnswer();
            answer.append("new answer\n");
            pump.pump();
            String next = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(next.contains("new answer"), next);
            assertFalse(next.contains("README.md"), next);
            assertFalse(next.contains("next request"), next);
        }
    }

    @Test
    void terminalResizeReflowsTheLiveAnswerFromItsSource() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        Size size = new Size(24, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var answer = renderer.beginAnswer();
            answer.append("abc\nxyz\n");
            pump.pump();
            sink.reset();

            size.setColumns(5);
            answer.append("k");
            pump.pump();

            String reflow = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(reflow.contains("abc"), "reflow=" + reflow + ", stats=" + renderer.renderStats());
            assertTrue(reflow.contains("xyz"), reflow);
        }
    }

    @Test
    void idleWinchEventReflowsTheLiveAnswerWithoutAnotherSourceDelta() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        Size size = new Size(24, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Terminal.SignalHandler previousWinch = Mockito.mock(Terminal.SignalHandler.class);
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenReturn(previousWinch);
        var winch = ArgumentCaptor.forClass(Terminal.SignalHandler.class);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            var answer = renderer.beginAnswer();
            answer.append("abc\nxyz\n");
            pump.pump();
            sink.reset();

            size.setColumns(5);
            winch.getValue().handle(Terminal.Signal.WINCH);
            Mockito.verify(previousWinch).handle(Terminal.Signal.WINCH);
            pump.pump();

            String reflow = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(reflow.contains("abc"), reflow);
            assertTrue(reflow.contains("xyz"), reflow);
        }
    }

    @Test
    void defaultWinchSentinelIsRestoredButNeverInvoked() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenReturn(Terminal.SignalHandler.SIG_DFL);
        var winch = ArgumentCaptor.forClass(Terminal.SignalHandler.class);
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            var answer = renderer.beginAnswer();
            answer.append("live answer");
            pump.pump();
            long framesBeforeWinch = renderer.renderStats().frames();

            assertDoesNotThrow(() -> winch.getValue().handle(Terminal.Signal.WINCH));
            pump.pump();

            assertEquals(framesBeforeWinch + 1, renderer.renderStats().frames());
        } finally {
            renderer.close();
        }
        Mockito.verify(terminal).handle(Terminal.Signal.WINCH, Terminal.SignalHandler.SIG_DFL);
    }

    @Test
    void nextTurnStartsItsLiveStripFromAReanchoredDisplay() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenAnswer(invocation -> {
            InfoCmp.Capability capability = invocation.getArgument(0);
            return switch (capability) {
                case cursor_up -> "\u001b[A";
                case cursor_down -> "\u001b[B";
                case cursor_left -> "\b";
                case cursor_right -> "\u001b[C";
                case carriage_return -> "\r";
                case clr_eol -> "\u001b[K";
                case parm_up_cursor -> "\u001b[%p1%dA";
                case parm_down_cursor -> "\u001b[%p1%dB";
                case parm_left_cursor -> "\u001b[%p1%dD";
                case parm_right_cursor -> "\u001b[%p1%dC";
                default -> null;
            };
        });
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var first = renderer.beginAnswer();
            first.append("first row\nsecond row\n");
            first.finish();
            renderer.endTurn();
            pump.pump();
            sink.reset();

            renderer.beginTurn();
            var second = renderer.beginAnswer();
            second.append("second turn\n");
            second.finish();
            renderer.endTurn();
            pump.pump();

            String raw = sink.toString(StandardCharsets.UTF_8);
            String visible = org.jline.utils.AttributedString.stripAnsi(raw);
            assertEquals("second turn\n", visible);
            assertFalse(raw.contains("\u001b[A"), raw);
            assertFalse(raw.contains("\u001b[1A"), raw);
        }
    }

    @Test
    void answerStormCachesCompletedLinesAndRerendersOnlyTheTail() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var answer = renderer.beginAnswer();
            answer.append("stable completed line\nactive");
            pump.pump();

            answer.append(" tail");
            pump.pump();
            answer.append(" grows");
            pump.pump();

            assertEquals(2, renderer.renderStats().cacheHits());
        }
    }

    @Test
    void streamedAnswerConsumesStatefulStyledMarkdownLines() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.UTF_8);
        Size size = new Size(80, 20);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var answer = renderer.beginAnswer();
            answer.append("| Key | Value |\n| --- | --- |\n| one | two |\n\n```ja");
            answer.append("va\nSystem.out.println(1);\n```\n");
            answer.finish();
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("| Key"), visible);
            assertTrue(visible.contains("| one"), visible);
            assertTrue(visible.contains("code: java"), visible);
            assertTrue(visible.contains("    System.out.println(1);"), visible);
            assertTrue(visible.contains("end"), visible);
        }
    }

    @Test
    void enteringInputModeDoesNotPermanentlyFreezeAnIncompleteAnswerTail() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(false);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("live complete\nincomplete tail");
            pump.pump();
            Mockito.clearInvocations(reader);

            reading.set(true);
            answer.append(" grows");
            pump.pump();

            var batch = ArgumentCaptor.forClass(String.class);
            Mockito.verify(reader, Mockito.times(1)).printAbove(batch.capture());
            assertEquals("live complete\n", batch.getValue());
            assertFalse(batch.getValue().contains("incomplete tail"), batch.getValue());
        }
    }

    @Test
    void leavingInputModeRepaintsOnlyTheUnfrozenAnswerTail() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(false);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("frozen line\nlive tail");
            pump.pump();

            reading.set(true);
            answer.append(" grows");
            pump.pump();
            sink.reset();

            reading.set(false);
            answer.append(" again");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("frozen line"), live);
            assertTrue(live.contains("live tail grows again"), live);
        }
    }

    @Test
    void readingToLiveMarkdownTransitionDoesNotLeaveHeadingDecorationBehind() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(true);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("# Frozen heading\nlive tail");
            pump.pump();
            sink.reset();

            reading.set(false);
            answer.append(" grows");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("Frozen heading"), live);
            assertFalse(live.contains("="), live);
            assertTrue(live.contains("live tail grows"), live);
        }
    }

    @Test
    void deferredTableFlushDoesNotRedrawSourceAlreadyPrintedAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(60, 16);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        AtomicBoolean reading = new AtomicBoolean(true);
        Mockito.when(reader.isReading()).thenAnswer(invocation -> reading.get());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("| Key | Value |\n| --- | --- |\n| one | two |\n");
            pump.pump();
            sink.reset();

            reading.set(false);
            answer.append("\nlive tail");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("Key"), live);
            assertFalse(live.contains("one"), live);
            assertTrue(live.contains("live tail"), live);
        }
    }

    @Test
    void finishedAnswerWithoutTrailingNewlineStillFreezesOverflow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(20, 6);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(false);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("11111111111111111111"
                    + "22222222222222222222"
                    + "33333333333333333333"
                    + "44444444444444444444");
            answer.finish();
            pump.pump();

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(live, "11111111111111111111"), live);
        }
    }

    @Test
    void unfinishedLongTailStaysBoundedWithoutBeingFrozenIntoHistory() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(20, 6);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(false);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("11111111111111111111"
                    + "22222222222222222222"
                    + "33333333333333333333"
                    + "44444444444444444444");
            pump.pump();

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("11111111111111111111"), live);
            assertTrue(live.contains("44444444444444444444"), live);
        }
    }

    @Test
    void coalescedNextTurnAnswerSurvivesThePreviousTurnFinishCutoff() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
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
            var first = renderer.beginAnswer();
            first.append("first turn\n");
            first.finish();
            renderer.endTurn();

            renderer.beginTurn();
            var second = renderer.beginAnswer();
            second.append("second turn");
            pump.pump();

            second.append(" continues");
            pump.pump();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(visible.contains("second turn continues"), visible);
            assertEquals(1, occurrences(visible, "second turn"), visible);
        }
    }

    @Test
    void readingPushIsIncludedInRenderWriteByteAndFlushAccounting() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.beginTurn();
            var answer = renderer.beginAnswer();
            answer.append("background push\n");
            answer.finish();
            pump.pump();

            RenderStats stats = renderer.renderStats();
            assertEquals(1, stats.frames());
            assertEquals(1, stats.writes());
            assertEquals("background push\n".getBytes(StandardCharsets.UTF_8).length, stats.bytes());
            assertEquals(1, stats.flushes());
        }
    }

    @Test
    void readingPushUsesOneSynchronizedOutputEnvelopeWhenSupported() {
        String previous = System.getProperty("paicli.render.synchronized-output");
        System.setProperty("paicli.render.synchronized-output", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("synchronized push\n");
            answer.finish();
            pump.pump();

            var payload = ArgumentCaptor.forClass(String.class);
            Mockito.verify(reader, Mockito.times(1)).printAbove(payload.capture());
            assertTrue(payload.getValue().startsWith(AnsiSeq.BEGIN_SYNCHRONIZED_UPDATE), payload.getValue());
            assertTrue(payload.getValue().endsWith(AnsiSeq.END_SYNCHRONIZED_UPDATE), payload.getValue());
            assertEquals(payload.getValue().getBytes(StandardCharsets.UTF_8).length,
                    renderer.renderStats().bytes());
        } finally {
            if (previous == null) {
                System.clearProperty("paicli.render.synchronized-output");
            } else {
                System.setProperty("paicli.render.synchronized-output", previous);
            }
        }
    }

    @Test
    void stripOverflowFreezesTopRowsInOneBatchPerFrame() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(40, 6);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader reader = Mockito.mock(LineReader.class);
        Mockito.when(reader.isReading()).thenReturn(false);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("line one\nline two\nline three\nline four\nline five\n");
            answer.finish();
            pump.pump();

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String liveBytes = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(liveBytes, "line one"), liveBytes);
            assertEquals(1, occurrences(liveBytes, "line two"), liveBytes);
            assertTrue(liveBytes.contains("line three"), liveBytes);
            assertEquals(1, renderer.renderStats().writes());
            assertEquals(1, renderer.renderStats().flushes());
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
