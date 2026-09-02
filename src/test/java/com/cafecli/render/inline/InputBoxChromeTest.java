package com.cafecli.render.inline;

import org.jline.terminal.Size;
import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the [[输入框]] looks like on screen (issue 09, ADR 0016 D6).
 *
 * <p>Every assertion here reads rows the user sees — their plain text, their column width, and the
 * colour actually emitted for a given column. Nothing asserts erase distances, cursor arithmetic or
 * byte sequences.
 */
class InputBoxChromeTest {

    private static final String RULE = "─";

    // --------------------------------------------------------------- shape at a normal size

    @Test
    void twoDimRulesBracketTheInputRowAndSpanWidthMinusOne() {
        InputBoxChrome.Box box = decorate(List.of(row("> hello")), 0, 60, 30);

        List<String> plain = plain(box);
        assertEquals(3, plain.size(), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(0), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(2), plain.toString());
        assertTrue(plain.get(1).startsWith("> hello"), plain.toString());
        assertEquals(59, box.rows().get(0).columnLength(), plain.get(0));
        assertEquals(59, box.rows().get(2).columnLength(), plain.get(2));
        assertTrue(box.rows().get(0).toAnsi().contains("2m"),
                "the rules are dim: " + box.rows().get(0).toAnsi());
    }

    @Test
    void theInputRowSitsOneRowBelowTheTopRuleSoTheCursorFollowsIt() {
        InputBoxChrome.Box box = decorate(List.of(row("> hello")), 0, 60, 30);

        assertEquals(1, box.cursorRowShift());
    }

    // --------------------------------------------------------------- the wash (primary criterion)

    @Test
    void anEmptyBufferStillCarriesTheFullWidthWash() {
        // Nothing typed: the row is the bare prompt. The wash is what makes an empty box read as a
        // field that is waiting rather than as a prompt between two separators (ADR 0016 D6).
        InputBoxChrome.Box box = decorate(List.of(row("> ")), 0, 60, 30);

        AttributedString inputRow = box.rows().get(1);
        assertEquals(59, inputRow.columnLength(), inputRow.toString());
        assertTrue(inputRow.subSequence(0, 1).toAnsi().contains("48;5;236"), inputRow.toAnsi());
        assertTrue(inputRow.subSequence(58, 59).toAnsi().contains("48;5;236"),
                "the wash must reach the last washed column even with nothing typed: "
                        + inputRow.toAnsi());
    }

    @Test
    void theWashIsTheSameOneTheSubmittedBlockUses() {
        Terminal terminal = terminal(60, "xterm-256color", 256);
        InputBoxChrome.Box box = decorate(List.of(row("> hello")), 0, 60, 30);

        AttributedString submitted = SubmittedPromptBlock.rows("hello", 60, terminal).get(0);
        AttributedString live = box.rows().get(1);

        assertEquals(background(submitted, 30), background(live, 30),
                "submitting must read as the field freezing upward, so the two washes are one wash");
    }

    @Test
    void aSixteenColourTerminalGetsNoBackgroundWashAndIsNotPadded() {
        InputBoxChrome.Box box = InputBoxChrome.decorate(
                List.of(row("> hello")), 0, 60, 30, 12, InputBoxChrome.Palette.COLOR_16);

        AttributedString inputRow = box.rows().get(1);
        assertFalse(inputRow.toAnsi().contains("48;5;"), inputRow.toAnsi());
        assertEquals("> hello", inputRow.toString(),
                "with no background there is nothing to pad, as with DiffBlock and SubmittedPromptBlock");
        // The rules still bracket it, so the box is still a box.
        assertEquals(RULE.repeat(59), box.rows().get(0).toString());
    }

    @Test
    void ansiDisabledLeavesNoEscapeResidueAnywhereInTheBox() {
        // A capable terminal draws U+2500 through DEC Special Graphics, which is a charset escape
        // even with every colour turned off. The ANSI-disabled rung therefore uses ASCII outright —
        // exactly JLine's own fallback for a terminal with no line-drawing capability.
        Terminal capable = terminal(60, "xterm-256color", 256);
        Mockito.when(capable.getStringCapability(InfoCmp.Capability.enter_alt_charset_mode))
                .thenReturn("\033(0");
        Mockito.when(capable.getStringCapability(InfoCmp.Capability.exit_alt_charset_mode))
                .thenReturn("\033(B");

        InputBoxChrome.Box box = InputBoxChrome.decorate(
                List.of(row("> hello")), 0, 60, 30, 12, InputBoxChrome.Palette.PLAIN);

        for (AttributedString rendered : box.rows()) {
            String ansi = rendered.toAnsi(capable);
            assertFalse(ansi.chars().anyMatch(ch -> ch == 27), ansi);
        }
        assertEquals(List.of("-".repeat(59), "> hello", "-".repeat(59)), plain(box));
    }

