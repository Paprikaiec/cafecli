package com.cafecli.render.inline.replay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forensic classification has to be trustworthy before any conclusion is drawn from it, because it
 * is the thing that decides whether a live-region copy came from a height reduction or from the
 * renderer drawing below its own stale region.
 */
class ReplayForensicsTest {

    private static final ReplayTrace.Header HEADER =
            new ReplayTrace.Header(1, "xterm-256color", 20, 3, List.of(), ReplayTrace.LineFeedMode.LF);

    private static ReplayTrace.WriteEvent write(long sequence, String text) {
        return new ReplayTrace.WriteEvent(sequence, sequence, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aHeightReductionThatDropsTheTopRowIsAttributedToTheResize() {
        ReplayTrace trace = new ReplayTrace(HEADER, List.of(
                write(0, "esc to cancel\r\nb\r\nc"),
                new ReplayTrace.ResizeEvent(1, 1, 1, 2, 20)));

        ReplayForensics.Report report = ReplayForensics.analyze(trace);

        assertEquals(1, report.liveRegionEscapes().size());
        ReplayForensics.Escape escape = report.liveRegionEscapes().get(0);
        assertEquals(ReplayForensics.Kind.RESIZE_SHRINK, escape.kind());
        assertEquals("esc to cancel", escape.rowText().strip());
        assertEquals(3, escape.rowsBefore());
        assertEquals(2, escape.rowsAfter());
        assertEquals(1, escape.markersOnScreenBefore());
        assertEquals(0, escape.markersOnScreenAfter());
    }

    @Test
    void outputBelowTheRegionThatScrollsItOffIsAttributedToTheWrite() {
        ReplayTrace trace = new ReplayTrace(HEADER, List.of(
                write(0, "esc to cancel\r\nb\r\nc"),
                write(1, "\r\nd")));

        ReplayForensics.Report report = ReplayForensics.analyze(trace);

        assertEquals(1, report.liveRegionEscapes().size());
        ReplayForensics.Escape escape = report.liveRegionEscapes().get(0);
        assertEquals(ReplayForensics.Kind.WRITE_SCROLL, escape.kind());
        assertEquals("esc to cancel", escape.rowText().strip());
        assertEquals(1, escape.markersOnScreenBefore());
    }

    @Test
    void aStaleCopyAlreadyOnScreenIsVisibleInTheMarkerCountAtTheMomentOfEscape() {
        ReplayTrace trace = new ReplayTrace(HEADER, List.of(
                write(0, "esc to cancel\r\nesc to cancel\r\nc"),
                write(1, "\r\nd")));

        ReplayForensics.Report report = ReplayForensics.analyze(trace);

        ReplayForensics.Escape escape = report.liveRegionEscapes().get(0);
        assertEquals(2, escape.markersOnScreenBefore());
        assertEquals(1, escape.markersOnScreenAfter());
    }

    @Test
    void heightGrowthThatPullsRowsBackDoesNotDoubleCountALaterEscape() {
        ReplayTrace trace = new ReplayTrace(HEADER, List.of(
                write(0, "esc to cancel\r\nb\r\nc"),
                new ReplayTrace.ResizeEvent(1, 1, 1, 2, 20),
                new ReplayTrace.ResizeEvent(2, 2, 2, 3, 20),
                new ReplayTrace.ResizeEvent(3, 3, 3, 2, 20)));

        ReplayForensics.Report report = ReplayForensics.analyze(trace);

        assertEquals(2, report.liveRegionEscapes().size());
        assertTrue(report.liveRegionEscapes().stream()
                .allMatch(escape -> escape.kind() == ReplayForensics.Kind.RESIZE_SHRINK));
    }

    @Test
    void nonLiveRegionRowsAreRecordedButNotCountedAsCopies() {
        ReplayTrace trace = new ReplayTrace(HEADER, List.of(
                write(0, "transcript\r\nb\r\nc"),
                write(1, "\r\nd")));

        ReplayForensics.Report report = ReplayForensics.analyze(trace);

        assertEquals(1, report.escapes().size());
        assertEquals(0, report.liveRegionEscapes().size());
    }

    @Test
    void writesAreDescribedWithLiteralControlSequencesAndCollapsedText() {
        assertEquals("ESC[7A<CR>ESC[J\"hello\"<LF>",
                ReplayForensics.describe(
                        "\u001b[7A\r\u001b[Jhello\n".getBytes(StandardCharsets.UTF_8)));
    }
}
