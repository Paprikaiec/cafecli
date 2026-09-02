package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.jline.utils.Status;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void formatStatusLineIncludesAllFields() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "running");
        String line = BottomStatusBar.formatFooterLine(info, 200);
        assertTrue(line.contains("Auto Model"), line);
        assertTrue(line.contains("running"), line);
        assertTrue(line.contains("glm-5.1"), line);
        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1%"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("in 1.0k out 234"), line);
        assertTrue(line.contains("cache 100"), line);
        assertTrue(line.contains("¥0.0123"), line);
        assertTrue(line.contains("1.5s"), line);
    }

    @Test
    void contextSegmentUsesContextTokensNotUsageTotals() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 51_000L, 2_000L, 0L,
                null, false, 0L, "idle");
        String line = BottomStatusBar.formatFooterLine(info, 200);

        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("in 51.0k out 2.0k"), line);
        assertFalse(line.contains("53.0k/200.0k"), line);
    }

    @Test
    void formatStatusLinePadsToColumnWidth() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertTrue(visible(line).length() == 80, "status line should fill the bar: " + visible(line).length());
    }

    @Test
    void formatStatusLineTruncatesWhenLong() {
        StatusInfo info = new StatusInfo("very-long-model-name-exceeding-cols",
                999_999L, 200_000L, true, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 30);
        assertTrue(visible(line).length() == 30);
    }

    @Test
    void formatStatusLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void formatStatusLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, false, 250L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void footerLineFitsColumnWidth() {
        String line = BottomStatusBar.formatFooterLine(StatusInfo.idle("glm-5.1", 200_000L, false), 40);
        assertTrue(line.length() == 40, "footer should fill requested width: " + line.length());
        assertTrue(line.contains("Auto Model"), line);
    }

    @Test
    void activeStatusLineShowsPhase() {
        StatusInfo info = StatusInfo.active("glm-5.1", 200_000L, false, "plan")
                .withEnvironment("MCP 4/4", "Skill 2/2");
        String top = BottomStatusBar.formatStatusLine(info, 80);
        String bottom = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(top.contains("4 MCP servers"), top);
        assertTrue(top.contains("2 skills"), top);
        assertTrue(bottom.contains("plan"), bottom);
        assertTrue(bottom.contains("glm-5.1"), bottom);
    }

    @Test
    void statusLinesUseJLineAttributes() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        var lines = BottomStatusBar.formatStatusLines(info, 80);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).toString().contains("YOLO"), "status row should show current mode");
        assertTrue(lines.get(1).toAnsi().contains("[2m"), "footer row should use subtle style");
    }

    @Test
    void statusLineDescribesCtrlYActionForBothApprovalModes() {
        String hitl = BottomStatusBar.formatStatusLine(
                StatusInfo.idle("glm-5.1", 200_000L, true), 80);
        String yolo = BottomStatusBar.formatStatusLine(
                StatusInfo.idle("glm-5.1", 200_000L, false), 80);

        assertTrue(hitl.contains("HITL Ctrl+Y for YOLO"), hitl);
        assertTrue(yolo.contains("YOLO Ctrl+Y to enable HITL"), yolo);
    }

    @Test
    void dockNeverWritesToTheTerminalItself() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (Terminal terminal = headlessTerminal(sink)) {
            BottomStatusBar bar = new BottomStatusBar();
            bar.start();
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            bar.update(StatusInfo.active("glm-5.1", 200_000L, false, "react"));
            bar.rows(80);
            bar.close();
            terminal.flush();

            // The dock is not a painter any more: the render thread composes its rows into the live
            // frame. A second writer here is exactly what the JLine Status used to be.
            assertEquals("", sink.toString(StandardCharsets.UTF_8),
                    "the dock must emit nothing of its own");
        }
    }

    @Test
    void lifecycleCallsAreSafeInAnyOrder() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        BottomStatusBar bar = new BottomStatusBar();
        bar.close();
        bar.start();
        bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
        bar.close();
        assertTrue(bar.rows(80).isEmpty(), "a closed dock contributes no rows");
    }

    @Test
    void dockNeverCreatesAJLineStatus() throws Exception {
        try (Terminal terminal = headlessTerminal()) {
            BottomStatusBar bar = new BottomStatusBar();
            bar.start();
            try {
                bar.update(StatusInfo.active("glm-5.1", 200_000L, false, "react"));
                assertNull(Status.getStatus(terminal, false),
                        "the dock must never ask JLine for a Status: JLine itself only queries the "
                                + "non-creating overload, so it has to stay null forever");
            } finally {
                bar.close();
            }
        }
    }

    /** A real (non-mock) {@code AbstractTerminal}: {@code Status.getStatus} requires one. */
    private static Terminal headlessTerminal() throws java.io.IOException {
        return headlessTerminal(new ByteArrayOutputStream());
    }

    private static Terminal headlessTerminal(ByteArrayOutputStream sink) throws java.io.IOException {
        ExternalTerminal terminal = new ExternalTerminal("dock-test", "xterm-256color",
                new java.io.ByteArrayInputStream(new byte[0]), sink, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 24));
        return terminal;
    }

    @Test
    void dockRowsAreOnlyProducedInsideTheStartedLifecycle() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        BottomStatusBar bar = new BottomStatusBar();

        assertTrue(bar.rows(80).isEmpty(), "no dock rows before start");
        bar.start();
        try {
            assertTrue(bar.rows(80).isEmpty(), "no dock rows until the first status arrives");
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            assertEquals(2, bar.rows(80).size(), "the dock is two rows: mode row + model row");
            assertTrue(bar.rows(80).get(0).toString().contains("YOLO"), bar.rows(80).toString());
            assertTrue(bar.rows(80).get(1).toString().contains("glm-5.1"), bar.rows(80).toString());
        } finally {
            bar.close();
        }
        assertTrue(bar.rows(80).isEmpty(), "no dock rows after close");
    }

    private static String visible(String line) {
        return line;
    }
}