    @Test
    void theTerminalsOwnCapabilitiesSelectThePalette() {
        // 断言的是 `fromCapabilities` 而不是 `of`：`of` 还要乘上进程级的颜色总开关，而那个开关是
        // `AnsiStyle` 里一个每 JVM 只算一次的 `static final`，`TerminalMarkdownRendererTest` 会在它的
        // 静态初始化里把它关掉。断言 `of` 因此只在「谁先加载」这件事上成立——那是 flake，不是判据。
        assertEquals(InputBoxChrome.Palette.PLAIN,
                InputBoxChrome.Palette.fromCapabilities(terminal(60, "dumb", null)));
        assertEquals(InputBoxChrome.Palette.COLOR_256,
                InputBoxChrome.Palette.fromCapabilities(terminal(60, "xterm-256color", 256)));
        assertEquals(InputBoxChrome.Palette.COLOR_16,
                InputBoxChrome.Palette.fromCapabilities(terminal(60, "xterm", 16)));
    }

    @Test
    void theProcessWideColourSwitchOverridesEveryCapability() {
        // 颜色关掉时，再有能力的终端也走 PLAIN——这是 `of` 相对 `fromCapabilities` 多出来的那一层。
        // 开关翻不动（`static final`），所以这里按它当前的取值断言两条中的对应一条，两条都是真判据。
        InputBoxChrome.Palette selected =
                InputBoxChrome.Palette.of(terminal(60, "xterm-256color", 256));
        if (AnsiStyle.isEnabled()) {
            assertEquals(InputBoxChrome.Palette.COLOR_256, selected);
        } else {
            assertEquals(InputBoxChrome.Palette.PLAIN, selected);
        }
    }

    @Test
    void theWashKeepsTheMarkersOwnPurpleForeground() {
        AttributedString prompt = new AttributedString(">",
                AttributedStyle.DEFAULT.foreground(141));
        AttributedString captured = new org.jline.utils.AttributedStringBuilder()
                .append(prompt).append(" hello").toAttributedString();

        InputBoxChrome.Box box = decorate(List.of(captured), 0, 60, 30);

        String marker = box.rows().get(1).subSequence(0, 1).toAnsi();
        assertTrue(marker.contains("38;5;141"), marker);
        assertTrue(marker.contains("48;5;236"), marker);
        assertFalse(box.rows().get(1).subSequence(2, 7).toAnsi().contains("38;5;141"),
                "only the marker is purple: " + box.rows().get(1).toAnsi());
    }

    // --------------------------------------------------------------- the marker

    @Test
    void theLiveMarkerIsTheSamePurpleAsItsFrozenCounterpart() {
        Terminal terminal = terminal(60, "xterm-256color", 256);
        AttributedString frozenMarker =
                SubmittedPromptBlock.rows("hello", 60, terminal).get(0).subSequence(0, 1);

        String prompt = InputBoxChrome.prompt(InputBoxChrome.Palette.COLOR_256);

        assertEquals("> ", AttributedString.fromAnsi(prompt).toString(),
                "the editing marker is `>`; the `* ` editing marker is retired");
        assertTrue(prompt.contains("38;5;141"), prompt);
        assertTrue(frozenMarker.toAnsi().contains("38;5;141"), frozenMarker.toAnsi());
    }

    @Test
    void theMarkerDegradesToMagentaAndThenToPlainText() {
        assertTrue(InputBoxChrome.prompt(InputBoxChrome.Palette.COLOR_16).contains("35m"),
                InputBoxChrome.prompt(InputBoxChrome.Palette.COLOR_16));
        assertEquals("> ", InputBoxChrome.prompt(InputBoxChrome.Palette.PLAIN));
    }

    // --------------------------------------------------------------- we only decorate, never re-lay

    @Test
    void aCapturedRowIsNeverReclippedOrRewrapped() {
        // JLine already placed the right prompt at the terminal's right edge; the row arrives full
        // width. Decoration may add padding, never remove or move a column.
        String laidOut = "> hello" + " ".repeat(60 - 7 - 9) + "y / n / a";
        InputBoxChrome.Box box = decorate(List.of(row(laidOut)), 0, 60, 30);

        assertEquals(laidOut, box.rows().get(1).toString(),
                "a row already at the terminal width must survive untouched");
        assertEquals(60, box.rows().get(1).columnLength());
    }

