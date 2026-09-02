package com.cafecli.render.inline;

import com.cafecli.render.StatusInfo;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a [[转向消息]] lands in the [[保留窗口]] (ADR 0016 D5, issue 10).
 *
 * <p>A position in the ordered commit log used to be an <b>entry</b>, fixed at that entry's first
 * commit and idempotent by identity. A streaming answer enters the log at its first committed row, so
 * anything committed while it was still growing took a position <em>after the answer's entire
 * eventual output</em> — and in the [[转写浏览层]] that mis-ordering is unconditional, because the
 * overlay renders an answer's whole retained rendering rather than its committed prefix. The overlay
 * is exactly where a user goes to ask "where did my correction land?", so the feature's stated purpose
 * would be unmet in its primary viewer.
 *
 * <p>A position is therefore a <b>source span</b>: an answer interrupted by another commit occupies
 * two of them, with the interrupting item in between.
 *
 * <p>The "reads as one block" half is asserted against an <b>independent control</b> — the same answer
 * streamed with nobody interrupting it. Deleting the steering message from the interrupted log has to
 * leave something byte-identical to that control: no duplicated row, no missing row, and no separator
 * blank sprouting where the correction happened to land.
 */
class SteeringPositionTest {

    private static final int WIDTH = 80;
    private static final String BEFORE = "the part written before the correction";
    private static final String STEER = "actually use java 21";
    private static final String AFTER = "the part written after the correction";

    // --- the criterion: position ------------------------------------------------------------------

    /**
     * Open the layer and look. No resize is involved, and none is needed — this is what the overlay
     * shows on <em>every</em> viewing.
     */
    @Test
    void theSteeringEchoAppearsInTheOverlayWhereItInterruptedTheAnswer() throws Exception {
        try (Rig rig = new Rig()) {
            List<StripEntry> log = rig.steeredTurn();

            String visible = overlayText(log);

            int before = visible.indexOf(BEFORE);
            int steer = visible.indexOf(STEER);
            int after = visible.indexOf(AFTER);
            assertTrue(before >= 0 && steer >= 0 && after >= 0,
                    "all three must be in the overlay:\n" + visible);
            assertTrue(before < steer,
                    "the correction must come after what the user was reacting to:\n" + visible);
            assertTrue(steer < after,
                    "the correction must come before what the answer said next, not after all of it:\n"
                            + visible);
        }
    }

    /** The same position in the [[bounded replay window]], which is what a reset repaints. */
    @Test
    void theSteeringEchoTakesItsPositionInTheBoundedReplayWindowToo() throws Exception {
        try (Rig rig = new Rig()) {
            List<String> rows = replayRows(rig.steeredTurn());

            assertTrue(rowIndex(rows, BEFORE) < rowIndex(rows, STEER), String.join("\n", rows));
            assertTrue(rowIndex(rows, STEER) < rowIndex(rows, AFTER), String.join("\n", rows));
        }
    }

    // --- the criterion: it still reads as one block -----------------------------------------------

    /**
     * Take the correction back out of the log and the answer must be indistinguishable from the same
     * answer nobody interrupted — in the [[bounded replay window]] and in the [[转写浏览层]] alike.
     *
     * <p>That is the whole of "no separator blank between the spans, no duplicated rows, no missing
     * rows", stated against a control rather than against a hand-counted expectation.
     */
    @Test
    void anInterruptedAnswerPaintsExactlyWhatTheSameAnswerPaintsUninterrupted() throws Exception {
        try (Rig interrupted = new Rig(); Rig control = new Rig()) {
            List<StripEntry> steered = withoutTheCorrection(interrupted.steeredTurn());
            List<StripEntry> plain = control.uninterruptedTurn();

            assertEquals(replayRows(plain), replayRows(steered),
                    "an interrupted answer must repaint as one block:\n"
                            + String.join("\n", replayRows(steered)));
        }
    }

    @Test
    void anInterruptedAnswerReadsAsOneBlockInTheOverlayToo() throws Exception {
        try (Rig interrupted = new Rig(); Rig control = new Rig()) {
            List<StripEntry> steered = withoutTheCorrection(interrupted.steeredTurn());
            List<StripEntry> plain = control.uninterruptedTurn();

            assertEquals(overlayBody(plain), overlayBody(steered),
                    "an interrupted answer must read as one block in the overlay");
        }
    }

    /**
     * The overlay is open <b>while</b> the correction lands (A13's live follow, ADR 0016 D1's safety).
     * A position that was still open when the layer measured it closes underneath the reader, so the
     * document has to notice its height shrink and re-base everything after it — otherwise the answer's
     * second half would be drawn twice, once in each position.
     */
    @Test
    void theOverlayFollowsTheCorrectionLandingWhileItIsOpen() throws Exception {
        try (Rig rig = new Rig()) {
            InlineRenderer r = rig.renderer;
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append(BEFORE + "\n\n");
            rig.pump.pump();

            OverlayDocument document = new OverlayDocument(r.retainedItems());
            document.layout(WIDTH, foldStates(r.retainedItems()), false);
            // The reader is looking at it, so the layer is holding a rendering of that position while
            // it closes underneath them — which is the case a rendering that captured its bounds once
            // would get wrong.
            document.viewport(0, document.totalRows());

            r.printSteeringPrompt(STEER);
            rig.pump.pump();
            answer.append(AFTER + "\n\n");
            answer.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            assertTrue(document.refresh(r.retainedItems()), "the document must notice the change");
            document.layout(WIDTH, foldStates(r.retainedItems()), false);
            List<String> rows = document.viewport(0, document.totalRows()).stream()
                    .map(AttributedString::toString)
                    .map(String::stripTrailing)
                    .toList();

            assertEquals(1, occurrences(rows, BEFORE), String.join("\n", rows));
            assertEquals(1, occurrences(rows, STEER), String.join("\n", rows));
            assertEquals(1, occurrences(rows, AFTER), String.join("\n", rows));
            assertTrue(rowIndex(rows, BEFORE) < rowIndex(rows, STEER)
                            && rowIndex(rows, STEER) < rowIndex(rows, AFTER),
                    "the correction must stay between the two halves as the layer follows:\n"
                            + String.join("\n", rows));
        }
    }

