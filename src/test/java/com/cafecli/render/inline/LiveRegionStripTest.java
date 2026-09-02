package com.cafecli.render.inline;

import com.cafecli.hitl.ApprovalRequest;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRegionStripTest {

    /**
     * The rule character as it actually reaches <em>this</em> terminal. JLine never writes U+2500
     * verbatim: it emits DEC Special Graphics where the terminal advertises it and degrades to ASCII
     * where it does not. The mock below advertises none, so a rule arrives as {@code -}. The capable
     * path — DEC graphics, which {@code TerminalScreenModel} maps back to {@code ─} — is asserted in
     * {@code ScreenTruthRegressionTest}.
     */
    private static final String RULE_AS_THIS_TERMINAL_DRAWS_IT = "-";

    @Test
    void dockRowsAreDrawnAsTheTrailingRowsOfTheComposedFrame() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(
                new AttributedString("DOCK-MODE-ROW"),
                new AttributedString("DOCK-MODEL-ROW")));
        ApprovalBlock approval = new ApprovalBlock(terminal, ApprovalRequest.of(
                "write_file", "{\"path\":\"a.txt\"}", "test", "task_2"));
        strip.showApproval(approval);

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertTrue(visible.contains("DOCK-MODE-ROW"), visible);
        assertTrue(visible.indexOf("需要审批") < visible.indexOf("DOCK-MODE-ROW"), visible);
        assertTrue(visible.indexOf("DOCK-MODE-ROW") < visible.indexOf("DOCK-MODEL-ROW"), visible);
    }

    @Test
    void dockIsNeverCommittedToFrozenScrollbackWhenATurnFinishesMidStream() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(
                new AttributedString("DOCK-MODE-ROW"),
                new AttributedString("DOCK-MODEL-ROW")));
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        strip.add(answer);
        answer.append("still streaming tail");
        strip.finishTurn();

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertEquals(1, occurrences(visible, "DOCK-MODE-ROW"),
                "the dock is live-region furniture, it must never be frozen into scrollback: " + visible);
        assertEquals(1, occurrences(visible, "DOCK-MODEL-ROW"), visible);
    }

    @Test
    void liveTailHeightBudgetIsTerminalRowsMinusTheDocksOwnHeight() {
        Terminal terminal = terminal(80, 10);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(
                new AttributedString("DOCK-MODE-ROW"),
                new AttributedString("DOCK-MODEL-ROW")));
        strip.bindLeadingEntry(numberedRows(12));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        // 10 terminal rows − 2 dock rows = 8 rows of live tail, so only the last 8 survive and the
        // dock still fits underneath. No external reserved-rows supplier is involved.
        assertTrue(visible.contains("ROW-05"), visible);
        assertFalse(visible.contains("ROW-04"), visible);
        assertTrue(visible.contains("ROW-12"), visible);
        assertTrue(visible.contains("DOCK-MODEL-ROW"), visible);
    }

    @Test
    void dockStaysBelowABatchOfFrozenScrollbackAndIsNotDuplicatedByIt() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(
                new AttributedString("DOCK-MODE-ROW"),
                new AttributedString("DOCK-MODEL-ROW")));
        strip.add(new CodeBlock(terminal, "java", "int first = 1;\n"));
        strip.add(new CodeBlock(terminal, "java", "int second = 2;\n"));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertTrue(visible.indexOf("int first = 1;") < visible.indexOf("DOCK-MODE-ROW"), visible);
        assertTrue(visible.indexOf("int second = 2;") < visible.indexOf("DOCK-MODE-ROW"), visible);
        assertEquals(1, occurrences(visible, "DOCK-MODE-ROW"),
                "frozen scrollback must not leave a copy of the dock behind: " + visible);
    }

    @Test
    void dockIsRelaidOutAtTheNewWidthAfterAResize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        AtomicReference<Size> geometry = new AtomicReference<>(new Size(80, 24));
        Mockito.when(terminal.getSize()).thenAnswer(ignored -> geometry.get());
        Mockito.when(terminal.getBufferSize()).thenAnswer(ignored -> geometry.get());
        AtomicLong nanos = new AtomicLong();
        TimeSource clock = nanos::get;
        LiveRegionStrip strip = new LiveRegionStrip(terminal,
                new GeometrySampler(geometry::get), clock,
                new TurbulenceGate(TurbulenceGate.DEFAULT_QUIET_MILLIS,
                        TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS));
        strip.bindDock(width -> List.of(new AttributedString("DOCK-WIDTH=" + width)));
        strip.bindLeadingEntry(numberedRows(1));

        // 宽度减一：状态坞是活区里唯一每帧都补齐到整行的东西，少占一列，光标就不会落在最后一列。
        assertTrue(AttributedString.stripAnsi(strip.renderFrame()).contains("DOCK-WIDTH=79"));

        geometry.set(new Size(40, 24));
        // Geometry is sampled, not read on every frame: a delivered signal arms the sampler and the
        // sample it then takes is what ends the [[geometry epoch]] (ADR 0010 amendment A3c). A
        // terminal that delivers no signal at all is covered by the sampler's slow heartbeat.
        strip.armGeometrySample();

        // The frame that observes the change writes the erase and no content; the dock comes back at
        // the new width once the geometry has been quiet for the quiet interval (amendment A3b).
        assertFalse(AttributedString.stripAnsi(strip.renderFrame()).contains("DOCK-WIDTH"),
                "the frame that observes a change must not repaint the dock");
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(TurbulenceGate.DEFAULT_QUIET_MILLIS + 1));

        String resized = AttributedString.stripAnsi(strip.renderFrame());
        assertTrue(resized.contains("DOCK-WIDTH=39"), resized);
        assertFalse(resized.contains("DOCK-WIDTH=79"), resized);
    }

    // ------------------------------------------------------------ 输入行进活区

    @Test
    void inputBoxRowsSitAfterTheLiveTailAndBeforeTheDock() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindLeadingEntry(numberedRows(2));
        strip.bindInputBox(() -> inputFrame(80, 5, "> hello"));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertTrue(visible.contains("> hello"), visible);
        assertTrue(visible.indexOf("ROW-02") < visible.indexOf("> hello"), visible);
        assertTrue(visible.indexOf("> hello") < visible.indexOf("DOCK-ROW"), visible);
    }

    @Test
    void composedFrameCarriesTheInputCursorTranslatedIntoFrameCoordinates() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindLeadingEntry(numberedRows(3));
        // 输入框自己的坐标系：第 0 行第 7 列（JLine 的 Size.cursorPos = row * (cols + 1) + col）
        strip.bindInputBox(() -> inputFrame(80, 7, "> hello"));

        strip.renderFrame();

        // 活区尾占 3 行、上横线占 1 行，故输入框第 0 行在合成帧的第 4 行：4 * 81 + 7。
        // 这条断言在 issue 09 之前是 3 * 81 + 7——装饰把输入行下推了一行，光标必须跟着走，
        // 否则它会落在上横线上。
        assertEquals(4 * 81 + 7, strip.lastCursorPos());
    }

    @Test
    void multiRowInputCursorKeepsItsRowAndColumnAfterTranslation() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindLeadingEntry(numberedRows(2));
        // 第 1 行（第二行）第 4 列，含 CJK 字符——列数由 JLine 侧算好，这里只做平移
        strip.bindInputBox(() -> new CaptureDisplay.Frame(
                List.of(new AttributedString("> 中文"), new AttributedString("续行")),
                1 * 81 + 4, 80, false));

        strip.renderFrame();

        // 2 行活区尾 + 1 行上横线 + 捕获帧的第 1 行（issue 09 之前是 3 * 81 + 4）。
        assertEquals(4 * 81 + 4, strip.lastCursorPos());
    }

    @Test
    void inputBoxNeverEntersFrozenScrollback() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindInputBox(() -> inputFrame(80, 2, "> typing"));
        strip.add(new CodeBlock(terminal, "java", "int frozen = 1;\n"));
        strip.finishTurn();

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertEquals(1, occurrences(visible, "> typing"),
                "the input line is live-region furniture, it must never be frozen: " + visible);
        assertTrue(visible.indexOf("int frozen = 1;") < visible.indexOf("> typing"), visible);
    }

    @Test
    void anAbsentInputBoxLeavesTheFrameCursorUnpositioned() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindLeadingEntry(numberedRows(2));

        strip.renderFrame();

        assertEquals(-1, strip.lastCursorPos());
    }

    @Test
    void liveTailBudgetAlsoSubtractsTheInputBoxHeight() {
        Terminal terminal = terminal(80, 10);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindInputBox(() -> new CaptureDisplay.Frame(
                List.of(new AttributedString("> one"), new AttributedString("> two")), 2, 80, false));
        strip.bindLeadingEntry(numberedRows(12));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        // 10 行 − 1 行状态坞 − 2 行输入框 = 7 行活区尾
        assertTrue(visible.contains("ROW-06"), visible);
        assertFalse(visible.contains("ROW-05"), visible);
        assertTrue(visible.contains("> two"), visible);
        assertTrue(visible.contains("DOCK-ROW"), visible);
    }

    @Test
    void theInputRowIsBracketedByTwoRulesInsideTheComposedFrame() {
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindLeadingEntry(numberedRows(2));
        strip.bindInputBox(() -> inputFrame(80, 5, "> hello"));

        List<String> rows = List.of(AttributedString.stripAnsi(strip.renderFrame()).split("\n", -1));
        int at = indexOfRowStartingWith(rows, "> hello");

        assertTrue(at > 0, rows.toString());
        assertEquals(RULE_AS_THIS_TERMINAL_DRAWS_IT.repeat(79), rows.get(at - 1), rows.toString());
        assertEquals(RULE_AS_THIS_TERMINAL_DRAWS_IT.repeat(79), rows.get(at + 1), rows.toString());
        assertTrue(rows.get(at + 2).contains("DOCK-ROW"), rows.toString());
    }

    @Test
    void theRulesAreNotPaintedWhenTheInputRowsAreNot() {
        // ADR 0016 D4: a capture from another width composes zero input rows. Two rules with nothing
        // between them would be an empty shell, so the whole box goes away together.
        Terminal terminal = terminal(80, 30);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindLeadingEntry(numberedRows(2));
        strip.bindInputBox(() -> inputFrame(120, 5, "> captured at another width"));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertFalse(visible.contains(RULE_AS_THIS_TERMINAL_DRAWS_IT.repeat(10)), visible);
        assertFalse(visible.contains("> captured"), visible);
        assertTrue(visible.contains("DOCK-ROW"), visible);
        assertEquals(-1, strip.lastCursorPos());
    }

    @Test
    void theLiveTailBudgetAlsoPaysForTheRules() {
        Terminal terminal = terminal(80, 24);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW"),
                new AttributedString("DOCK-ROW-2")));
        strip.bindInputBox(() -> inputFrame(80, 2, "> typing"));
        strip.bindLeadingEntry(numberedRows(40));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        // 24 rows − 2 dock − 3 box (rule + input + rule) = 19 rows of live tail.
        assertTrue(visible.contains("ROW-22"), visible);
        assertFalse(visible.contains("ROW-21"), visible);
        assertTrue(visible.contains("> typing"), visible);
        assertTrue(visible.contains("DOCK-ROW-2"), visible);
    }

    @Test
    void aShortTerminalDropsTheRulesButKeepsTheInputRow() {
        Terminal terminal = terminal(80, 11);
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        strip.bindDock(width -> List.of(new AttributedString("DOCK-ROW")));
        strip.bindInputBox(() -> inputFrame(80, 5, "> hello"));

        String visible = AttributedString.stripAnsi(strip.renderFrame());

        assertTrue(visible.contains("> hello"), visible);
        assertFalse(visible.contains(RULE_AS_THIS_TERMINAL_DRAWS_IT.repeat(10)), visible);
    }

    private static int indexOfRowStartingWith(List<String> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static CaptureDisplay.Frame inputFrame(int columns, int cursorColumn, String text) {
        return new CaptureDisplay.Frame(List.of(new AttributedString(text)), cursorColumn, columns, false);
    }

    /** A fixed-height live entry: the simplest way to drive the strip's height arithmetic. */
    private static StripEntry numberedRows(int count) {
        List<AttributedString> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new AttributedString(String.format("ROW-%02d", i)));
        }
        return new StripEntry() {
            @Override
            public List<AttributedString> render(int width) {
                return rows;
            }

            @Override
            public List<AttributedString> freezeTopRows(int minimumRows, int width) {
                return List.of();
            }
        };
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static Terminal terminal(int cols, int rows) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(cols, rows);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        // No line-drawing capability: see RULE_AS_THIS_TERMINAL_DRAWS_IT.
        Mockito.when(terminal.getStringCapability(InfoCmp.Capability.enter_alt_charset_mode))
                .thenReturn(null);
        Mockito.when(terminal.getStringCapability(InfoCmp.Capability.exit_alt_charset_mode))
                .thenReturn(null);
        return terminal;
    }

    @Test
    void submittedPromptAndApprovalCoalescedInOneFrameBothRemainVisible() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        ApprovalBlock approval = new ApprovalBlock(terminal, ApprovalRequest.of(
                "write_file", "{\"path\":\"a.txt\"}", "test", "task_2"));

        strip.submitPrompt("submitted prompt\n");
        strip.showApproval(approval);

        String visible = AttributedString.stripAnsi(strip.renderFrame());
        assertTrue(visible.contains("submitted prompt"), visible);
        assertTrue(visible.contains("需要审批"), visible);
        assertTrue(visible.contains("task_2"), visible);
    }
}