    @Test
    void aRowThatFillsTheTerminalTakesTheRulesOutWithIt() {
        // Manual acceptance, 2026-08-28: with a long line the wash reached the right edge while the
        // rules stopped one column short, so the box read as a stripe sticking out past its own
        // borders. The rules default to width - 1 (the dock's rule: nothing we pad occupies the last
        // column), but the input rows are not ours to pad — LineReaderImpl wraps at the terminal's
        // own width, so a filled row *is* width columns. Clipping it is forbidden (D6: decoration
        // only ever adds columns), so the rules grow to meet it instead.
        String filled = "x".repeat(60);
        InputBoxChrome.Box box = decorate(List.of(row(filled)), 0, 60, 30);

        assertEquals(60, box.rows().get(1).columnLength(), "the captured row is untouched");
        assertEquals(60, box.rows().get(0).columnLength(),
                "the top rule must be as wide as the widest row in the box:\n" + plain(box));
        assertEquals(60, box.rows().get(2).columnLength(),
                "the bottom rule must be as wide as the widest row in the box:\n" + plain(box));
    }

    @Test
    void aBoxThatDoesNotFillTheTerminalStillYieldsTheLastColumn() {
        // The other half of the same rule: growing the rules is driven by the rows, not by the
        // terminal, so an idle box keeps the documented width - 1 and the cursor stays out of the
        // last column.
        InputBoxChrome.Box box = decorate(List.of(row("> ")), 0, 60, 30);

        for (AttributedString rendered : box.rows()) {
            assertEquals(59, rendered.columnLength(), rendered.toString());
        }
    }

    @Test
    void aTallBoxSizesItsRulesFromTheRowsItActuallyShows() {
        // The widest row is measured over the *visible* window, not the whole buffer, because the
        // rows outside it are not on screen to align against.
        List<AttributedString> rows = new ArrayList<>();
        rows.add(row("x".repeat(60)));
        for (int i = 0; i < 6; i++) {
            rows.add(row("short " + i));
        }
        InputBoxChrome.Box box = decorate(rows, 6, 60, 30, 4);

        assertFalse(plain(box).contains("x".repeat(60)), "the filled row scrolled out of the box");
        assertEquals(59, box.rows().get(0).columnLength(),
                "a row that is not shown must not widen the rules:\n" + plain(box));
    }

    @Test
    void thereAreNoVerticalBorderCharactersAnywhere() {
        InputBoxChrome.Box box = decorate(
                List.of(row("> hello"), row("second line")), 1, 60, 30);

        for (String rendered : plain(box)) {
            for (char forbidden : "│┃|╭╮╰╯┌┐└┘├┤".toCharArray()) {
                assertFalse(rendered.indexOf(forbidden) >= 0,
                        "vertical borders are excluded by ADR 0016 D6: " + rendered);
            }
        }
    }

    // --------------------------------------------------------------- CJK

    @Test
    void cjkIsPaddedAndMeasuredByColumnWidthNotCharacterCount() {
        InputBoxChrome.Box box = decorate(List.of(row("> 重构这个类")), 0, 40, 30);

        AttributedString inputRow = box.rows().get(1);
        assertEquals(39, inputRow.columnLength(), inputRow.toString());
        // 2 prompt columns + 5 double-width characters = 12 columns of content, 27 of padding.
        assertEquals("> 重构这个类" + " ".repeat(27), inputRow.toString());
        assertEquals(39, box.rows().get(0).columnLength());
    }

    // --------------------------------------------------------------- multi-line growth

    @Test
    void multiLineInputGrowsDownwardWithTheRulesFollowing() {
        InputBoxChrome.Box box = decorate(
                List.of(row("> first"), row("second"), row("third")), 2, 60, 30);

        List<String> plain = plain(box);
        assertEquals(5, plain.size(), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(0));
        assertTrue(plain.get(1).startsWith("> first"), plain.toString());
        assertTrue(plain.get(2).startsWith("second"), plain.toString());
        assertTrue(plain.get(3).startsWith("third"), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(4));
    }

    @Test
    void pastTheCapTheBoxScrollsInternallyAndKeepsBothRules() {
        List<AttributedString> typed = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            typed.add(row("line " + i));
        }
        // The box may occupy 5 rows in all: 2 rules + 3 of the 8 typed rows.
        InputBoxChrome.Box box = InputBoxChrome.decorate(
                typed, 7, 60, 30, 5, InputBoxChrome.Palette.COLOR_256);

