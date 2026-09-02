package com.cafecli.render.inline.replay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dev-only forensic replay: attribute every screen row that leaves for <b>permanent scrollback</b> to
 * the exact recorded event that pushed it there.
 *
 * <p>Why this exists. The animated-fullscreen defect is counted after the fact ("N spinner copies in
 * Terminal.app history"), which says nothing about <em>when</em> or <em>how</em> a live-region row
 * became unerasable. A row can only leave the screen two ways, and the two imply completely different
 * fixes:
 *
 * <ul>
 *   <li>{@link Kind#RESIZE_SHRINK} — a height reduction dropped rows off the top. The renderer never
 *       had a chance to erase them; the region was simply too high up when the window got shorter.</li>
 *   <li>{@link Kind#WRITE_SCROLL} — output written below the row scrolled it off the top. The renderer
 *       drew somewhere lower than the old region, i.e. its erase arithmetic missed.</li>
 * </ul>
 *
 * <p>{@link Escape#markersOnScreenBefore} is the second discriminator: two live-region marker rows on
 * screen at the moment of the escape means a stale copy was already sitting above the current region
 * (an erase miss), while one means the single live region itself scrolled away before being redrawn.
 *
 * <p>This is a diagnostic, not an oracle. It inherits exactly the fidelity of {@link
 * TerminalScreenModel}: calibrated against six exact Terminal.app conformance fixtures, and within one
 * copy of a real Terminal.app playback of the same fixed byte stream. Conclusions drawn here are about
 * the modelled screen and must say so.
 *
 * <pre>{@code
 * java -cp target/test-classes:target/cafecli-1.0.0.jar \
 *   com.cafecli.render.inline.replay.ReplayForensics trace.jsonl
 * }</pre>
 */
public final class ReplayForensics {

    /** Live-region text that must never reach scrollback; mirrors the assertions in resize-storm.sh. */
    public static final List<String> LIVE_REGION_MARKERS =
            List.of("esc to cancel", "Ctrl+Y to enable HITL");

    private ReplayForensics() {
    }

    /** How a row left the screen. */
    public enum Kind {
        /** A height reduction dropped it off the top of the screen. */
        RESIZE_SHRINK,
        /** Output below it scrolled it off the top of the screen. */
        WRITE_SCROLL
    }

    /** One physical row's departure into permanent scrollback. */
    public record Escape(int eventIndex,
                         long sequence,
                         long elapsedNanos,
                         Kind kind,
                         int columnsBefore,
                         int rowsBefore,
                         int columnsAfter,
                         int rowsAfter,
                         int cursorRowBefore,
                         int markersOnScreenBefore,
                         int markersOnScreenAfter,
                         boolean liveRegionRow,
                         String rowText,
                         String eventDetail) {
    }

    /**
     * One event that changed how many live-region marker rows are visible <b>on screen</b>.
     *
     * <p>This is where copies are actually born. An escape into scrollback only records that an
     * already-stale row finally left the screen; a mint records the frame that failed to erase its
     * predecessor. Going from k to k+1 markers with k &gt;= 1 means one extra stale live region is now
     * sitting on the screen.
     */
    public record Mint(int eventIndex,
                       long sequence,
                       long elapsedNanos,
                       Kind kind,
                       int columnsBefore,
                       int rowsBefore,
                       int columnsAfter,
                       int rowsAfter,
                       int cursorRowBefore,
                       int cursorColumnBefore,
                       int cursorRowAfter,
                       int markersBefore,
                       int markersAfter,
                       List<Integer> markerRowsBefore,
                       List<Integer> markerRowsAfter,
                       String eventDetail,
                       String previousEventDetail) {
    }

    /** Full forensic result for one trace. */
    public record Report(List<Escape> escapes,
                         List<Mint> markerChanges,
                         int writeEvents,
                         int resizeEvents,
                         String historyText,
                         List<String> finalDocument) {

        /** Events that added a stale live region to the screen (the first draw is not a copy). */
        public List<Mint> copyMints() {
            return markerChanges.stream()
                    .filter(change -> change.markersAfter() > change.markersBefore()
                            && change.markersBefore() >= 1)
                    .toList();
        }

        /** Events that removed live-region rows from the screen, i.e. an erase that worked. */
        public List<Mint> erasures() {
            return markerChanges.stream()
                    .filter(change -> change.markersAfter() < change.markersBefore())
                    .toList();
        }

        /** Escapes that carried a live-region marker row into permanent scrollback. */
        public List<Escape> liveRegionEscapes() {
            return escapes.stream().filter(Escape::liveRegionRow).toList();
        }

        /** Marker rows surviving in the final modelled history — the number resize-storm.sh greps for. */
        public Map<String, Integer> markerCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String marker : LIVE_REGION_MARKERS) {
                counts.put(marker, (int) historyText.lines().filter(line -> line.contains(marker)).count());
            }
            return counts;
        }
    }

    public static Report analyze(ReplayTrace trace) {
        return analyze(trace, LIVE_REGION_MARKERS);
    }

    public static Report analyze(ReplayTrace trace, List<String> markers) {
        Objects.requireNonNull(trace, "trace").requireComplete();
        ReplayTrace.Header header = trace.header();
        TerminalScreenModel terminal =
                new TerminalScreenModel(header.initialColumns(), header.initialRows());

        List<Escape> escapes = new ArrayList<>();
        List<Mint> markerChanges = new ArrayList<>();
        int scrollbackRows = 0;
        int writes = 0;
        int resizes = 0;
        String previousDetail = "";
        List<ReplayTrace.Event> events = trace.events();
        for (int index = 0; index < events.size(); index++) {
            ReplayTrace.Event event = events.get(index);
            int columnsBefore = terminal.columns();
            int rowsBefore = terminal.screenRows();
            int cursorRowBefore = terminal.cursorRow();
            int cursorColumnBefore = terminal.cursorColumn();
            List<Integer> markerRowsBefore = markerRowIndexes(terminal.screen(), markers);
            int markersBefore = markerRowsBefore.size();

            Kind kind;
            String detail;
            if (event instanceof ReplayTrace.WriteEvent write) {
                writes++;
                kind = Kind.WRITE_SCROLL;
                detail = describe(write.bytes());
                terminal.feed(lineFeedAdjusted(write.bytes(), header.lineFeedMode()));
            } else if (event instanceof ReplayTrace.ResizeEvent resize) {
                resizes++;
                kind = Kind.RESIZE_SHRINK;
                detail = resize.columns() + "x" + resize.rows() + " (generation " + resize.generation() + ")";
                terminal.resize(resize.columns(), resize.rows());
            } else {
                throw new IllegalStateException("Unknown replay event: " + event);
            }

            List<Integer> markerRowsAfter = markerRowIndexes(terminal.screen(), markers);
            int markersAfter = markerRowsAfter.size();
            if (markersAfter != markersBefore) {
                markerChanges.add(new Mint(index, event.sequence(), event.elapsedNanos(), kind,
                        columnsBefore, rowsBefore, terminal.columns(), terminal.screenRows(),
                        cursorRowBefore, cursorColumnBefore, terminal.cursorRow(),
                        markersBefore, markersAfter, markerRowsBefore, markerRowsAfter,
                        detail, previousDetail));
            }
            previousDetail = detail;

            List<String> scrollback = terminal.scrollback();
            if (scrollback.size() > scrollbackRows) {
                for (String row : scrollback.subList(scrollbackRows, scrollback.size())) {
                    escapes.add(new Escape(index,
                            event.sequence(),
                            event.elapsedNanos(),
                            kind,
                            columnsBefore,
                            rowsBefore,
                            terminal.columns(),
                            terminal.screenRows(),
                            cursorRowBefore,
                            markersBefore,
                            markersAfter,
                            containsMarker(row, markers),
                            row,
                            detail));
                }
            }
            // A height increase pulls rows back out of scrollback, so this tracks in both directions.
            scrollbackRows = scrollback.size();
        }

        return new Report(List.copyOf(escapes), List.copyOf(markerChanges), writes, resizes,
                terminal.historyText(), terminal.document());
    }

    private static int markerRows(List<String> rows, List<String> markers) {
        return markerRowIndexes(rows, markers).size();
    }

    private static List<Integer> markerRowIndexes(List<String> rows, List<String> markers) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (containsMarker(rows.get(i), markers)) {
                indexes.add(i);
            }
        }
        return List.copyOf(indexes);
    }

    private static boolean containsMarker(String row, List<String> markers) {
        return markers.stream().anyMatch(row::contains);
    }

    private static byte[] lineFeedAdjusted(byte[] bytes, ReplayTrace.LineFeedMode mode) {
        if (mode == ReplayTrace.LineFeedMode.LF) {
            return bytes;
        }
        int lineFeeds = 0;
        for (byte value : bytes) {
            if (value == '\n') {
                lineFeeds++;
            }
        }
        byte[] translated = new byte[bytes.length + lineFeeds];
        int target = 0;
        for (byte value : bytes) {
            if (value == '\n') {
                translated[target++] = '\r';
            }
            translated[target++] = value;
        }
        return translated;
    }

    /**
     * Compact human-readable rendering of a recorded write: control sequences stay literal, printable
     * runs are collapsed so a whole-frame repaint stays one readable line.
     */
    static String describe(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (c == 0x1b) {
                flushRun(run, out);
                int end = i + 1;
                if (end < text.length() && text.charAt(end) == '[') {
                    end++;
                    while (end < text.length() && text.charAt(end) >= 0x20 && text.charAt(end) <= 0x3f) {
                        end++;
                    }
                    if (end < text.length()) {
                        end++;
                    }
                } else if (end < text.length()) {
                    end++;
                }
                out.append("ESC").append(text, i + 1, end);
                i = end;
            } else if (c == '\r') {
                flushRun(run, out);
                out.append("<CR>");
                i++;
            } else if (c == '\n') {
                flushRun(run, out);
                out.append("<LF>");
                i++;
            } else {
                run.append(c);
                i++;
            }
        }
        flushRun(run, out);
        return out.toString();
    }

    private static void flushRun(StringBuilder run, StringBuilder out) {
        if (run.isEmpty()) {
            return;
        }
        String value = run.toString();
        out.append('"')
                .append(value.length() <= 24 ? value : value.substring(0, 24) + "…+" + (value.length() - 24))
                .append('"');
        run.setLength(0);
    }

    /** Replay up to (and optionally through) one event and print the modelled screen around it. */
    static void dumpScreenAt(ReplayTrace trace, int eventIndex) {
        ReplayTrace.Header header = trace.header();
        TerminalScreenModel terminal =
                new TerminalScreenModel(header.initialColumns(), header.initialRows());
        List<ReplayTrace.Event> events = trace.events();
        for (int index = 0; index <= eventIndex && index < events.size(); index++) {
            if (index == eventIndex) {
                System.out.println("--- screen BEFORE event #" + index + " (" + terminal.columns()
                        + "x" + terminal.screenRows() + ", cursor row " + terminal.cursorRow()
                        + " col " + terminal.cursorColumn() + ") ---");
                printScreen(terminal);
                if (events.get(index) instanceof ReplayTrace.WriteEvent write) {
                    System.out.println("--- write bytes ---");
                    System.out.println(describe(write.bytes()));
                }
            }
            apply(terminal, events.get(index), header.lineFeedMode());
        }
        System.out.println("--- screen AFTER event #" + eventIndex + " (" + terminal.columns()
                + "x" + terminal.screenRows() + ", cursor row " + terminal.cursorRow()
                + " col " + terminal.cursorColumn() + ") ---");
        printScreen(terminal);
    }

    /** Per-event cursor trail: the cheapest way to see which frame leaves the cursor where. */
    static void trail(ReplayTrace trace, int from, int to) {
        ReplayTrace.Header header = trace.header();
        TerminalScreenModel terminal =
                new TerminalScreenModel(header.initialColumns(), header.initialRows());
        List<ReplayTrace.Event> events = trace.events();
        for (int index = 0; index < events.size() && index <= to; index++) {
            ReplayTrace.Event event = events.get(index);
            int beforeRow = terminal.cursorRow();
            int beforeColumn = terminal.cursorColumn();
            int beforeMarkers = markerRows(terminal.screen(), LIVE_REGION_MARKERS);
            apply(terminal, event, header.lineFeedMode());
            if (index < from) {
                continue;
            }
            String detail = event instanceof ReplayTrace.WriteEvent write
                    ? describe(write.bytes())
                    : "RESIZE " + terminal.columns() + "x" + terminal.screenRows();
            System.out.printf("#%-5d %3dx%-3d cursor (%d,%d)->(%d,%d) markers %d->%d  %s%n",
                    index, terminal.columns(), terminal.screenRows(),
                    beforeRow, beforeColumn, terminal.cursorRow(), terminal.cursorColumn(),
                    beforeMarkers, markerRows(terminal.screen(), LIVE_REGION_MARKERS),
                    truncate(detail, 150));
        }
    }

    private static void printScreen(TerminalScreenModel terminal) {
        List<String> rows = terminal.screen();
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            System.out.printf("  %3d %s %s%n", i,
                    containsMarker(row, LIVE_REGION_MARKERS) ? "*" : " ",
                    truncate(row.stripTrailing(), 100));
        }
    }

    private static void apply(TerminalScreenModel terminal,
                              ReplayTrace.Event event,
                              ReplayTrace.LineFeedMode mode) {
        if (event instanceof ReplayTrace.WriteEvent write) {
            terminal.feed(lineFeedAdjusted(write.bytes(), mode));
        } else if (event instanceof ReplayTrace.ResizeEvent resize) {
            terminal.resize(resize.columns(), resize.rows());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ReplayForensics <trace.jsonl> [--all | --screen=<eventIndex>]");
            System.exit(2);
        }
        for (String arg : args) {
            if (arg.startsWith("--trail=")) {
                String[] bounds = arg.substring("--trail=".length()).split(":");
                trail(ReplayTrace.read(Path.of(args[0])),
                        Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
                return;
            }
            if (arg.startsWith("--screen=")) {
                dumpScreenAt(ReplayTrace.read(Path.of(args[0])),
                        Integer.parseInt(arg.substring("--screen=".length())));
                return;
            }
        }
        boolean all = args.length > 1 && args[1].equals("--all");
        Report report = analyze(ReplayTrace.read(Path.of(args[0])));

        System.out.println("events: " + report.writeEvents() + " writes, "
                + report.resizeEvents() + " resizes");
        System.out.println("final modelled marker rows: " + report.markerCounts());
        System.out.println("rows pushed to permanent scrollback: " + report.escapes().size()
                + " (live-region rows: " + report.liveRegionEscapes().size() + ")");

        Map<Kind, Integer> byKind = new LinkedHashMap<>();
        for (Escape escape : report.liveRegionEscapes()) {
            byKind.merge(escape.kind(), 1, Integer::sum);
        }
        System.out.println("live-region escapes by cause: " + byKind);

        Map<String, Integer> byShape = new LinkedHashMap<>();
        for (Escape escape : report.liveRegionEscapes()) {
            byShape.merge(escape.kind() + " markersOnScreenBefore=" + escape.markersOnScreenBefore(),
                    1, Integer::sum);
        }
        System.out.println("live-region escapes by shape: " + byShape);
        System.out.println("on-screen copies minted: " + report.copyMints().size()
                + "; erasures: " + report.erasures().size());

        Map<Kind, Integer> mintsByKind = new LinkedHashMap<>();
        for (Mint mint : report.copyMints()) {
            mintsByKind.merge(mint.kind(), 1, Integer::sum);
        }
        System.out.println("copy mints by cause: " + mintsByKind);

        System.out.println();
        System.out.println("=== every change to the on-screen live-region row count ===");
        for (Mint change : report.markerChanges()) {
            System.out.printf("#%d seq=%d t=%.3fms %s %dx%d->%dx%d cursor(%d,%d)->row %d markers %d->%d%n",
                    change.eventIndex(), change.sequence(), change.elapsedNanos() / 1_000_000.0,
                    change.kind(), change.columnsBefore(), change.rowsBefore(),
                    change.columnsAfter(), change.rowsAfter(),
                    change.cursorRowBefore(), change.cursorColumnBefore(), change.cursorRowAfter(),
                    change.markersBefore(), change.markersAfter());
            System.out.println("    rows  : " + change.markerRowsBefore()
                    + " -> " + change.markerRowsAfter());
            System.out.println("    prev  : " + truncate(change.previousEventDetail(), 300));
            System.out.println("    event : " + truncate(change.eventDetail(), 300));
        }

        System.out.println();
        System.out.println("=== rows that reached permanent scrollback ===");
        List<Escape> shown = all ? report.escapes() : report.liveRegionEscapes();
        for (Escape escape : shown) {
            System.out.printf("#%d seq=%d t=%.3fms %s %dx%d->%dx%d cursorRow=%d markers %d->%d%n",
                    escape.eventIndex(), escape.sequence(), escape.elapsedNanos() / 1_000_000.0,
                    escape.kind(), escape.columnsBefore(), escape.rowsBefore(),
                    escape.columnsAfter(), escape.rowsAfter(), escape.cursorRowBefore(),
                    escape.markersOnScreenBefore(), escape.markersOnScreenAfter());
            System.out.println("    event : " + truncate(escape.eventDetail(), 220));
            System.out.println("    row   : " + truncate(escape.rowText().strip(), 160));
        }

        if (args.length > 1 && args[1].equals("--history")) {
            Files.writeString(Path.of(args[0] + ".model-history.txt"), report.historyText(),
                    StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…+" + (value.length() - limit);
    }
}
