package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerSegmentTest {

    @Test
    void completedProseRowsHighlightPathAndFunctionEntities() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        answer.append("See `src/Agent.java:232` and executeTools().\n");
        answer.finish();

        AttributedString line = answer.renderRetained(120).getFirst();

        assertNotEquals(AttributedStyle.DEFAULT,
                line.styleAt(line.toString().indexOf("src/Agent")));
        assertNotEquals(line.styleAt(line.toString().indexOf("src/Agent")),
                line.styleAt(line.toString().indexOf("executeTools")));
    }

    /**
     * A markdown table is what makes the styled-line count width-dependent.
     *
     * <p><b>Measured 2026-08-19</b>, and it narrows A11's mechanism usefully: {@code
     * TerminalMarkdownRenderer} wraps only table <em>cells</em> to the width, while prose is folded
     * later, when a styled line is turned into physical rows. So the count of styled lines a given
     * source produces is width-independent for prose and width-dependent for a table, whose row height
     * is the tallest wrapped cell. A table in flight is therefore not one case among several — it is
     * the case.
     *
     * <p>This table produces <b>13</b> styled lines at width 40, 10 at 60, 9 at 80 and 7 at 120
     * (measured). Two columns would not do: two-column tables with long cells fall into the
     * width-independent key/value form.
     */
    private static final String TABLE = """
            | stage | what happens | why it matters |
            |---|---|---|
            | erase | the visible screen and saved scrollback both go | it is geometry independent |
            | redraw | the replay window comes back at the new width | it is the only wrong-able half |

            """;

    /**
     * The [[活区]] tail must keep flowing across a width change (ADR 0010 amendment A11).
     *
     * <p>The boundary dividing "already committed to [[冻结回卷]]" from "still live" has to be held in
     * <b>source</b> coordinates, because the same source at a different width yields a different number
     * of styled lines. A boundary carried as a count of styled lines and used at a wider width
     * over-counts, and everything the answer streams after the resize silently never appears: neither
     * committed nor drawn in the tail.
     */
    @Test
    void wideningMidAnswerDoesNotSwallowWhatIsStreamedAfterIt() {
        AnswerSegment answer = new AnswerSegment(dumbTerminal(), () -> { });
        LiveAnswer live = new LiveAnswer(answer);
        answer.append(TABLE);
        // Everything so far is committed at the narrow width, as the frame drain does every frame.
        live.freezeTopRows(Integer.MAX_VALUE, 40);

        answer.append("continued after the resize\n\n");

        assertEquals(1, occurrences(live.render(120), "continued after the resize"),
                "the live tail at the new width must contain what was streamed after the resize: "
                        + text(live.render(120)));
    }

    /**
     * Narrowing is the opposite error of widening: it increases the styled-line count, so a boundary
     * carried in styled coordinates under-counts and the tail redraws content that is already in
     * [[冻结回卷]].
     */
    @Test
    void narrowingMidAnswerDoesNotRedrawWhatIsAlreadyCommitted() {
        AnswerSegment answer = new AnswerSegment(dumbTerminal(), () -> { });
        LiveAnswer live = new LiveAnswer(answer);
        answer.append(TABLE);
        live.freezeTopRows(Integer.MAX_VALUE, 120);

        answer.append("continued after the resize\n\n");

        assertEquals(0, occurrences(live.render(40), "the visible screen and saved"),
                "committed table rows must not come back into the live tail at a narrower width: "
                        + text(live.render(40)));
        assertEquals(1, occurrences(live.render(40), "continued after the resize"),
                text(live.render(40)));
    }

    /**
     * The committed prefix and the live tail partition the answer at <b>every</b> width: a
     * [[destructive resize reset]] repaints the prefix and the [[活区]] draws the tail, so an overlap
     * duplicates content on screen and a gap loses it.
     */
    @Test
    void theCommittedPrefixAndTheLiveTailPartitionTheAnswerAtEveryWidth() {
        AnswerSegment answer = new AnswerSegment(dumbTerminal(), () -> { });
        LiveAnswer live = new LiveAnswer(answer);
        answer.append("# Heading\n");
        for (int i = 0; i < 3; i++) {
            answer.append("paragraph " + i + " with enough words in it to reflow between widths.\n");
        }
        answer.append(TABLE);
        answer.append("a trailing fragment still streaming");
        live.freezeTopRows(Integer.MAX_VALUE, 72);

        for (int width : new int[]{36, 48, 72, 96, 132}) {
            assertEquals(text(answer.renderRetained(width)),
                    text(answer.replayRows(width)) + text(live.render(width)),
                    "committed prefix + live tail must be exactly the whole answer at width " + width);
        }
    }

    private static Terminal dumbTerminal() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        return terminal;
    }

    private static String text(java.util.List<AttributedString> rows) {
        StringBuilder all = new StringBuilder();
        rows.forEach(row -> all.append(row.toString()).append('\n'));
        return all.toString();
    }

    private static int occurrences(java.util.List<AttributedString> rows, String needle) {
        return (int) rows.stream().filter(row -> row.toString().contains(needle)).count();
    }

    /**
     * The entity pass is memoized <b>per view</b> (ADR 0016 D1), across every width that view draws
     * at: the styled line is the same object at each width, so only a table can make it differ.
     */
    @Test
    void completedLineEntityPassRunsOnceAcrossRenderFramesForOneView() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        LiveAnswer live = new LiveAnswer(answer);
        answer.append("See src/Agent.java:232.\n");
        answer.finish();

        for (int frame = 0; frame < 100; frame++) {
            live.render(120);
        }
        live.render(80);

        assertEquals(1, live.proseHighlightPasses());
    }
}
