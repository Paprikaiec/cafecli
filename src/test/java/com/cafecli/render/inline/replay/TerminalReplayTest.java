package com.cafecli.render.inline.replay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerminalReplayTest {

    @Test
    void replaysWritesIntoCanonicalHistory() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 3, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(new ReplayTrace.WriteEvent(
                        0,
                        0,
                        "hello\r\nworld".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of(), result.scrollback());
        assertEquals(List.of("hello", "world", ""), result.screen());
        assertEquals("hello\nworld\n\n", result.text());
    }

    @Test
    void resizeReflowsPhysicalRowsButKeepsLogicalHistory() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 4, 3, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(
                                0,
                                0,
                                "abcd\r\nxy".getBytes(StandardCharsets.UTF_8)),
                        new ReplayTrace.ResizeEvent(1, 10, 1, 4, 2)));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of(), result.scrollback());
        assertEquals(List.of("ab", "cd", "xy", ""), result.screen());
        assertEquals("abcd\nxy\n\n", result.text());
    }

    @Test
    void integerMultipleResizeLeavesCursorOnFollowingPhysicalRow() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 4, 3, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(0, 0, "abcd".getBytes(StandardCharsets.UTF_8)),
                        new ReplayTrace.ResizeEvent(1, 10, 1, 3, 4),
                        new ReplayTrace.WriteEvent(2, 20, "\u001b[2K".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of("abcd", "", ""), result.screen());
    }

    @Test
    void nonPendingCursorAtANewWidthMultipleClampsToThePreviousRowsLastColumn() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 6, 4, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(0, 0, "abcdefgh".getBytes(StandardCharsets.UTF_8)),
                        new ReplayTrace.ResizeEvent(1, 1, 1, 4, 4),
                        new ReplayTrace.WriteEvent(2, 2, "\u001b[2KX".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of("abcd", "   X", "", ""), result.screen());
        assertEquals("abcd   X\n\n\n", result.text());
    }

    @Test
    void exactComparisonSlicesBothHistoriesAtTheAnchorAndReportsTheFirstMismatch() {
        TerminalReplay.Comparison comparison = TerminalReplay.compareExact(
                "shell truth\nCAFECLI_REPLAY_START\nsame\nexpected",
                "different model prefix\nCAFECLI_REPLAY_START\nsame\nactual",
                "CAFECLI_REPLAY_START");

        assertFalse(comparison.matches());
        assertEquals(3, comparison.line());
        assertEquals(1, comparison.column());
        assertEquals("history differs at UTF-8 byte 26 (line 3, column 1): expected 'e', actual 'a'",
                comparison.diagnostic());
    }

    @Test
    void lineFeedTranslationIsTakenFromTheTraceHeader() {
        byte[] write = "a\nb".getBytes(StandardCharsets.UTF_8);
        TerminalReplay.Result bareLf = TerminalReplay.replay(new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 4, 2, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(new ReplayTrace.WriteEvent(0, 0, write))));
        TerminalReplay.Result onlcr = TerminalReplay.replay(new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 4, 2, List.of("onlcr", "opost"),
                        ReplayTrace.LineFeedMode.CRLF),
                List.of(new ReplayTrace.WriteEvent(0, 0, write))));

        assertEquals(List.of("a", " b"), bareLf.screen());
        assertEquals(List.of("a", "b"), onlcr.screen());
    }

    @Test
    void canonicalHistoryRejoinsSoftWrapsButPreservesHardLineBreaks() {
        ReplayTrace.Header header = new ReplayTrace.Header(
                1, "xterm-256color", 4, 4, List.of(), ReplayTrace.LineFeedMode.LF);
        TerminalReplay.Result softWrapped = TerminalReplay.replay(new ReplayTrace(
                header,
                List.of(new ReplayTrace.WriteEvent(
                        0, 0, "abcde".getBytes(StandardCharsets.UTF_8)))));
        TerminalReplay.Result hardWrapped = TerminalReplay.replay(new ReplayTrace(
                header,
                List.of(new ReplayTrace.WriteEvent(
                        0, 0, "abcd\r\ne".getBytes(StandardCharsets.UTF_8)))));

        assertEquals(List.of("abcd", "e", "", ""), softWrapped.screen());
        assertEquals("abcde\n\n\n", softWrapped.text());
        assertEquals("abcd\ne\n\n\n", hardWrapped.text());
    }

    @Test
    void utf8GlyphsAndEscapeSequencesMaySpanRecordedWrites() {
        byte[] glyph = "界".getBytes(StandardCharsets.UTF_8);
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 2, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(0, 0, new byte[]{glyph[0], glyph[1]}),
                        new ReplayTrace.WriteEvent(1, 1, new byte[]{glyph[2], 0x1b, '['}),
                        new ReplayTrace.WriteEvent(2, 2, "31mX".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of("界X", ""), result.screen());
        assertEquals("界X\n\n", result.text());
    }

    @Test
    void decSpecialGraphicsAreSerializedAsTheGlyphTerminalAppDisplays() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 2, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(new ReplayTrace.WriteEvent(
                        0, 0, "\u001b(0x\u001b(B".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of("│", ""), result.screen());
        assertEquals("│\n\n", result.text());
    }

    @Test
    void terminalAppHistoryTerminatesTheFinalNonWrappedScreenRow() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 1, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(new ReplayTrace.WriteEvent(
                        0, 0, "x".getBytes(StandardCharsets.UTF_8))));

        assertEquals("x\n", TerminalReplay.replay(trace).text());
    }

    @Test
    void heightGrowthPullsRecentHistoryBackOntoTheScreenToKeepTheBottomEdgePinned() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 3, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(
                                0, 0, "one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8)),
                        new ReplayTrace.ResizeEvent(1, 1, 1, 2, 8),
                        new ReplayTrace.ResizeEvent(2, 2, 2, 3, 8)));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of(), result.scrollback());
        assertEquals(List.of("one", "two", "three"), result.screen());
        assertEquals("one\ntwo\nthree\n", result.text());
    }

    @Test
    void resizeKeepsAWideGlyphWholeBeforeLaterAbsoluteErase() {
        ReplayTrace trace = new ReplayTrace(
                new ReplayTrace.Header(1, "xterm-256color", 8, 4, List.of(), ReplayTrace.LineFeedMode.LF),
                List.of(
                        new ReplayTrace.WriteEvent(
                                0, 0, "abc界Z".getBytes(StandardCharsets.UTF_8)),
                        new ReplayTrace.ResizeEvent(1, 1, 1, 5, 4),
                        new ReplayTrace.WriteEvent(
                                2, 2, "\u001b[2;1H\u001b[2KX".getBytes(StandardCharsets.UTF_8))));

        TerminalReplay.Result result = TerminalReplay.replay(trace);

        assertEquals(List.of("abc", "X", "", "", ""), result.screen());
    }
}