        List<String> plain = plain(box);
        assertEquals(5, plain.size(), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(0));
        assertEquals(RULE.repeat(59), plain.get(4));
        assertTrue(plain.get(1).startsWith("line 6"), plain.toString());
        assertTrue(plain.get(3).startsWith("line 8"), plain.toString());
        // The cursor's row scrolled with it: row 7 of the buffer is drawn at box row 3.
        assertEquals(1 - 5, box.cursorRowShift());
    }

    @Test
    void scrollingKeepsTheCursorRowVisibleWhenItIsNotAtTheEnd() {
        List<AttributedString> typed = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            typed.add(row("line " + i));
        }
        InputBoxChrome.Box box = InputBoxChrome.decorate(
                typed, 1, 60, 30, 5, InputBoxChrome.Palette.COLOR_256);

        List<String> plain = plain(box);
        assertTrue(plain.get(1).startsWith("line 1"), plain.toString());
        assertTrue(plain.get(3).startsWith("line 3"), plain.toString());
        assertEquals(1, box.cursorRowShift());
    }

    @Test
    void theInputRowsWinTheLastRowWhenTheBoxCannotAffordItsOwnRules() {
        InputBoxChrome.Box box = InputBoxChrome.decorate(
                List.of(row("> hello")), 0, 60, 30, 1, InputBoxChrome.Palette.COLOR_256);

        assertEquals(1, box.rows().size(), plain(box).toString());
        assertTrue(box.rows().get(0).toString().startsWith("> hello"), plain(box).toString());
        assertEquals(0, box.cursorRowShift());
    }

    // --------------------------------------------------------------- short-terminal ladder

    @Test
    void belowTheTwoRuleThresholdOnlyTheTopRuleSurvives() {
        InputBoxChrome.Box box = decorate(List.of(row("> hello")), 0, 60, 19);

        List<String> plain = plain(box);
        assertEquals(2, plain.size(), plain.toString());
        assertEquals(RULE.repeat(59), plain.get(0));
        assertTrue(plain.get(1).startsWith("> hello"), plain.toString());
        assertEquals(1, box.cursorRowShift());
    }

    @Test
    void belowTheOneRuleThresholdOnlyTheInputRowSurvivesAndIsStillWashed() {
        InputBoxChrome.Box box = decorate(List.of(row("> hello")), 0, 60, 11);

        List<String> plain = plain(box);
        assertEquals(1, plain.size(), plain.toString());
        assertTrue(plain.get(0).startsWith("> hello"), plain.toString());
        assertEquals(59, box.rows().get(0).columnLength());
        assertEquals(0, box.cursorRowShift());
    }

    @Test
    void theLadderRungsAreExactlyTwentyAndTwelve() {
        // Picked, not measured (see InputBoxChrome's javadoc). Pinned so a change is deliberate.
        assertEquals(3, decorate(List.of(row("> x")), 0, 60, 20).rows().size());
        assertEquals(2, decorate(List.of(row("> x")), 0, 60, 19).rows().size());
        assertEquals(2, decorate(List.of(row("> x")), 0, 60, 12).rows().size());
        assertEquals(1, decorate(List.of(row("> x")), 0, 60, 11).rows().size());
    }

    // --------------------------------------------------------------- D4

    @Test
    void nothingToComposeMeansNoRulesAndNoWashEither() {
        // ADR 0016 D4: a capture from another width composes zero input rows. The chrome must not
        // leave an empty shell of two rules behind.
        InputBoxChrome.Box box = decorate(List.of(), -1, 60, 30);

        assertEquals(List.of(), box.rows());
        assertEquals(0, box.cursorRowShift());
    }

    // --------------------------------------------------------------- helpers

    private static InputBoxChrome.Box decorate(List<AttributedString> rows,
                                               int cursorRow,
                                               int width,
                                               int terminalRows) {
        return decorate(rows, cursorRow, width, terminalRows, Math.max(1, terminalRows - 2));
    }

    private static InputBoxChrome.Box decorate(List<AttributedString> rows,
                                               int cursorRow,
                                               int width,
                                               int terminalRows,
                                               int maxBoxRows) {
        return InputBoxChrome.decorate(rows, cursorRow, width, terminalRows, maxBoxRows,
                InputBoxChrome.Palette.COLOR_256);
    }

    private static AttributedString row(String text) {
        return new AttributedString(text);
    }

    private static List<String> plain(InputBoxChrome.Box box) {
        return box.rows().stream().map(AttributedString::toString).toList();
    }

    /** The background colour actually emitted for a column, as the terminal receives it. */
    private static String background(AttributedString row, int column) {
        String ansi = row.subSequence(column, column + 1).toAnsi();
        int at = ansi.indexOf("48;5;");
        return at < 0 ? "none" : ansi.substring(at, ansi.indexOf('m', at));
    }

    private static Terminal terminal(int columns, String type, Integer maxColors) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn(type);
        Size size = new Size(columns, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getNumericCapability(InfoCmp.Capability.max_colors))
                .thenReturn(maxColors);
        return terminal;
    }
}
