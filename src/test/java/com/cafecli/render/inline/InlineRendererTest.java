package com.cafecli.render.inline;

import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.llm.LlmClient;
import com.cafecli.render.Renderer;
import com.cafecli.render.StatusInfo;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    /**
     * Retention is the <b>whole session</b> (ADR 0010 amendment A10), and the [[转写浏览层]] is where
     * the user reaches it.
     *
     * <p>This inverts the five-turn retention test that stood here: under a
     * [[destructive resize reset]] the terminal's own scrollback is disposable, so a bound on what is
     * kept in memory is a bound on what the session can still be shown at all. Retention and replay
     * stay different bounds — the [[bounded replay window]] is still row-budgeted, because it answers
     * to the terminal on every geometry change, while retention answers only to an overlay the user
     * opens deliberately.
     *
     * <p>Reached by {@code g} rather than at open, because the overlay now opens at the <b>tail</b>
     * (ADR 0010 amendment A13). The property is unchanged: the first turn of the session is still
     * there to be browsed after twelve.
     */
    @Test
    void theOverlayBrowsesEveryTurnOfTheSessionNotTheNewestFive() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        org.jline.utils.NonBlockingReader reader =
                Mockito.mock(org.jline.utils.NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenReturn((int) 'g', (int) 'q');
        Mockito.when(reader.read()).thenReturn((int) 'g', (int) 'q');
        Mockito.when(terminal.reader()).thenReturn(reader);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            for (int turn = 1; turn <= 12; turn++) {
                renderer.beginTurn();
                renderer.appendUserPrompt(String.format("turn-%03d", turn));
                Renderer.BlockWriter answer = renderer.beginAnswer();
                answer.append(String.format("answer-%03d%n", turn));
                answer.finish();
                renderer.endTurn();
                pump.pump();
            }
            sink.reset();

            renderer.openTranscript();
            writer.flush();

            String browsed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(browsed.contains("turn-001"), browsed);
            assertTrue(browsed.contains("answer-001"),
                    "the first turn of the session must still be browsable after twelve: " + browsed);
        }
    }

    /**
     * {@code /clear} is the one way to drop the retained session during a run (ADR 0010 amendment
     * A10): the log goes, and with it every rendered row the [[活区]] was holding for it.
     *
     * <p>The rows counted are the <b>live region's own</b> (ADR 0016 D1) — retained items hold none —
     * so what is streamed here is an answer, whose live view owns a projection while it is live.
     */
    @Test
    void clearViewDropsTheRetainedLogAndItsRenderedRows() {
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
            Renderer.BlockWriter answer = renderer.beginAnswer();
            answer.append("a streamed answer line the live region has laid out\n");
            pump.pump();
            assertTrue(renderer.retainedLogSnapshot().items() > 0);
            assertTrue(renderer.retainedLogSnapshot().liveRenderCacheRows() > 0);

            renderer.clearView();
            pump.pump();
            sink.reset();

            InlineRenderer.RetainedLogSnapshot snapshot = renderer.retainedLogSnapshot();
            assertEquals(0, snapshot.items());
            assertEquals(0, snapshot.liveRenderCacheRows());
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

            // Item 0 is the submitted prompt echo; item 1 is the code block it produced.
            List<org.jline.utils.AttributedString> rows =
                    renderer.retainedItems().get(1).renderRetained(80);

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

            List<org.jline.utils.AttributedString> rows =
                    renderer.retainedItems().get(1).renderRetained(80);
            String retained = rows.stream()
                    .map(org.jline.utils.AttributedString::toString)
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertTrue(retained.contains("first retained line"), retained);
            assertTrue(retained.contains("second retained line"), retained);
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
        Size size = new Size(120, 40);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        BottomStatusBar statusBar = new BottomStatusBar();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal, out, pump,
                new ManualFrameTicker(), statusBar)) {
            renderer.start();

            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "first")
                    .withEnvironment("MCP 1/1", "Skill 2/2"));
            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "second"));
            renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "latest"));
            pump.pump();

            // The dock is composed into the live frame by the render thread: only the latest status
            // is ever drawn, and the environment summary carried by the first update is preserved.
            String frame = org.jline.utils.AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
            assertTrue(frame.contains("latest"), frame);
            assertFalse(frame.contains("first"), frame);
            assertFalse(frame.contains("second"), frame);
            assertTrue(frame.contains("1 MCP server"), frame);
            assertTrue(frame.contains("2 skills"), frame);
            assertEquals("latest", statusBar.currentStatus().phase());
            assertEquals(1, renderer.renderStats().frames());
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
            // 可见的标记仍然是 `> `；issue 09 给它加上了[[提交块]]的紫色（ADR 0016 D6），所以提示符
            // 字符串现在带着那个样式，但**剥掉样式之后必须一字不变**——它要交给行编辑器当提示符。
            assertEquals("> ", AttributedString.fromAnsi(renderer.inputPrompt()).toString());
            // 这里钉的是**接线**：提示符由 `InputBoxChrome` 按这个终端的档位产出，而不是渲染器自己
            // 拼一份。**不要**改成断言某个具体的转义序列（例如 16 色的 `35m`）：`AnsiStyle.ENABLED`
            // 是 `static final`、每个 JVM 只算一次，而 `TerminalMarkdownRendererTest` 在自己的静态初始化
            // 里设了 `paicli.render.color=false`，于是"哪个测试类先被加载"决定了整个 JVM 的色彩档位。
            // 断言具体序列就会变成一条只在全量顺序下红的 flake（这正是本仓库已有的那条的成因）。
            // 三个色彩分支由 `InputBoxChromeTest` 直接对 `prompt(Palette)` 断言，不受全局状态影响。
            assertEquals(InputBoxChrome.prompt(InputBoxChrome.Palette.of(terminal)),
                    renderer.inputPrompt());
            assertTrue(renderer.inputRightPrompt().contains("@path"));
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
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
            renderer.appendToolResults(List.of(tr("read_file", "ok")));
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

    /** The result half of the pair: a [[工具块]] is not settled until it has one (ADR 0017 D2). */
    private static com.cafecli.tool.ToolRegistry.ToolExecutionResult tr(String name, String text) {
        return new com.cafecli.tool.ToolRegistry.ToolExecutionResult(name + "-id", name, "{}",
                com.cafecli.tool.ToolOutput.text(text), 3, false);
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
            assertFalse(visible.contains("collapse"), visible);
        }
    }

    @Test
    void codeUnderTheHundredLineCapStaysInlineWithoutAToggleHint() {
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
            assertTrue(visible.contains("line 11"), visible);
            assertFalse(visible.contains("ctrl+o to expand"), visible);
        }
    }

    @Test
    void foldablesCommitToScrollbackOnceOnAddAndAreRepaintedByAGeometryChange() {
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
        ManualClock clock = new ManualClock();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                clock)) {
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            // A foldable is committed straight to frozen scrollback on the frame it is added — it
            // never renders in the live tail (ADR 0012), so it appears exactly once.
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.appendToolResults(List.of(tr("read_file", "ok")));
            pump.pump();
            assertTrue(org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8)).contains("README.md"));

            // A newer foldable does not re-emit the already-committed older one (no live-then-freeze
            // double render, which is what opened the #5B gap on a real terminal).
            String code = java.util.stream.IntStream.rangeClosed(1, 11)
                    .mapToObj(i -> "line " + i)
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            sink.reset();
            renderer.appendCodeBlock("bash", code);
            pump.pump();
            String committed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(committed.contains("README.md"), committed);
            assertTrue(committed.contains("code: bash"), committed);
            assertTrue(committed.contains("line 11"), committed);

            // Frozen scrollback is immutable **within** a [[geometry epoch]] only (ADR 0010
            // amendment A17). A geometry change erases the visible screen and the terminal's saved
            // scrollback, so committed blocks are repainted from the [[bounded replay window]] —
            // once each. Not repainting them would not preserve them; it would lose them.
            sink.reset();
            size.setColumns(79);
            winch.getValue().handle(Terminal.Signal.WINCH);
            settleGesture(clock, pump);

            String afterResize = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, countOccurrences(afterResize, "README.md"), afterResize);
            assertEquals(1, countOccurrences(afterResize, "code: bash"), afterResize);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ManualFramePump pump = new ManualFramePump();
        // Streamed transcript text is committed by the render thread like every other frozen row,
        // so a frame has to run before it is on the wire.
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");
            pump.pump();

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
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            renderer.beginTurn();
            renderer.stream().println("┌─ code: text");
            renderer.stream().println("    literal content");
            renderer.stream().println("└─ end");
            pump.pump();

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
        ManualFramePump pump = new ManualFramePump();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
        try {
            byte[] utf8 = "中文边界\n".getBytes(StandardCharsets.UTF_8);
            for (byte value : utf8) {
                renderer.stream().write(new byte[]{value});
            }
            renderer.stream().flush();
            pump.pump();

            // The frame also carries the live region, so this pins the decoding property rather than
            // exact sink equality: the CJK survives being split across single-byte writes intact.
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("中文边界"), emitted);
            assertFalse(emitted.contains("�"), emitted);
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
            assertTrue(emitted.contains("› "), emitted);
            assertTrue(emitted.contains("inspect Renderer.java"), emitted);
        } finally {
            renderer.close();
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
            assertTrue(rendered.contains("思考 2 行"), rendered);
            assertFalse(rendered.contains("ctrl+o to expand"), rendered);
            assertFalse(rendered.contains("first line"), rendered);
            assertFalse(rendered.contains("second line"), rendered);
        }
    }

    @Test
    void endingTurnCommitsCollapsedReasoningWithoutAnyFoldFailureNotice() {
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
            reasoning.append("inspect state\nverify result\n");
            reasoning.finish();
            // A completed foldable is committed straight to scrollback on the frame it is added, so
            // the single collapsed header appears exactly once — no live render that is later re-frozen.
            renderer.endTurn();
            pump.pump();

            String committed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(committed, "思考 2 行"), committed);
            assertFalse(committed.contains("Block frozen"), committed);
            assertFalse(committed.contains("Ctrl+T"), committed);
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
            assertTrue(visible.indexOf("思考 1 行") < visible.indexOf("final answer"), visible);
            assertEquals(2, renderer.retainedLogSnapshot().items());
        }
    }

    @Test
    void newThinkingBurstCommitsThePreviousReasoningToScrollbackInsteadOfRepainting() {
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
            reasoning.append("step one analysis\n");
            reasoning.finish();

            // Next ReAct step starts thinking again: the previous collapsed reasoning is committed to
            // scrollback exactly once (on its own add frame), never left in the live region stacked
            // above the active spinner (which is what smears when the wheel is scrolled mid-turn).
            renderer.beginThinking("Thinking");
            renderer.appendThinking("step two analysis");
            pump.pump();

            String started = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(started, "思考 1 行"), started);
            assertTrue(started.contains("Thinking"), started);

            // A further spinner refresh must not repaint the now-frozen reasoning header.
            sink.reset();
            renderer.appendThinking(" continues");
            pump.pump();
            String refreshed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(refreshed.contains("思考 1 行"), refreshed);
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
    void pushWhileTheLineEditorIsReadingGoesThroughTheSingleDisplayPath() {
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
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            var answer = renderer.beginAnswer();
            answer.append("first background line\n");
            answer.append("second background line\n");
            answer.finish();

            assertEquals("", sink.toString(StandardCharsets.UTF_8), "nothing is written before a frame");
            pump.pump();

            // 冻结路径只剩一条：读取态不再有 printAbove 分支，帧照常由渲染线程写出。
            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String frame = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(frame.contains("first background line"), frame);
            assertTrue(frame.contains("second background line"), frame);
            assertEquals(1, renderer.renderStats().writes());
        }
    }

    @Test
    void toolPushWhileReadingIsFrozenInOneFrameWrite() {
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
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.appendToolResults(List.of(tr("read_file", "ok")));
            renderer.endTurn();
            pump.pump();

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String frame = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(frame.contains("ReadFile(README.md)"), frame);
            assertEquals(1, renderer.renderStats().writes());
        }
    }

    @Test
    void nextPromptDoesNotReplayAnAlreadyFrozenTool() {
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
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.appendToolResults(List.of(tr("read_file", "ok")));
            renderer.endTurn();
            pump.pump();
            assertTrue(org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8)).contains("ReadFile(README.md)"));
            sink.reset();

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
    void shortCodePushWhileReadingPrintsExpandedBlockOnce() {
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
        Mockito.when(reader.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(reader);
            renderer.appendCodeBlock("bash", "echo one\necho two\n");
            renderer.endTurn();
            pump.pump();

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String frame = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(frame.contains("code: bash"), frame);
            assertTrue(frame.contains("echo one"), frame);
            assertTrue(frame.contains("echo two"), frame);
            assertEquals(1, occurrences(frame, "echo one"), frame);
        }
    }

    @Test
    void nextTurnSubmissionPrintsOnlyTheNewPromptAfterPreviousFoldableCommitted() {
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
            renderer.appendToolResults(List.of(tr("read_file", "ok")));
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
            assertFalse(frozen.contains("ReadFile(README.md)"), frozen);
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
        Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                .thenReturn(Terminal.SignalHandler.SIG_DFL);
        var winch = ArgumentCaptor.forClass(Terminal.SignalHandler.class);
        ManualFramePump pump = new ManualFramePump();
        ManualClock clock = new ManualClock();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                clock)) {
            // Geometry observation is established by start(), which is where the wake-up hint is
            // wired and where the sampler's driver comes up (ADR 0010 amendment A3c). This test used
            // to mutate the size and let frame composition notice on its next heartbeat; that
            // mechanism was frame composition probing the terminal, which is exactly what this
            // amendment takes off the write path. The assertion below is unchanged — reflow-from-
            // source at the new width is still required — only the delivery is, as the sibling
            // geometry test above already does it.
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            var answer = renderer.beginAnswer();
            // No trailing newline: the text stays as the live tail (completed lines are committed to
            // immutable scrollback and are not reflowed), so this exercises tail reflow-from-source.
            answer.append("hello");
            pump.pump();
            sink.reset();

            size.setColumns(3);
            answer.append("!");
            winch.getValue().handle(Terminal.Signal.WINCH);
            settleGesture(clock, pump);

            String reflow = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(reflow.contains("hel"), "reflow=" + reflow + ", stats=" + renderer.renderStats());
            assertTrue(reflow.contains("lo!"), reflow);
        }
    }

    /**
     * A geometry change repaints only once the geometry is observed to have settled (ADR 0010
     * amendment A3b): the frame that observes the change writes the erase, and the redraw follows a
     * quiet interval later. Both steps are driven off the injected clock, never by waiting.
     */
    private static void settleGesture(ManualClock clock, ManualFramePump pump) {
        pump.pump();
        clock.advance(TurbulenceGate.DEFAULT_QUIET_MILLIS + 1);
        pump.pump();
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
        ManualClock clock = new ManualClock();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                clock)) {
            renderer.start();
            Mockito.verify(terminal).handle(Mockito.eq(Terminal.Signal.WINCH), winch.capture());
            var answer = renderer.beginAnswer();
            // Live tail (no trailing newline) so the winch-triggered reflow has something to re-wrap;
            // completed lines are already immutable scrollback and are not reflowed.
            answer.append("hello");
            pump.pump();
            sink.reset();

            size.setColumns(3);
            winch.getValue().handle(Terminal.Signal.WINCH);
            Mockito.verify(previousWinch).handle(Terminal.Signal.WINCH);
            settleGesture(clock, pump);

            String reflow = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(reflow.contains("hel"), reflow);
            assertTrue(reflow.contains("lo"), reflow);
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
            assertEquals("second turn\n\n", visible);
            assertFalse(raw.contains("\u001b[A"), raw);
            assertFalse(raw.contains("\u001b[1A"), raw);
        }
    }

    @Test
    void completedAnswerLineCommitsToScrollbackOnceAndOnlyTheTailKeepsUpdating() {
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

            // The completed line is committed to scrollback exactly once and never repainted again
            // across the streaming frames; only the growing tail keeps producing output.
            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(visible, "stable completed line"), visible);
            assertTrue(visible.contains("grows"), visible);
        }
    }

    @Test
    void tallStreamingAnswerCommitsBodyToScrollbackSoOnlyTheTailStaysInTheLiveRegion() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(60, 30);
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
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= 20; i++) {
                body.append("row-").append(String.format("%02d", i)).append('\n');
            }
            answer.append(body.toString());
            answer.append("partial tail");
            pump.pump();

            // Every finished line is committed to scrollback exactly once even though the answer is
            // far shorter than the terminal height (this is the incremental commit, not overflow).
            String committed = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(committed, "row-05"), committed);
            assertEquals(1, occurrences(committed, "row-20"), committed);

            // Growing the tail must not repaint any already-committed body line.
            sink.reset();
            answer.append(" more");
            pump.pump();
            String afterTailGrows = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(afterTailGrows.contains("row-05"), afterTailGrows);
            assertFalse(afterTailGrows.contains("row-20"), afterTailGrows);
            assertTrue(afterTailGrows.contains("more"), afterTailGrows);
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
            assertTrue(visible.contains("System.out.println(1);"), visible);
            assertFalse(visible.contains("    System.out.println(1);"), visible);
            assertFalse(visible.contains("┌─"), visible);
            assertFalse(visible.contains("└─"), visible);
        }
    }

    @Test
    void anIncompleteAnswerTailIsNeverFrozen() {
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
            var answer = renderer.beginAnswer();
            answer.append("live complete\nincomplete tail");
            pump.pump();
            sink.reset();

            answer.append(" grows");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            // 完成行已是不可重绘的回卷；未完成的尾行仍活着，只重绘增量。
            assertFalse(live.contains("live complete"), live);
            assertTrue(live.contains("grows"), live);
        }
    }

    @Test
    void onlyTheUnfrozenAnswerTailIsRepainted() {
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
            var answer = renderer.beginAnswer();
            answer.append("frozen line\nlive tail");
            pump.pump();

            answer.append(" grows");
            pump.pump();
            sink.reset();

            answer.append(" again");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            // The committed line stays in scrollback (never repainted); only the tail delta is drawn.
            assertFalse(live.contains("frozen line"), live);
            assertTrue(live.contains("again"), live);
        }
    }

    @Test
    void aFrozenHeadingIsNotRedrawnWhenTheTailGrows() {
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
            var answer = renderer.beginAnswer();
            answer.append("# Frozen heading\nlive tail");
            pump.pump();
            sink.reset();

            answer.append(" grows");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("Frozen heading"), live);
            assertFalse(live.contains("="), live);
            assertTrue(live.contains("grows"), live);
        }
    }

    @Test
    void aDeferredTableIsCommittedExactlyOnce() {
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
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            var answer = renderer.beginAnswer();
            // 表格是 markdown 渲染器的延迟块：整块闭合前不产出 styled 行，闭合后一次性冻结。
            answer.append("| Key | Value |\n| --- | --- |\n| one | two |\n");
            pump.pump();

            answer.append("\nlive tail");
            pump.pump();

            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertEquals(1, occurrences(live, "Key"), live);
            assertEquals(1, occurrences(live, "one"), live);
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
            renderer.start();
            renderer.bindLineReader(reader);
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            var answer = renderer.beginAnswer();
            answer.append("11111111111111111111"
                    + "22222222222222222222"
                    + "33333333333333333333"
                    + "44444444444444444444"
                    + "55555555555555555555"
                    + "66666666666666666666");
            pump.pump();

            // 6 terminal rows − 2 dock rows = a 4-row tail budget: the oldest wrapped rows fall away.
            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String live = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertFalse(live.contains("11111111111111111111"), live);
            assertFalse(live.contains("22222222222222222222"), live);
            assertTrue(live.contains("66666666666666666666"), live);
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
            // The fully-printed answer carries the one trailing separator blank every committed block
            // unit emits (so answer→next-block never collapses to zero blanks), hence the extra "\n".
            assertEquals("background push\n\n".getBytes(StandardCharsets.UTF_8).length, stats.bytes());
            assertEquals(1, stats.flushes());
        }
    }

    @Test
    void aFrameUsesOneSynchronizedOutputEnvelopeWhenSupported() {
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
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
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

            Mockito.verify(reader, Mockito.never()).printAbove(Mockito.anyString());
            String payload = sink.toString(StandardCharsets.UTF_8);
            assertTrue(payload.startsWith(AnsiSeq.BEGIN_SYNCHRONIZED_UPDATE), payload);
            assertTrue(payload.endsWith(AnsiSeq.END_SYNCHRONIZED_UPDATE), payload);
            assertEquals(payload.getBytes(StandardCharsets.UTF_8).length,
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

    /**
     * The [[转写浏览层]] holds the terminal for its duration (ADR 0010 amendment A12), so producer
     * events that arrive while the user is reading update the source model and write nothing.
     *
     * <p>Asserted from <b>inside</b> the modal: the key read is where the overlay is parked, so an
     * event posted and a frame pumped from there is exactly the case that used to paint live-region
     * rows onto the alternate screen over it.
     */
    @Test
    void noLiveRegionBytesReachTheTerminalWhileTheTranscriptOverlayIsOpen() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        ManualFramePump pump = new ManualFramePump();
        long[] writesDuringModal = {-1};

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                new BottomStatusBar())) {
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            try {
                Mockito.when(keyReader.read(Mockito.anyLong())).thenAnswer(ignored -> {
                    // Parked in the overlay: a turn's worth of output arrives and a frame runs.
                    long before = renderer.renderStats().writes();
                    Renderer.BlockWriter answer = renderer.beginAnswer();
                    answer.append("produced while the user was reading history\n");
                    answer.finish();
                    renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "react"));
                    pump.drain();
                    writesDuringModal[0] = renderer.renderStats().writes() - before;
                    return (int) 'q';
                });
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();
            renderer.beginTurn();
            renderer.appendUserPrompt("show me the history");
            renderer.endTurn();
            pump.drain();

            renderer.openTranscript();

            assertEquals(0, writesDuringModal[0],
                    "a modal owns the terminal: the render loop must write nothing while it is up");

            // And the output produced meanwhile is not lost — it reaches the terminal on return.
            sink.reset();
            pump.drain();
            assertTrue(org.jline.utils.AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8))
                            .contains("produced while the user was reading history"),
                    "output produced during a modal must arrive once the terminal is handed back: ["
                            + org.jline.utils.AttributedString.stripAnsi(
                                    sink.toString(StandardCharsets.UTF_8)) + "]");
        }
    }

    /**
     * And the output that arrived meanwhile is shown <b>by the overlay</b>, not only after it closes
     * (ADR 0010 amendment A13): opening mid-turn is worth doing because it shows what is happening now.
     *
     * <p>Asserted before any frame is pumped after the overlay returns, so the only writer that can
     * have put the answer on the wire is the overlay itself.
     */
    @Test
    void answerCommittedWhileTheOverlayIsOpenIsShownByTheOverlay() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                new BottomStatusBar())) {
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            boolean[] produced = {false};
            try {
                Mockito.when(keyReader.read(Mockito.anyLong())).thenAnswer(ignored -> {
                    if (produced[0]) {
                        return (int) 'q';
                    }
                    produced[0] = true;
                    Renderer.BlockWriter answer = renderer.beginAnswer();
                    answer.append("STREAMED-WHILE-READING\n");
                    answer.finish();
                    pump.drain();
                    // One poll with no keystroke: the overlay looks at the log again.
                    return NonBlockingReader.READ_EXPIRED;
                });
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();
            renderer.beginTurn();
            renderer.appendUserPrompt("show me the history");
            pump.drain();
            sink.reset();

            renderer.openTranscript();
            writer.flush();

            assertTrue(org.jline.utils.AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8))
                            .contains("STREAMED-WHILE-READING"),
                    "the overlay must show output that arrived while it was open: ["
                            + org.jline.utils.AttributedString.stripAnsi(
                                    sink.toString(StandardCharsets.UTF_8)) + "]");
        }
    }

    /**
     * 回合跑到一半开着[[转写浏览层]]时按下的 Ctrl+C（issue 12，ADR 0016 D3）：**会话活下来**，
     * 回合被请求取消，**层不关**，而且「回合停下来了」这件事**在层里看得见**。
     *
     * <p>三件事被同一次开层证实：
     * <ul>
     *   <li>取消到位——令牌被取消（今天这里是进程直接消失，因为 {@code enterRawMode()} 不清
     *       {@code ISIG} 而仓库从未装过 INT handler）；</li>
     *   <li>层没被关掉——{@code q} 是留在手边的手动关层键，只有它被用掉才说明层一直开着，
     *       取消自己没关它（关屏需要它自己的理由：一条待决[[审批应答]]有，一次取消没有，A14）；</li>
     *   <li>反馈经有序提交日志进转写，所以浏览层的实时跟随（A13）把它画了出来——不必关层才知道。</li>
     * </ul>
     *
     * <p>signal 线程只说话、不写终端：中断那一刻终端的字节数一个都没变，那句反馈是渲染线程在后来
     * 的帧里落进[[冻结回卷]]的。
     */
    @Test
    void ctrlCWhileTheOverlayIsOpenStopsTheTurnAndLeavesTheReaderInTheLayer() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.enterRawMode()).thenReturn(new org.jline.terminal.Attributes());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            com.cafecli.runtime.CancellationRegistry cancellations =
                    new com.cafecli.runtime.CancellationRegistry();
            com.cafecli.runtime.CancellationToken turn = cancellations.beginForegroundTurn();
            com.cafecli.cli.SessionInterruptGuard guard =
                    new com.cafecli.cli.SessionInterruptGuard(cancellations, renderer, null, null);

            int[] polls = {0};
            long[] wroteFromSignalThread = {-1};
            boolean[] manualCloseUsed = {false};
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            try {
                Mockito.when(keyReader.read(Mockito.anyLong())).thenAnswer(ignored -> {
                    int poll = ++polls[0];
                    if (poll == 1) {
                        // 用户在层里按下 Ctrl+C：SIGINT 落到会话级 handler 上，就是这一次调用。
                        long before = sink.size();
                        guard.interrupt();
                        wroteFromSignalThread[0] = sink.size() - before;
                        pump.drain();
                        return NonBlockingReader.READ_EXPIRED;
                    }
                    if (poll < 4) {
                        pump.drain();
                        return NonBlockingReader.READ_EXPIRED;  // 层继续轮询、重读提交日志
                    }
                    manualCloseUsed[0] = true;
                    return (int) 'q';
                });
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();
            renderer.beginTurn();
            renderer.appendUserPrompt("what is it doing right now");
            pump.drain();
            sink.reset();

            renderer.openTranscript();
            writer.flush();

            String visible = org.jline.utils.AttributedString.stripAnsi(
                    sink.toString(StandardCharsets.UTF_8));
            assertTrue(turn.isCancelled(), "层内 Ctrl+C 必须停回合");
            assertEquals(0L, wroteFromSignalThread[0],
                    "signal 线程只经 stream() 说话，不直写终端");
            assertTrue(manualCloseUsed[0],
                    "取消不关层：层只应被用户自己按的 q 关掉：[" + visible + "]");
            assertTrue(visible.contains("已请求取消"),
                    "「回合停下来了」必须在层内看得见：[" + visible + "]");
        }
    }

    /**
     * 斜杠 palette 也是一个[[模态租借]]里的读取间隙（issue 12 验收项 3）：它在输入线程上、在
     * {@code readLine} 之外阻塞读键，所以今天和浏览层一样裸着。装了兜底之后，一次 Ctrl+C 停回合，
     * 而 palette **照常等它的答案**。
     */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void ctrlCInsideTheSlashPaletteStopsTheTurnAndLeavesThePaletteWaiting() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(new org.jline.terminal.Attributes());
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            com.cafecli.runtime.CancellationRegistry cancellations =
                    new com.cafecli.runtime.CancellationRegistry();
            com.cafecli.cli.SessionInterruptGuard guard =
                    new com.cafecli.cli.SessionInterruptGuard(cancellations, renderer, null, null);
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();

            com.cafecli.runtime.CancellationToken duringPalette = cancellations.beginForegroundTurn();
            int[] keys = {0};
            Mockito.when(keyReader.read()).thenAnswer(ignored -> {
                if (++keys[0] == 1) {
                    guard.interrupt();
                    return (int) 'x';   // palette 不认识的键：它继续读
                }
                return (int) '\r';
            });

            int chosen = renderer.openPalette("配置 / config", List.of("model", "hitl"));

            assertTrue(duringPalette.isCancelled(), "palette 里的 Ctrl+C 也要停回合");
            assertEquals(0, chosen, "palette 没被取消掉，它等到了用户自己的选择");
        }
    }

    /**
     * An [[审批应答]] raised while the [[转写浏览层]] holds the input thread closes the overlay and
     * puts the user back where the card and the [[输入框]] that answers it are (ADR 0010 amendment
     * A14).
     *
     * <p>Driven through a real {@link ApprovalPump}, because the seam is its enqueue-time
     * notification: the worker parks on a get with <b>no timeout</b>, so nothing else would ever tell
     * the overlay that a tool is waiting, and the session would stop with no visible cause. The
     * fixture keeps a {@code q} in reserve as the manual close — reaching for it is the failure this
     * asserts against.
     */
    @Test
    void anApprovalRaisedWhileTheOverlayIsOpenReturnsTheUserToTheCardAndTheInputBox()
            throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        Mockito.when(terminal.enterRawMode()).thenReturn(new org.jline.terminal.Attributes());
        ManualFramePump pump = new ManualFramePump();
        com.cafecli.hitl.ApprovalPump approvals = new com.cafecli.hitl.ApprovalPump();
        ApprovalRequest request = ApprovalRequest.of(
                "write_file", "{\"path\":\"a.txt\"}", "test", "主 Agent");
        AtomicBoolean reserveKeyUsed = new AtomicBoolean();
        java.util.concurrent.CountDownLatch notified = new java.util.concurrent.CountDownLatch(1);

        // Not try-with-resources: a parked worker outlives a failed assertion, and closing an executor
        // waits for it — a red test would hang the build instead of reporting.
        var toolThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        var inputThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                new BottomStatusBar())) {
            approvals.setPendingNotifier(pending -> {
                renderer.onApprovalPending();
                notified.countDown();
            });
            renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new org.jline.utils.AttributedString("> answer me")), 2, 80, false));
            java.util.concurrent.atomic.AtomicReference<
                    java.util.concurrent.Future<ApprovalResult>> parkedTool =
                    new java.util.concurrent.atomic.AtomicReference<>();
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            int[] polls = {0};
            Mockito.when(keyReader.read(Mockito.anyLong())).thenAnswer(ignored -> {
                if (polls[0]++ == 0) {
                    // A dangerous tool asks for approval while the user is reading history.
                    parkedTool.set(toolThread.submit(() -> approvals.requestApproval(request)));
                    assertTrue(notified.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return NonBlockingReader.READ_EXPIRED;
                }
                reserveKeyUsed.set(true);
                return (int) 'q';
            });
            Mockito.when(keyReader.read()).thenReturn((int) 'y');
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();
            renderer.beginTurn();
            renderer.appendUserPrompt("show me the history");
            pump.drain();

            renderer.openTranscript();

            assertFalse(reserveKeyUsed.get(),
                    "the approval closes the overlay by itself — the user pressed nothing");
            writer.flush();
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("[?1049l"),
                    "and the user is back on the primary screen");
            assertFalse(org.jline.utils.AttributedString.stripAnsi(
                            sink.toString(StandardCharsets.UTF_8)).contains("需要审批"),
                    "nothing about the approval was drawn inside the overlay — it is answered "
                            + "through the input box, which the overlay does not have");
            sink.reset();

            // From here the input pump does what it always does with a pending approval — which it
            // can only do because the overlay stopped holding the thread it does it on.
            com.cafecli.hitl.ApprovalPump.Pending pending =
                    approvals.poll(java.time.Duration.ofSeconds(2));
            assertNotNull(pending, "the approval is still waiting to be answered");
            renderer.showApproval(pending.request(), "");
            String primaryScreen = "";
            for (int frame = 0; frame < 100 && !primaryScreen.contains("需要审批"); frame++) {
                pump.drain();
                writer.flush();
                primaryScreen = org.jline.utils.AttributedString.stripAnsi(
                        sink.toString(StandardCharsets.UTF_8));
                Thread.sleep(10);
            }
            assertTrue(primaryScreen.contains("需要审批"),
                    "the approval card is drawn on the primary screen: " + primaryScreen);
            assertTrue(primaryScreen.contains("> answer me"),
                    "with the input box that answers it: " + primaryScreen);

            // The answer arrives through the [[输入框]] on the primary screen (issue 07), not from
            // the renderer: the card is drawn here, the key that answers it is read by the resident
            // line editor and routed by the REPL.
            approvals.resolve(pending, ApprovalResult.approve());
            assertEquals(ApprovalResult.Decision.APPROVED,
                    parkedTool.get().get(5, java.util.concurrent.TimeUnit.SECONDS).decision(),
                    "and the parked tool is unblocked without the user ever closing the overlay");
        } finally {
            approvals.rejectAllPending("test teardown");
            toolThread.shutdownNow();
            inputThread.shutdownNow();
        }
    }

    /**
     * The slash palette gets the same lease treatment. It prints its list at the cursor, reads a key
     * and erases exactly what it printed, so a live-region frame written into the middle of that
     * scrolls the list out from under its own erase.
     */
    @Test
    void noLiveRegionBytesReachTheTerminalWhileTheSlashPaletteIsOpen() {
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
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        ManualFramePump pump = new ManualFramePump();
        long[] writesDuringModal = {-1};

        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                new BottomStatusBar())) {
            NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
            try {
                Mockito.when(keyReader.read()).thenAnswer(ignored -> {
                    long before = renderer.renderStats().writes();
                    renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "react"));
                    pump.drain();
                    writesDuringModal[0] = renderer.renderStats().writes() - before;
                    return (int) '\r';
                });
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
            Mockito.when(terminal.reader()).thenReturn(keyReader);
            renderer.start();
            pump.drain();

            assertEquals(0, renderer.openPalette("配置 / config", List.of("a", "b")));

            assertEquals(0, writesDuringModal[0],
                    "the palette owns the terminal while it is up, exactly as the overlay does");
        }
    }

    @Test
    void openTranscriptSuspendsThenRestoresStatusDock() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(80);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        NonBlockingReader keyReader = Mockito.mock(NonBlockingReader.class);
        try {
            // The overlay polls with a timeout rather than blocking, so that it notices a resize of
            // its own accord (ADR 0010 amendment A12).
            Mockito.when(keyReader.read(Mockito.anyLong())).thenReturn((int) 'q');
            Mockito.when(keyReader.read()).thenReturn((int) 'q');
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        Mockito.when(terminal.reader()).thenReturn(keyReader);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        BottomStatusBar statusBar = new BottomStatusBar();
        ManualFramePump pump = new ManualFramePump();

        try (InlineRenderer renderer = new InlineRenderer(terminal, out, pump,
                new ManualFrameTicker(), statusBar)) {
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            pump.pump();
            sink.reset();

            renderer.openTranscript();
            writer.flush();

            // The overlay owns the whole screen on its own: there is no dock to suspend and no
            // bottom scroll region to work around, so entering it sets no DECSTBM region.
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertFalse(SCROLL_REGION.matcher(emitted).find(),
                    "the overlay must not install a scroll region: " + emitted);
        }
    }

    @Test
    void noScrollRegionIsEverInstalledAcrossADockedRendererLifecycle() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (Terminal terminal = new org.jline.terminal.impl.ExternalTerminal("dock-lifecycle",
                "xterm-256color", new java.io.ByteArrayInputStream(new byte[0]), sink,
                StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(80, 24));
            ManualFramePump pump = new ManualFramePump();
            PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
            try (InlineRenderer renderer = new InlineRenderer(terminal, out, pump,
                    new ManualFrameTicker(), new BottomStatusBar())) {
                renderer.start();
                renderer.updateStatus(StatusInfo.active("glm-5.1", 200_000L, false, "react"));
                renderer.beginTurn();
                renderer.appendCodeBlock("java", "int x = 1;\n");
                pump.pump();
                renderer.endTurn();
                pump.pump();
                terminal.flush();
            }

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertFalse(SCROLL_REGION.matcher(emitted).find(),
                    "DECSTBM is retired: the live region protects itself with clear→print→recreate: "
                            + emitted.replace("", "ESC"));
            assertNull(Status.getStatus(terminal, false), "no JLine Status may ever be created");
        }
    }

    /** DECSTBM set (ESC [ top ; bottom r). ESC [ r is a reset and is not a region install. */
    private static final java.util.regex.Pattern SCROLL_REGION =
            java.util.regex.Pattern.compile("\\[\\d+;\\d+r");

    @Test
    void submittedPromptFreezesAsAWashedBlockWithChevronOnlyOnItsFirstRow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors)).thenReturn(256);
        Size size = new Size(24, 20);
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
            renderer.appendUserPrompt("please review the render loop");
            pump.pump();

            List<String> frozen = frozenPromptRows(sink);
            assertTrue(frozen.size() >= 2, frozen.toString());
            assertTrue(frozen.get(0).startsWith("› please"), frozen.toString());
            assertTrue(frozen.get(1).startsWith("  "), frozen.toString());
            assertFalse(frozen.get(1).contains("›"), frozen.toString());
            assertEquals(1, occurrences(String.join("\n", frozen), "›"), frozen.toString());
        }
    }

    /** ANSI-stripped, non-blank rows committed to frozen scrollback. */
    private static List<String> frozenPromptRows(ByteArrayOutputStream sink) {
        String visible = org.jline.utils.AttributedString.stripAnsi(
                sink.toString(StandardCharsets.UTF_8));
        return java.util.Arrays.stream(visible.split("\n", -1))
                .filter(row -> !row.isBlank())
                .toList();
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

    /** Count truly-empty lines strictly between the first {@code from} match and the next {@code to}. */
    private static int blankLinesBetween(String text, String from, String to) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.length; i++) {
            if (start < 0) {
                if (lines[i].contains(from)) {
                    start = i;
                }
            } else if (lines[i].contains(to)) {
                end = i;
                break;
            }
        }
        if (start < 0 || end < 0) {
            return -1;
        }
        int blanks = 0;
        for (int i = start + 1; i < end; i++) {
            if (lines[i].isEmpty()) {
                blanks++;
            }
        }
        return blanks;
    }
}
