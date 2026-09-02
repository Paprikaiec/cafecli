package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins one rule and one rule only: <b>a {@link Display} is never handed a geometry change</b>. The
 * strip throws the instance away and builds a new one ({@code LiveRegionStrip.installFreshDisplay}),
 * and {@code Display.resize} is called only on an instance that has never painted.
 *
 * <p>The rule needs pinning because {@code resize} looks like the obvious thing to call and is
 * cheaper. What it actually does is rebuild the display's line model — {@code oldLines} — by joining
 * the previous rows and re-splitting them at the new width. Every row {@code oldLines} claims is a
 * row the next {@code update} will diff against instead of painting. After a
 * [[destructive resize reset]] the screen holds none of them, so every one of those claims is false
 * and the diff silently skips real work. A fresh instance starts with empty {@code oldLines} and has
 * nothing to be wrong about.
 *
 * <p>The three cases below are the observable shape of that. Reusing an instance emits a stray row
 * advance, because rejoining rows that each end in {@code '\n'} splits off a trailing empty row that
 * the next update walks the cursor down to reconcile. A fresh instance repaints every row with
 * exactly one advance between rows and ends on content. The third case records that the stray
 * advance comes specifically from terminating the <em>final</em> row — which is worth knowing, but
 * is not a reason to stop terminating it, because the rule above already makes the phantom
 * unreachable in production.
 *
 * <p>This test says nothing about how far anything is erased. It also does not justify hard
 * termination — that justification is a property of the painted region, is stated on
 * {@code LiveRegionStrip.hardTerminated}, and is gated by
 * {@code ScreenTruthRegressionTest.everyLiveRegionRowIsItsOwnLineInWhatTheTerminalGivesBack}
 * (ADR 0010 amendment A18).
 */
class HardTerminationCouplingTest {

    private static final int COLUMNS = 40;

    private static List<AttributedString> region(boolean terminateLast) {
        List<AttributedString> lines = new ArrayList<>();
        String[] body = {"  spinner", "  one", "  two", "  dock-top"};
        for (String text : body) {
            lines.add(new AttributedStringBuilder().append(text).append('\n').toAttributedString());
        }
        AttributedStringBuilder last = new AttributedStringBuilder().append("x".repeat(COLUMNS - 1));
        if (terminateLast) {
            last.append('\n');
        }
        lines.add(last.toAttributedString());
        return lines;
    }

    /** Paint a region, then change only the height — either on the same Display or on a fresh one. */
    private static String afterHeightChange(boolean terminateLast, boolean freshDisplay)
            throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (TracingTerminal terminal = new TracingTerminal(
                "hard-termination", "xterm-256color", new PipedInputStream(16), sink)) {
            terminal.setSize(new Size(COLUMNS, 12));
            Display display = new Display(terminal, false);
            display.setDelayLineWrap(true);
            display.resize(12, COLUMNS);
            display.update(region(terminateLast), -1, true);
            int mark = sink.size();

            terminal.setSize(new Size(COLUMNS, 13));
            if (freshDisplay) {
                display = new Display(terminal, false);
                display.setDelayLineWrap(true);
            }
            display.resize(13, COLUMNS);
            display.update(region(terminateLast), -1, true);
            return sink.toString(StandardCharsets.UTF_8).substring(mark)
                    .replace("", "<ESC>").replace("\r", "<CR>").replace("\n", "<LF>");
        }
    }

    @Test
    void reusingADisplayAcrossAHeightChangeDisplacesTheCursorByOneRow() {
        // The one-row displacement is why a reused Display is never handed a geometry change.
        assertEquals("<CR><CR><LF>", assertDoesNotThrowIo(() -> afterHeightChange(true, false)),
                "JLine still reconciles the phantom trailing line produced by Display.resize");
    }

    @Test
    void aFreshDisplayRepaintsWithoutAnyExtraRowAdvance() {
        // A fresh instance has empty oldLines and cannot split off a phantom: it repaints all five
        // rows with four inter-row advances and ends on content. This is the property the reset relies on.
        String emitted = assertDoesNotThrowIo(() -> afterHeightChange(true, true));
        assertEquals(4, emitted.split("<CR><CR><LF>", -1).length - 1,
                "a fresh Display advances exactly once between rows: " + emitted);
        assertTrue(emitted.endsWith("x".repeat(COLUMNS - 1)),
                "the repaint must end on the last row's content, not on a row advance: " + emitted);
    }

    @Test
    void theStrayAdvanceComesFromTerminatingTheFinalRow() {
        // Attribution only. The final row's terminator is what `resize` splits a phantom off, and it
        // is also the one terminator with no effect on line structure: JLine treats the last row as
        // non-wrapping either way. Dropping it is therefore available but buys nothing — the rule
        // that a Display never sees a geometry change already makes the phantom unreachable.
        assertEquals("", assertDoesNotThrowIo(() -> afterHeightChange(false, false)),
                "without the final newline a reused Display emits nothing at all");
    }

    private interface IoSupplier {
        String get() throws Exception;
    }

    private static String assertDoesNotThrowIo(IoSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
