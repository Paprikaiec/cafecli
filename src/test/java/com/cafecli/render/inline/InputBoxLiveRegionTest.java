package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import com.cafecli.render.inline.replay.TerminalScreenModel;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入行进活区的端到端断言（issue 05，ADR 0014 决策 1/2/5）。
 *
 * <p>用真实 {@link CafeCliLineReader} + 真实 {@link InlineRenderer} + 手动帧泵：输入线程键入、渲染
 * 线程合成，断言落在合成帧的字节上。这正是 PRD 说的「最不可测的部分变成最可测的部分」。
 */
class InputBoxLiveRegionTest {

    private static final class Rig implements AutoCloseable {
        final TracingTerminal terminal;
        final PipedOutputStream keys;
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final ManualFramePump pump = new ManualFramePump();
        final ManualClock clock = new ManualClock();
        final InlineRenderer renderer;
        final CafeCliLineReader reader;
        final int columns;
        final int rows;
        Thread readThread;

        Rig() throws Exception {
            this(60, 12);
        }

        Rig(int columns, int rows) throws Exception {
            this(columns, rows, null);
        }

        Rig(int columns, int rows, Completer completer) throws Exception {
            this.columns = columns;
            this.rows = rows;
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("inputbox", "xterm-256color", in, sink);
            terminal.setSize(new Size(columns, rows));
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                    clock);
            Map<String, Object> vars = new HashMap<>();
            vars.put(LineReader.INPUT_RC_FILE_NAME, "/nonexistent-cafecli-inputrc");
            reader = CafeCliLineReader.create(terminal, "inputbox", vars, null, completer, null);
            renderer.bindLineReader(reader);
            renderer.start();
        }

        void startReading(String prompt) throws Exception {
            readThread = new Thread(() -> {
                try {
                    reader.readLine(prompt);
                } catch (Throwable ignored) {
                    // pipe closed at teardown
                }
            }, "inputbox-readline");
            readThread.setDaemon(true);
            readThread.start();
            Thread.sleep(300);
        }

        void send(String s) throws Exception {
            keys.write(s.getBytes(StandardCharsets.UTF_8));
            keys.flush();
            Thread.sleep(150);
        }

        String frame() {
            return AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
        }

        /**
         * The screen a user would be looking at, not the bytes we sent it. The residue below is
         * invisible in {@link #frame()} — every row involved <em>was</em> written, just to the wrong
         * column — so only a screen model can tell the two apart.
         */
        List<String> document() {
            TerminalScreenModel screen = new TerminalScreenModel(columns, rows);
            screen.feed(sink.toString(StandardCharsets.UTF_8));
            return screen.document().stream().filter(row -> !row.isBlank()).toList();
        }