    private static List<FoldState> foldStates(List<StripEntry> log) {
        return log.stream().map(StripEntry::foldState).toList();
    }

    private static int occurrences(List<String> rows, String needle) {
        return (int) rows.stream().filter(row -> row.contains(needle)).count();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** The renderer with a manual frame pump: one pump is one frame, so commits are deterministic. */
    private static final class Rig implements AutoCloseable {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final PipedOutputStream keys;
        private final TracingTerminal terminal;
        final ManualFramePump pump = new ManualFramePump();
        final InlineRenderer renderer;

        Rig() throws Exception {
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("steering", "xterm-256color", in, sink);
            terminal.setSize(new Size(WIDTH, 24));
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8),
                    pump, new ManualFrameTicker(), new ManualClock());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        }

        /** The scenario: the model streams, the user reacts mid-stream, the answer goes on. */
        List<StripEntry> steeredTurn() {
            renderer.beginTurn();
            var answer = renderer.beginAnswer();
            answer.append(BEFORE + "\n\n");
            pump.pump();                       // the first paragraph reaches [[冻结回卷]]
            renderer.printSteeringPrompt(STEER);
            pump.pump();
            answer.append(AFTER + "\n\n");
            answer.finish();
            pump.pump();
            renderer.endTurn();
            pump.pump();
            return renderer.retainedItems();
        }

        /** The control: the same answer, the same frames, nobody interrupting. */
        List<StripEntry> uninterruptedTurn() {
            renderer.beginTurn();
            var answer = renderer.beginAnswer();
            answer.append(BEFORE + "\n\n");
            pump.pump();
            pump.pump();
            answer.append(AFTER + "\n\n");
            answer.finish();
            pump.pump();
            renderer.endTurn();
            pump.pump();
            return renderer.retainedItems();
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

    /**
     * The log without the correction, identified by what it renders rather than by its type — the
     * assertion should not care how the log happens to wrap its positions.
     */
    private static List<StripEntry> withoutTheCorrection(List<StripEntry> log) {
        List<StripEntry> kept = new ArrayList<>();
        for (StripEntry item : log) {
            if (item.renderRetained(WIDTH).stream().noneMatch(row -> row.toString().contains(STEER))) {
                kept.add(item);
            }
        }
        assertEquals(log.size() - 1, kept.size(),
                "the correction must occupy exactly one position in the log");
        return kept;
    }

    /** Exactly what a [[destructive resize reset]] would repaint for this log. */
    private static List<String> replayRows(List<StripEntry> log) {
        return ReplayWindow.select(log, WIDTH, ReplayWindow.budgetRows(24)).rows().stream()
                .map(AttributedString::toString)
                .map(String::stripTrailing)
                .toList();
    }

    private static int rowIndex(List<String> rows, String needle) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("missing row containing '" + needle + "':\n"
                + String.join("\n", rows));
    }

    /** What the [[转写浏览层]] puts on the alternate screen for this log. */
    private static String overlayText(List<StripEntry> log) throws Exception {
        TerminalFixture fixture = overlayTerminal();
        new TranscriptOverlay(fixture.terminal).open(log);
        fixture.writer.flush();
        return fixture.sink.toString(StandardCharsets.UTF_8);
    }

    /** The same, with the footer chrome dropped so two logs can be compared row for row. */
    private static List<String> overlayBody(List<StripEntry> log) throws Exception {
        List<String> body = new ArrayList<>();
        for (String line : overlayText(log).split("\n")) {
            String plain = line.replaceAll("\\[[0-9;?]*[a-zA-Z]", "").stripTrailing();
            if (plain.contains("wheel/j/k") || plain.contains("Ctrl+T thinking")) {
                continue;
            }
            body.add(plain);
        }
        return body;
    }

    private record TerminalFixture(Terminal terminal, ByteArrayOutputStream sink, PrintWriter writer) {
    }

    /** A terminal whose reader immediately says {@code q}: the overlay paints once and leaves. */
    private static TerminalFixture overlayTerminal() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Size size = new Size(WIDTH, 24);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getWidth()).thenReturn(WIDTH);
        Mockito.when(terminal.getHeight()).thenReturn(24);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read(Mockito.anyLong())).thenReturn((int) 'q');
        Mockito.when(reader.read()).thenReturn((int) 'q');
        Mockito.when(terminal.reader()).thenReturn(reader);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        return new TerminalFixture(terminal, sink, writer);
    }
}
