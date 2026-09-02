package com.cafecli.render.inline.replay;

import com.cafecli.render.inline.TerminalCapabilities;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dev-only producer for the small Terminal.app conformance fixtures used by the replay oracle.
 * The companion capture script owns the real window, stops this process at the exact history
 * boundary, and terminates it only after AppleScript has persisted that history.
 */
public final class TerminalConformanceDriver {

    public static final String ANCHOR = "[cafecli-conf:v1]";
    public static final int INITIAL_COLUMNS = 24;
    public static final int INITIAL_ROWS = 8;

    private final Terminal terminal;
    private final TerminalReplayRecorder recorder;
    private final AtomicLong resizeGeneration = new AtomicLong();
    private final Object resizeMonitor = new Object();

    private TerminalConformanceDriver(Terminal terminal, TerminalReplayRecorder recorder) {
        this.terminal = terminal;
        this.recorder = recorder;
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            long generation = resizeGeneration.incrementAndGet();
            recorder.recordResizeSignal(generation);
            Size observed = TerminalCapabilities.safeSize(terminal);
            recorder.observeResize(generation, observed.getRows(), observed.getColumns());
            synchronized (resizeMonitor) {
                resizeMonitor.notifyAll();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        try (Terminal terminal = TerminalCapabilities.systemTerminalBuilder().build();
             TerminalReplayRecorder recorder = TerminalReplayRecorder.create(options.trace(), terminal)) {
            TerminalConformanceDriver driver = new TerminalConformanceDriver(terminal, recorder);
            driver.prepareAnchoredEmptyScreen(options.fixtureCase(), options.captureId());
            driver.run(options.fixtureCase(), options.captureId());
            writeReadyFile(options.ready());
            new CountDownLatch(1).await();
        }
    }

    private void prepareAnchoredEmptyScreen(FixtureCase fixtureCase, String captureId) {
        write("\u001b[3J\u001b[2J\u001b[H");
        write(ANCHOR + " " + fixtureCase.id() + " " + captureId + "\n");
        write("\n".repeat(INITIAL_ROWS));
        write("\u001b[H");
    }

    private void run(FixtureCase fixtureCase, String captureId) throws InterruptedException {
        switch (fixtureCase) {
            case HARD_SOFT_WRAP -> {
                write("soft:ABCDEFGHIJKLMNOPQ\n");
                write("hard:ABCD\nhard:EFGH");
                resize(8, 20);
            }
            case EXACT_MULTIPLE_CURSOR -> {
                resize(6, 24);
                write("\u001b[H1234567890123456789012345678901234567890");
                resize(6, 20);
                write("\u001b[2KX");
            }
            case CJK_RIGHT_EDGE -> {
                write("\u001b[H1234567890123456789界Z");
                // Grow by one row while narrowing so both reflowed rows stay on screen. A blind
                // cell chunker leaves 界 on row 1; glyph-aware reflow moves 界Z together to row 2.
                resize(9, 20);
                write("\u001b[2;1H\u001b[2KX\u001b[3;1HEND");
            }
            case CLEAR_TO_END_OF_SCREEN -> {
                resize(6, 24);
                write("\u001b[HKEEP-0\nKEEP-1\nERASE-2\nERASE-3");
                write("\u001b[2;4H\u001b[JX");
            }
            case HEIGHT_SHRINK_SCROLLBACK -> {
                write("\u001b[HH0\nH1\nH2\nH3\nH4\nH5\nH6\nH7");
                resize(4, 24);
                write("\u001b[H\u001b[JVISIBLE");
            }
            case CURSOR_DOWN_CLAMP -> {
                resize(6, 24);
                write("\u001b[HTOP\u001b[2;1HSTART\u001b[999BBOTTOM");
            }
            case SAVED_CURSOR_REFLOW -> {
                // Does DECSC/DECRC follow its content through a width reflow, or is the saved
                // position a stale (row, column) pair? Set up wide, then narrow: the narrowing
                // pushes the save point down by one physical row.
                resize(INITIAL_ROWS, 40);
                // Re-blank the screen after the widen so the measured reflow is the narrowing.
                write("\u001b[H" + "\n".repeat(2 * INITIAL_ROWS) + "\u001b[H");
                // 30 columns: one physical row at width 40, two physical rows at width 24.
                write("WRAP:abcdefghijklmnopqrstuvwxy");
                write("\u001b[2;1HSAVEHERE");
                write("\u001b[2;15H\u001b7");
                write("\u001b[3;1HROW3MARK");
                resize(INITIAL_ROWS, INITIAL_COLUMNS);
                // Follows content -> X joins the SAVEHERE line at column 15.
                // Stale coordinates -> X lands on physical row 2, the WRAP continuation row.
                write("\u001b8X");
                // Absolute-row probe: shows where the reflowed content actually sits.
                write("\u001b[5;1HEND5");
            }
            case SAVED_CURSOR_REFLOW_SHIFT -> {
                // saved-cursor-reflow was inconclusive: its wrapping line sat ABOVE the save
                // point, and Terminal.app anchors the screen to the buffer bottom, so the save
                // point kept the same physical row. Here the wrapping line sits BELOW the save
                // point, so the narrowing pushes the save point up one physical row.
                resize(INITIAL_ROWS, 40);
                write("\u001b[H" + "\n".repeat(2 * INITIAL_ROWS) + "\u001b[H");
                write("AAAAAAAA");
                write("\u001b[2;1HBBBBBBBB");
                write("\u001b[3;1HSAVEHERE");
                write("\u001b[3;15H\u001b7");
                // 30 columns: one physical row at width 40, two physical rows at width 24.
                write("\u001b[4;1HWRAP:abcdefghijklmnopqrstuvwxy");
                write("\u001b[5;1HCCCCCCCC");
                resize(INITIAL_ROWS, INITIAL_COLUMNS);
                // Follows content -> X joins the SAVEHERE line at column 15.
                // Stale coordinates -> X replaces the WRAP line's 15th character.
                write("\u001b8X");
                // Absolute-row probes: pin which content ended up on rows 1, 2 and 8.
                write("\u001b[1;10HP1");
                write("\u001b[2;20HQ2");
                write("\u001b[8;1HP8");
            }
            case ERASE_SAVED_SCROLLBACK -> {
                // Does CSI 3J erase Terminal.app's saved scrollback, or is it ignored?
                // SBMARK-7Q3 is written first and then pushed 18 rows above the visible top by the
                // SB-FILL filler, so at erase time it lives only in saved scrollback: an erase
                // confined to the visible screen cannot reach it, and only a scrollback erase can
                // remove it.
                write("SBMARK-7Q3\n");
                for (int line = 0; line < 3 * INITIAL_ROWS; line++) {
                    write("SB-FILL-" + line + "\n");
                }
                write("\u001b[3J");
                // prepareAnchoredEmptyScreen scrolled the capture anchor into that same saved
                // scrollback, so an honored erase takes the anchor with it and the capture script
                // would have nothing to slice. Re-emit the exact anchor after the erase: the
                // capture then succeeds under both hypotheses and the marker alone discriminates.
                // The POST filler pushes the re-emitted anchor fully into scrollback so its four
                // wrapped rows rejoin into one history line, exactly like the prepared anchor.
                write(ANCHOR + " " + fixtureCase.id() + " " + captureId + "\n");
                for (int line = 0; line < 2 * INITIAL_ROWS; line++) {
                    write("POST-" + line + "\n");
                }
            }
            case ERASE_SCREEN_PUSHES_SCROLLBACK -> {
                // Does CSI 2J blank the visible rows, or does it push them into Terminal.app's saved
                // scrollback first? This is the fact `clear(1)` exposes — the pre-clear screen stays
                // scrollable afterwards — and it decides the order of a [[destructive resize reset]]'s
                // erase: a scrollback erase written *before* the screen erase is undone by it.
                //
                // The prepared anchor is already in saved scrollback and CSI 2J cannot reach it, so
                // nothing needs re-emitting here. VISROW markers occupy the visible screen alone at
                // erase time; if they reappear in history the erase deposited them.
                write("\u001b[H");
                for (int line = 0; line < INITIAL_ROWS - 3; line++) {
                    write("VISROW-" + line + "\n");
                }
                write("\u001b[2J");
                write("\u001b[HAFTER-SCREEN-ERASE\n");
            }
            case RESET_ERASE_LEAVES_NOTHING -> {
                // The production erase, exactly as AnsiSeq.ERASE_SCREEN_AND_SAVED_SCROLLBACK writes
                // it: whatever CSI 2J deposits, the CSI 3J behind it must take away again. Nothing
                // written before it may be reachable afterwards — not the visible screen, not the
                // saved scrollback.
                write("\u001b[H");
                for (int line = 0; line < INITIAL_ROWS - 3; line++) {
                    write("VISROW-" + line + "\n");
                }
                // Saved scrollback as well as the visible screen: SBROW is pushed above the top.
                write("SBROW-DEEP\n");
                for (int line = 0; line < 2 * INITIAL_ROWS; line++) {
                    write("SB-FILL-" + line + "\n");
                }
                write(com.cafecli.render.inline.AnsiSeq.ERASE_SCREEN_AND_SAVED_SCROLLBACK);
                // An honoured erase takes the prepared capture anchor with it, so the capture script
                // would have nothing to slice; re-emit it exactly, as erase-saved-scrollback does.
                write(ANCHOR + " " + fixtureCase.id() + " " + captureId + "\n");
                for (int line = 0; line < 2 * INITIAL_ROWS; line++) {
                    write("POST-" + line + "\n");
                }
            }
        }
    }

    private void resize(int rows, int columns) throws InterruptedException {
        long before = resizeGeneration.get();
        write("\u001b[8;" + rows + ";" + columns + "t");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (resizeMonitor) {
            while (resizeGeneration.get() <= before && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.timedWait(resizeMonitor, 50);
            }
        }
        Size observed = TerminalCapabilities.safeSize(terminal);
        if (resizeGeneration.get() <= before
                || observed.getRows() != rows
                || observed.getColumns() != columns) {
            throw new IllegalStateException("Terminal resize did not settle at " + rows + "x" + columns
                    + " (generation=" + resizeGeneration.get() + ", observed="
                    + observed.getRows() + "x" + observed.getColumns() + ")");
        }
    }

    private void write(String text) {
        recorder.recordWrite(text);
        PrintWriter writer = terminal.writer();
        writer.print(text);
        writer.flush();
        terminal.flush();
    }

    private static void writeReadyFile(Path ready) throws IOException {
        Path parent = ready.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(ready, "ready\n", StandardCharsets.UTF_8);
    }

    enum FixtureCase {
        HARD_SOFT_WRAP("hard-soft-wrap"),
        EXACT_MULTIPLE_CURSOR("exact-multiple-cursor"),
        CJK_RIGHT_EDGE("cjk-right-edge"),
        CLEAR_TO_END_OF_SCREEN("clear-to-end-of-screen"),
        HEIGHT_SHRINK_SCROLLBACK("height-shrink-scrollback"),
        CURSOR_DOWN_CLAMP("cursor-down-clamp"),
        SAVED_CURSOR_REFLOW("saved-cursor-reflow"),
        SAVED_CURSOR_REFLOW_SHIFT("saved-cursor-reflow-shift"),
        ERASE_SAVED_SCROLLBACK("erase-saved-scrollback"),
        ERASE_SCREEN_PUSHES_SCROLLBACK("erase-screen-pushes-scrollback"),
        RESET_ERASE_LEAVES_NOTHING("reset-erase-leaves-nothing");

        private final String id;

        FixtureCase(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static FixtureCase parse(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (FixtureCase fixtureCase : values()) {
                if (fixtureCase.id.equals(normalized)) {
                    return fixtureCase;
                }
            }
            throw new IllegalArgumentException("Unknown conformance case: " + value);
        }
    }

    private record Options(FixtureCase fixtureCase, Path trace, Path ready, String captureId) {
        private static Options parse(String[] args) {
            String caseValue = null;
            Path trace = null;
            Path ready = null;
            String captureId = null;
            for (String argument : args) {
                if (argument.startsWith("--case=")) {
                    caseValue = argument.substring("--case=".length());
                } else if (argument.startsWith("--trace=")) {
                    trace = Path.of(argument.substring("--trace=".length())).toAbsolutePath().normalize();
                } else if (argument.startsWith("--ready=")) {
                    ready = Path.of(argument.substring("--ready=".length())).toAbsolutePath().normalize();
                } else if (argument.startsWith("--capture-id=")) {
                    captureId = argument.substring("--capture-id=".length());
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            if (caseValue == null || trace == null || ready == null
                    || captureId == null || captureId.isBlank()
                    || captureId.indexOf('\n') >= 0 || captureId.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                        "--case, --trace, --ready, and a single-line --capture-id are required");
            }
            return new Options(FixtureCase.parse(caseValue), trace, ready, captureId);
        }
    }
}