        @Override
        public void close() throws Exception {
            renderer.close();
            try {
                keys.close();
            } catch (Exception ignored) {
                // pipe may already be broken
            }
            terminal.close();
        }
    }

    @Test
    void typedTextAppearsInTheComposedFrameAboveTheStatusDock() throws Exception {
        try (Rig rig = new Rig()) {
            rig.renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            rig.startReading("> ");
            rig.send("hello");
            rig.sink.reset();
            rig.pump.pump();

            String frame = rig.frame();
            assertTrue(frame.contains("> hello"), frame);
            assertTrue(frame.indexOf("> hello") < frame.indexOf("glm-5.1"),
                    "the input line must sit above the status dock:\n" + frame);
        }
    }

    @Test
    void pushedOutputIsFrozenAboveTheInputLineWhichIsNeverDuplicated() throws Exception {
        try (Rig rig = new Rig()) {
            rig.startReading("> ");
            rig.send("draft");
            rig.pump.pump();
            rig.sink.reset();

            var answer = rig.renderer.beginAnswer();
            answer.append("pushed background line\n");
            answer.finish();
            rig.pump.pump();

            String frame = rig.frame();
            assertTrue(frame.contains("pushed background line"), frame);
            assertTrue(frame.indexOf("pushed background line") < frame.lastIndexOf("> draft"),
                    "pushed output must be frozen above the input line:\n" + frame);
            assertEquals(1, occurrences(frame, "pushed background line"), frame);
        }
    }

    @Test
    void acceptedInputLeavesNoResidueInTheLiveRegion() throws Exception {
        try (Rig rig = new Rig()) {
            rig.startReading("> ");
            rig.send("submitted text");
            rig.pump.pump();
            rig.sink.reset();

            rig.send("\r");
            rig.readThread.join(2000);
            rig.pump.pump();

            assertTrue(rig.reader.capture().lastFrame().isEmpty(),
                    "the input line must leave the live region once readLine returns");
            assertFalse(rig.frame().contains("submitted text"),
                    "the accepted line must not be repainted:\n" + rig.frame());
        }
    }

    /**
     * 退格穿过多行之后，屏上不留旧行（真机手工验收 2026-08-28 报的第三个缺陷）。
     *
     * <p><b>机理是双宽字符 + 右边缘光标。</b>行编辑器按终端<b>整宽</b>折行，所以一行填满时光标落在
     * {@code col == columns} 这个右边缘位置。移到右边缘没有可移植的转义，JLine
     * （{@code Display.moveVisualCursorTo}）改为重打第 {@code columns - 1} 列那个字符；那一列若是双宽
     * 字符的后半，取回来的是<b>整个字</b>，打在倒数第二列就多占一列、触发终端折行，后半落到下一行第 0 列。
     * 从此 JLine 的光标模型与屏幕差两列，后续每一帧都写错位置——下横线从第 2 列开始画，旧输入行再也
     * 擦不掉。修复是 {@code LiveRegionStrip.composedCursorPos} 把光标夹在最后一列之外。
     *
     * <p><b>判据必须读屏</b>：那些行一行不少地写出去了，只是写错了列，所以字节断言全都成立。ASCII
     * 同样填满整行却不复现（重打一个单宽字符是无害的），因此本例用 CJK，而这个不对称本身就是判据的
     * 一部分——任何预测 ASCII 也该失败的解释都是错的。
     */
    @Test
    void backspacingAcrossWrappedCjkRowsLeavesNothingBehind() throws Exception {
        try (Rig rig = new Rig(40, 24)) {
            rig.renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            rig.startReading("> ");
            String typed = "一句话总结这个管线在主屏模式原生回卷这条最难走的路线上做到了罕见的工程深度真的很好";
            for (int i = 0; i < typed.length(); i++) {
                rig.send(typed.substring(i, i + 1));
                rig.pump.pump();
            }
            assertEquals(3, rig.reader.capture().lastFrame().lines().size(),
                    "the buffer must actually wrap for this to be the reported case");

            for (int i = 0; i < typed.length(); i++) {
                rig.send("");
                rig.pump.pump();
            }

            assertEquals(1, rig.reader.capture().lastFrame().lines().size(),
                    "the line editor's own model is back to a single row");
            List<String> screen = rig.document();
            assertFalse(String.join("\n", screen).contains("一句话"),
                    "backspaced text must not survive on screen:\n" + String.join("\n", screen));
            // The box is back to its idle shape: rule, bare prompt, rule, then the two dock rows.
            assertEquals(5, screen.size(), "stale rows left on screen:\n" + String.join("\n", screen));
        }
    }

    /**
     * 一个捕获行**永远只占一个物理行**，即便行编辑器交过来的那一行自己带着 {@code '\n'}。
     *
     * <p>{@code LineReaderImpl.redisplay} 用
     * {@code full.columnSplitLength(columns, true, delayLineWrap, terminal)} 切它要显示的行，第二个
     * 参数是 <b>includeNewlines</b>：<b>按换行切出来的那一行会把 {@code '\n'} 留在行尾</b>。补全菜单
     * （{@code post}）是一整串带内嵌换行的文本，所以菜单一展开，捕获帧里**大多数行都以 {@code '\n'} 结尾}。
     *
     * <p>这条行为一路穿透[[活区]]的两个假设：{@code InputBoxChrome.washed} 逐字符拷贝、于是把
     * {@code '\n'} 一起拷进去，而 {@code columnLength()} 把它算作 <b>−1</b> 列（{@code WCWidth.wcwidth('\n')}），
     * 所以底纹补齐既多补一列、又补在了换行**之后**；随后 {@code LiveRegionStrip.hardTerminated()} 再补一个
     * {@code '\n'}，{@code Display.update} 只剥掉末尾那一个，内嵌的那个原样打给终端。于是一个「行」占了
     * <b>两个</b>物理行，而活区所有的行数——{@code liveTailBudget} 的预算、{@code lastRegionRows} 的回退
     * 距离——都按一行算，擦除因此永远擦不干净。
     *
     * <p>判据必须<b>读屏</b>：那些行确实被写出去了，只是多占了行，所以字节断言全都成立
     * （同 {@code backspacingAcrossWrappedCjkRowsLeavesNothingBehind} 的不对称）。菜单收起之后屏上
     * 不该剩任何候选行，且整个文档不该高过一屏——菜单把转写顶进回卷，正是行数被少算的可见证据。
     */
    @Test
    void aClosedCompletionMenuLeavesNoCandidateRowsOnScreen() throws Exception {
        Completer many = (reader, line, candidates) -> {
            for (int i = 1; i <= 24; i++) {
                candidates.add(new Candidate("@cand" + i + "/some/long/path/File" + i + ".java"));
            }
        };
        try (Rig rig = new Rig(80, 24, many)) {
            rig.reader.setOpt(LineReader.Option.AUTO_LIST);
            rig.reader.unsetOpt(LineReader.Option.AUTO_MENU);
            rig.renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            rig.startReading("> ");
            for (int i = 1; i <= 6; i++) {
                rig.renderer.stream().println("transcript row " + i);
                rig.pump.pump();
            }

            rig.send("@cand");
            rig.pump.drain();
            rig.send("\t");                       // the menu opens
            rig.pump.drain();
            assertTrue(rig.reader.capture().lastFrame().lines().size() > 1,
                    "the menu must actually open for this to be the reported case");

            rig.send("1\t");                      // narrows to one candidate
            rig.pump.drain();
            rig.send("x");                        // and the menu closes
            rig.pump.drain();

            List<String> screen = rig.document();
            assertEquals(0, screen.stream().filter(row -> row.contains(".java")).count(),
                    "a closed completion menu must leave no candidate row behind:\n"
                            + String.join("\n", screen));
            assertEquals(1, screen.stream().filter(row -> row.contains("Auto Model")).count(),
                    "and exactly one status dock:\n" + String.join("\n", screen));
        }
    }

    /**
     * The same property one level down, stated on the frame itself: <b>no captured row may contain a
     * line break</b>. Asserted separately from the screen because it is the invariant the fix
     * establishes, and because a screen that happens to come out clean would not prove it holds.
     */
    @Test
    void noCapturedInputRowEverContainsALineBreak() throws Exception {
        Completer many = (reader, line, candidates) -> {
            for (int i = 1; i <= 24; i++) {
                candidates.add(new Candidate("@cand" + i + "/some/long/path/File" + i + ".java"));
            }
        };
        try (Rig rig = new Rig(80, 24, many)) {
            rig.reader.setOpt(LineReader.Option.AUTO_LIST);
            rig.reader.unsetOpt(LineReader.Option.AUTO_MENU);
            rig.startReading("> ");
            rig.send("@cand");
            rig.pump.drain();
            rig.send("\t");
            rig.pump.drain();

            List<AttributedString> lines = rig.reader.capture().lastFrame().lines();
            assertTrue(lines.size() > 1, "the menu must actually open");
            for (AttributedString row : lines) {
                assertFalse(row.toString().contains("\n"),
                        "a captured row must be one row, not two: [" + row + "]");
                assertEquals(row.toString().length(), row.columnLength(),
                        "a row carrying a control character mis-measures its own width: [" + row + "]");
            }
        }
    }

    @Test
    void printAboveFromTheLineEditorBecomesFrozenScrollbackNotADirectWrite() throws Exception {
        try (Rig rig = new Rig()) {
            rig.startReading("> ");
            rig.send("typing");
            rig.pump.pump();
            rig.sink.reset();
            rig.terminal.tracer().reset();

            rig.reader.printAbove("STARTUP BANNER LINE");
            // 投进冻结队列，尚未成帧
            assertTrue(rig.terminal.tracer().records().stream()
                            .noneMatch(r -> r.text.contains("STARTUP")),
                    "printAbove must not write the terminal from the input thread");

            rig.pump.pump();
            String frame = rig.frame();
            assertTrue(frame.contains("STARTUP BANNER LINE"), frame);
            assertTrue(frame.indexOf("STARTUP BANNER LINE") < frame.lastIndexOf("> typing"),
                    "printAbove content belongs above the input line:\n" + frame);
        }
    }

    @Test
    void aResizeRelaysOutTheInputLineAndTheLiveRegionTogether() throws Exception {
        try (Rig rig = new Rig()) {
            rig.renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            rig.startReading("> ");
            rig.send("resize me");
            rig.pump.pump();

            rig.terminal.setSize(new Size(40, 10));
            rig.terminal.raise(Terminal.Signal.WINCH);
            Thread.sleep(300);
            rig.sink.reset();
            rig.send("!");
            // 缩放的重画等几何量稳定之后才发生（ADR 0010 amendment A3b）：先让这一帧观察到新几何量并
            // 只写擦除，再让几何量静止过安静间隔，重画才落下。时间来自注入的时钟，不靠等待。
            rig.pump.pump();
            rig.clock.advance(TurbulenceGate.DEFAULT_QUIET_MILLIS + 1);
            rig.pump.pump();

            String frame = rig.frame();
            // WINCH 后行编辑器按新宽度重新捕获，活区按新宽度重排；两者在同一帧里，光标仍有效。
            assertTrue(frame.contains("> resize me!"), frame);
            CaptureDisplay.Frame captured = rig.reader.capture().lastFrame();
            assertEquals(40, captured.columns(), "the capture must follow the new width");
            // 光标坐标按新宽度的 stride（cols + 1）计：单行、第 12 列（`> resize me!`）。
            assertEquals(12, captured.cursorPos(), captured.plain().toString());
            assertTrue(frame.contains("glm-5.1"), "the dock is re-laid out in the same frame:\n" + frame);
        }
    }

    @Test
    void aStockLineEditorIsNotWiredAsAnInputBoxProducer() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(60, 12);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        java.io.PrintWriter writer = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        LineReader stock = Mockito.mock(LineReader.class);
        Mockito.when(stock.isReading()).thenReturn(true);
        ManualFramePump pump = new ManualFramePump();

        // 降级路径：终端能力不足时装的是原生行编辑器，此时活区没有输入行条目，形态与今天一致。
        try (InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker())) {
            renderer.bindLineReader(stock);
            var answer = renderer.beginAnswer();
            answer.append("plain push\n");
            answer.finish();
            pump.pump();

            String frame = AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
            assertTrue(frame.contains("plain push"), frame);
            Mockito.verify(stock, Mockito.never()).printAbove(Mockito.anyString());
        }
    }

    @Test
    void terminalCapabilityGateDecidesWhetherTheBrushIsHandedOver() {
        Terminal capable = Mockito.mock(Terminal.class);
        Mockito.when(capable.getType()).thenReturn("xterm-256color");
        Mockito.when(capable.getSize()).thenReturn(new Size(80, 24));
        assertTrue(CafeCliLineReader.supported(capable));

        Terminal dumb = Mockito.mock(Terminal.class);
        Mockito.when(dumb.getType()).thenReturn("dumb");
        Mockito.when(dumb.getSize()).thenReturn(new Size(80, 24));
        assertFalse(CafeCliLineReader.supported(dumb));

        Terminal tiny = Mockito.mock(Terminal.class);
        Mockito.when(tiny.getType()).thenReturn("xterm-256color");
        Mockito.when(tiny.getSize()).thenReturn(new Size(80, 3));
        assertFalse(CafeCliLineReader.supported(tiny));

        assertFalse(CafeCliLineReader.supported(null));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
