package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dev-only harness for the [[转写浏览层]]'s cost criteria (ADR 0010 amendment A10): opening the overlay
 * and scrolling it must not get more expensive as the session gets longer.
 *
 * <p>It is not a test. A wall-clock assertion would be flaky and a functional assertion cannot see the
 * difference at all — the old whole-document layout produced exactly the same rows — so the regression
 * gate is {@link OverlayDocumentTest}'s layout counters and this is the measurement behind the
 * numbers recorded in the decision record.
 *
 * <pre>
 * mvn -o test-compile -DskipTests=false
 * java -cp target/test-classes:target/classes:$(ls target/*.jar | head -1) \
 *      com.cafecli.render.inline.OverlayLayoutCost
 * </pre>
 *
 * <p>Method: a synthetic session of {@code N} committed items — a submitted prompt, a 24-line code
 * block and a 6-line reasoning block per turn, which is the shape a real turn commits — laid out at
 * 118 columns with a 43-row page. Each figure is the median of its repetitions on a fresh document
 * (so no measured height survives from the previous repetition), after a warm-up pass.
 */
final class OverlayLayoutCost {

    private static final int WIDTH = 118;
    private static final int PAGE = 43;

    private OverlayLayoutCost() {
    }

    public static void main(String[] args) throws Exception {
        Terminal terminal = new ExternalTerminal("overlay-cost", "xterm-256color",
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
        terminal.setSize(new Size(WIDTH + 2, PAGE + 1));

        // Warm-up: let the JIT see every path before anything is recorded.
        for (int pass = 0; pass < 3; pass++) {
            measureOpen(session(terminal, 20), 5);
            measureScroll(session(terminal, 20), 50);
            measureJumpToEnd(session(terminal, 20), 5);
        }

        System.out.printf("%-8s %-7s %-11s %-15s %-19s %-17s %-17s %-16s %-16s %-18s %-18s%n",
                "turns", "items", "open (µs)", "open tail (µs)", "per keystroke (µs)",
                "1st jump G (µs)", "2nd jump G (µs)", "poll idle (µs)", "poll +1 (µs)",
                "stream tail (µs)", "stream away (µs)");
        for (int turns : new int[]{5, 50, 500, 2_000}) {
            List<StripEntry> log = session(terminal, turns);
            long open = measureOpen(log, 21);
            long openTail = measureOpenAtTail(log, 21);
            long scroll = measureScroll(log, 401);
            long jump = measureJumpToEnd(log, 5);
            long again = measureSecondJumpToEnd(log, 5);
            long pollIdle = measurePoll(log, 401, false);
            long pollGrowing = measurePoll(log, 401, true);
            long streamTail = measureStreamingPoll(terminal, log, 401, true);
            long streamAway = measureStreamingPoll(terminal, log, 401, false);
            System.out.printf("%-8d %-7d %-11d %-15d %-19d %-17d %-17d %-16d %-16d %-18d %-18d%n",
                    turns, log.size(), open / 1_000, openTail / 1_000, scroll / 1_000,
                    jump / 1_000, again / 1_000, pollIdle / 1_000, pollGrowing / 1_000,
                    streamTail / 1_000, streamAway / 1_000);
        }
        terminal.close();
    }

    /** What opening costs: index the log, lay out the first page and a page of slack. */
    private static long measureOpen(List<StripEntry> log, int repetitions) {
        List<Long> samples = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long start = System.nanoTime();
            OverlayDocument document = documentOver(log);
            document.ensureRows(PAGE * 2);
            document.viewport(0, PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /**
     * What opening <b>at the tail</b> costs (ADR 0010 amendment A13): the document has to be measured
     * whole, because the last page cannot be named without knowing the height.
     */
    private static long measureOpenAtTail(List<StripEntry> log, int repetitions) {
        List<Long> samples = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long start = System.nanoTime();
            OverlayDocument document = documentOver(log);
            int total = document.totalRows();
            document.viewport(Math.max(0, total - PAGE), PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /**
     * What one live-follow poll costs on an open overlay pinned at the tail: re-read the log, absorb
     * whatever arrived, re-measure and repaint the viewport. {@code growing} appends one committed item
     * per poll, which is faster than a real session commits them.
     */
    private static long measurePoll(List<StripEntry> log, int polls, boolean growing) {
        List<StripEntry> live = new ArrayList<>(log);
        OverlayDocument document = new OverlayDocument(live);
        document.layout(WIDTH, Collections.nCopies(live.size() + polls, FoldState.EXPANDED), false);
        int total = document.totalRows();
        document.viewport(Math.max(0, total - PAGE), PAGE);
        StripEntry template = log.get(log.size() - 1);
        List<Long> samples = new ArrayList<>(polls);
        for (int poll = 0; poll < polls; poll++) {
            if (growing) {
                live.add(template);
            }
            long start = System.nanoTime();
            document.refresh(List.copyOf(live));
            int height = document.totalRows();
            document.viewport(Math.max(0, height - PAGE), PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /**
     * What one 50 ms tick costs while an answer is <b>streaming</b> and the overlay is open: the
     * [[活区]]'s own drain of the newly completed line plus one live-follow poll of the same answer.
     *
     * <p>This is the case ADR 0016 D1 prices. Both views normally run at the terminal width, so before
     * the split they shared one {@code MarkdownProjection} and the second view's layout was a cache
     * hit; after it, {@code TerminalMarkdownRenderer} runs once per view. {@code atTail} chooses
     * whether the reader is watching the answer arrive (it is in the overlay's working set) or has
     * scrolled away from it (it is measured on every poll and released again).
     */
    private static long measureStreamingPoll(Terminal terminal, List<StripEntry> log,
                                             int polls, boolean atTail) {
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        List<StripEntry> live = new ArrayList<>(log);
        live.add(answer);
        OverlayDocument document = new OverlayDocument(live);
        document.layout(WIDTH, Collections.nCopies(live.size(), FoldState.EXPANDED), false);
        int total = document.totalRows();
        int viewportStart = atTail ? Math.max(0, total - PAGE) : 0;
        document.viewport(viewportStart, PAGE);
        List<Long> samples = new ArrayList<>(polls);
        for (int poll = 0; poll < polls; poll++) {
            answer.append("streamed prose line " + poll
                    + " with enough words in it to fold at any sensible width.\n");
            long start = System.nanoTime();
            // The render thread's own frame work for this answer: commit whatever completed.
            answer.freezeTopRows(Integer.MAX_VALUE, WIDTH);
            // And the overlay's 50 ms poll over the very same answer.
            document.refresh(List.copyOf(live));
            int height = document.totalRows();
            document.viewport(atTail ? Math.max(0, height - PAGE) : viewportStart, PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /** What one arrow key costs, on a document already open and scrolled into. */
    private static long measureScroll(List<StripEntry> log, int keystrokes) {
        OverlayDocument document = documentOver(log);
        document.viewport(0, PAGE);
        List<Long> samples = new ArrayList<>(keystrokes);
        for (int key = 1; key <= keystrokes; key++) {
            long start = System.nanoTime();
            document.ensureRows(key + PAGE * 2);
            document.viewport(key, PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /** The one navigation that must know where the end is, and pays to find out once. */
    private static long measureJumpToEnd(List<StripEntry> log, int repetitions) {
        List<Long> samples = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            OverlayDocument document = documentOver(log);
            document.viewport(0, PAGE);
            long start = System.nanoTime();
            int total = document.totalRows();
            document.viewport(Math.max(0, total - PAGE), PAGE);
            samples.add(System.nanoTime() - start);
        }
        return median(samples);
    }

    /**
     * The same jump on a document that has already measured itself once — the case that matters, since
     * the heights are remembered for as long as the width and the fold mode hold.
     */
    private static long measureSecondJumpToEnd(List<StripEntry> log, int repetitions) {
        OverlayDocument document = documentOver(log);
        int total = document.totalRows();
        List<Long> samples = new ArrayList<>(repetitions);
        for (int repetition = 0; repetition < repetitions; repetition++) {
            document.viewport(0, PAGE);
            long start = System.nanoTime();
            document.viewport(Math.max(0, document.totalRows() - PAGE), PAGE);
            samples.add(System.nanoTime() - start);
        }
        if (total <= 0) {
            throw new IllegalStateException("empty document");
        }
        return median(samples);
    }

    private static OverlayDocument documentOver(List<StripEntry> log) {
        OverlayDocument document = new OverlayDocument(log);
        document.layout(WIDTH, Collections.nCopies(log.size(), FoldState.EXPANDED), false);
        return document;
    }

    /** One turn commits a prompt echo, a code block and a reasoning block. */
    private static List<StripEntry> session(Terminal terminal, int turns) {
        List<StripEntry> log = new ArrayList<>(turns * 3);
        for (int turn = 0; turn < turns; turn++) {
            log.add(SubmittedPromptItem.of(terminal, "explain what changed in turn " + turn));
            StringBuilder code = new StringBuilder();
            for (int line = 0; line < 24; line++) {
                code.append("    int value").append(line).append(" = compute(").append(turn)
                        .append(", ").append(line).append(");\n");
            }
            log.add(new CodeBlock(terminal, "java", code.toString()));
            log.add(new ReasoningBlock(terminal,
                    "weigh the options\nread the file\ncheck the test\nrun it\nread the output\ndecide\n"));
        }
        return log;
    }

    private static long median(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
