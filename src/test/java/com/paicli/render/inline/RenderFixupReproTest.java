package com.paicli.render.inline;

import com.paicli.llm.LlmClient;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic reproductions for the TUI fixup wave — issue #1 (thinking rendered twice) and
 * issue #5A (double blank lines between block units).
 *
 * <p>These replay the Agent's exact typed-renderer call order against a byte-capturing mock terminal
 * with no LLM, then assert only externally observable committed scrollback. They are red-until-fixed
 * where a real logic bug exists, and stay green as regression guards afterward.
 *
 * <p>Scope boundary: the mock terminal has no live JLine {@code Status}, so the scroll-region-dependent
 * symptoms — #5B big gap and #3/#4 overlay scrolling — are NOT reproducible here; those are driven by
 * {@link RenderReproDriver} on a real terminal. If the #1 guard below passes, that is itself the
 * finding: the default flow commits reasoning exactly once, so the observed duplication lives in the
 * transcript overlay or a real-terminal live-preview ghost, not the default stream.
 */
class RenderFixupReproTest {

    private static final String REASONING_BODY =
            "Now let me read the remaining key inline render components: ActivityBlock, LiveRegionStrip";

    @Test
    void completedReasoningIsCommittedOnceAsCollapsedHeaderNotAsBody() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();
            r.appendUserPrompt("读一下 render/inline 下的类");

            // The reasoning first streams to the live ActivityBlock preview, exactly as the Agent does
            // via appendThinking on every reasoning delta.
            r.beginThinking("Thinking");
            r.appendThinking(REASONING_BODY);
            h.pump.pump();
            r.endThinking();
            h.pump.pump();

            // Discard every transient live-preview frame; from here we only observe committed output.
            h.sink.reset();

            // The Agent then publishes the completed reasoning (collapsed) and starts the answer.
            var reasoning = r.beginReasoning();
            reasoning.append(REASONING_BODY);
            reasoning.finish();
            var answer = r.beginAnswer();
            answer.append("Let me now read the remaining inline rendering components...\n");
            answer.finish();
            r.appendToolCalls(List.of(tc("glob_files",
                    "{\"pattern\":\"src/main/java/com/paicli/render/inline/*.java\"}")));
            r.endTurn();
            h.pump.pump();

            String committed = strip(h.sink);
            assertEquals(0, occurrences(committed, REASONING_BODY),
                    "completed reasoning body must not enter scrollback; only the collapsed 思考 header should:\n"
                            + committed);
            assertEquals(1, occurrences(committed, "思考 "),
                    "exactly one collapsed reasoning header expected:\n" + committed);
        } finally {
            h.close();
        }
    }

    @Test
    void frozenBlockUnitsAreSeparatedByAtMostOneBlankLine() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();
            r.appendCodeBlock("", "line 1\nline 2");
            h.pump.pump();
            h.sink.reset();

            // A newer block commits the code block to scrollback through freezeSettled.
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            h.pump.pump();

            String committed = strip(h.sink);
            // Count only truly-empty lines: the mock terminal has no erase capability, so JLine's
            // Display clears prior live frames by overwriting with spaces. Those space-runs are a
            // mock artifact (invisible erases on a real terminal); the structural spacing we care
            // about is emitted as genuinely empty rows by the block model + freezeSettled separator.
            int maxRun = maxConsecutiveEmptyLines(committed);
            assertTrue(maxRun <= 1,
                    "expected at most one blank line between block units, found a run of " + maxRun + ":\n"
                            + markBlanks(committed));
        } finally {
            h.close();
        }
    }

    @Test
    void aCommittedFoldableIsNeverRedrawnOnALaterFrame() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();

            // Root cause of issue #5B (the big blank gap): a foldable must NOT stream through the
            // live JLine Display region for one frame and then be re-frozen to scrollback on the next
            // add/endTurn. On a real terminal with a live Status scroll region, that live-then-freeze
            // sequence erases the live rows in place (walking the cursor down) and re-emits the frozen
            // block below, leaving an ~N-line blank gap above the re-emitted block. The deterministic
            // signature of that bug is: the block's unique probe text is drawn on the add-frame AND
            // again on a later frame. A committed foldable must appear on exactly one frame.
            r.appendCodeBlock("java", "int gapProbe = 42;");
            h.pump.pump();                       // add-frame: the block is committed to scrollback here
            String addFrame = strip(h.sink);
            assertEquals(1, occurrences(addFrame, "gapProbe"),
                    "the foldable must be committed to scrollback on its own add-frame:\n" + addFrame);

            h.sink.reset();                      // isolate only the frames AFTER the block was added
            r.endTurn();                         // a later frame (turn end / a newer block)
            h.pump.pump();

            String laterFrame = strip(h.sink);
            assertEquals(0, occurrences(laterFrame, "gapProbe"),
                    "a committed foldable must never be re-drawn on a later frame (live-then-freeze gap):\n"
                            + laterFrame);
        } finally {
            h.close();
        }
    }

    @Test
    void twoStackedFrozenFoldablesAreSeparatedByExactlyOneBlankLine() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();
            // Stack two code units then a tool call. freezeSettled commits the older unit whenever a
            // newer one arrives, so a single frame freezes BOTH code units to scrollback with no live
            // preview in between — the cleanest way to observe the inter-unit separator directly.
            r.appendCodeBlock("py", "alpha");
            r.appendCodeBlock("js", "bravo");
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            h.pump.pump();

            String committed = strip(h.sink);
            // Exactly one blank line separates the first unit's body from the second unit's header:
            // the strip owns that single separator and neither block contributes an edge blank. Before
            // the fix this was a run of two (code block's trailing edge blank + strip separator).
            assertEquals(1, blankLinesBetween(committed, "alpha", "code: js"),
                    "exactly one separator blank expected between stacked frozen units:\n"
                            + markBlanks(committed));
            assertTrue(maxConsecutiveEmptyLines(committed) <= 1,
                    "no double blank anywhere in stacked frozen units:\n" + markBlanks(committed));
        } finally {
            h.close();
        }
    }

    @Test
    void aFinishedAnswerIsSeparatedFromTheNextFoldableByExactlyOneBlank() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();

            // The real per-iteration order: an answer completes, then the next iteration's tool call
            // follows. This is the exact path that dropped the separator — the answer drains its
            // finished lines to scrollback incrementally with NO trailing blank, while foldables always
            // append one, so answer→tool collapsed to zero blanks while tool→thinking kept one.
            var answer = r.beginAnswer();
            answer.append("Here is the plan for the pipeline.\n");
            answer.finish();
            h.pump.pump();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            h.pump.pump();

            String committed = strip(h.sink);
            assertEquals(1, blankLinesBetween(committed, "Here is the plan", "ReadFile"),
                    "a finished answer and the following foldable must be separated by exactly one blank:\n"
                            + markBlanks(committed));
            assertTrue(maxConsecutiveEmptyLines(committed) <= 1,
                    "no double blank anywhere:\n" + markBlanks(committed));
        } finally {
            h.close();
        }
    }

    @Test
    void everyBlockBoundaryInAnIterationHasExactlyOneBlankRegardlessOfType() {
        Harness h = new Harness();
        try {
            InlineRenderer r = h.renderer;
            r.beginTurn();

            // reasoning → answer → tool → reasoning, the shape shown in the user's screenshot. Every
            // adjacent pair must be separated by exactly one blank line; before the fix the mixed
            // boundaries alternated 1 / 0 / 1 because only the foldable side contributed a separator.
            var reasoning = r.beginReasoning();
            reasoning.append("weigh the options");
            reasoning.finish();
            var answer = r.beginAnswer();
            answer.append("Committing to the pipeline plan now.\n");
            answer.finish();
            h.pump.pump();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            var reasoning2 = r.beginReasoning();
            reasoning2.append("re-check the diff");
            reasoning2.finish();
            h.pump.pump();

            String committed = strip(h.sink);
            assertEquals(1, blankLinesBetween(committed, "思考", "Committing to the pipeline plan"),
                    "reasoning→answer must have exactly one blank:\n" + markBlanks(committed));
            assertEquals(1, blankLinesBetween(committed, "Committing to the pipeline plan", "ReadFile"),
                    "answer→tool must have exactly one blank:\n" + markBlanks(committed));
            assertEquals(1, blankLinesBetween(committed, "ReadFile", "思考"),
                    "tool→reasoning must have exactly one blank:\n" + markBlanks(committed));
            assertTrue(maxConsecutiveEmptyLines(committed) <= 1,
                    "no double blank anywhere across the iteration:\n" + markBlanks(committed));
        } finally {
            h.close();
        }
    }

    // --- harness + helpers ---

    private static final class Harness {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final ManualFramePump pump = new ManualFramePump();
        final Terminal terminal;
        final InlineRenderer renderer;

        Harness() {
            terminal = Mockito.mock(Terminal.class);
            Mockito.when(terminal.getType()).thenReturn("xterm-256color");
            Size size = new Size(80, 20);
            Mockito.when(terminal.getSize()).thenReturn(size);
            Mockito.when(terminal.getBufferSize()).thenReturn(size);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), false);
            Mockito.when(terminal.writer()).thenReturn(writer);
            Mockito.doAnswer(invocation -> {
                writer.flush();
                return null;
            }).when(terminal).flush();
            Mockito.when(terminal.handle(Mockito.eq(Terminal.Signal.WINCH), Mockito.any()))
                    .thenReturn(Terminal.SignalHandler.SIG_DFL);
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker());
            renderer.start();
        }

        void close() {
            renderer.close();
        }
    }

    private static String strip(ByteArrayOutputStream sink) {
        return AttributedString.stripAnsi(sink.toString(StandardCharsets.UTF_8));
    }

    private static int maxConsecutiveEmptyLines(String text) {
        String[] lines = text.split("\n", -1);
        // Drop the final split element: a block committed as "…\n" plus its trailing separator "\n"
        // ends the string with "\n\n", and split(-1) turns that terminator into a phantom empty. It
        // is a single trailing separator line, not a double blank between units.
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        int max = 0;
        int run = 0;
        for (int i = 0; i < count; i++) {
            if (lines[i].isEmpty()) {
                run++;
                max = Math.max(max, run);
            } else {
                run = 0;
            }
        }
        return max;
    }

    private static String markBlanks(String text) {
        return text.replace("\n", "⏎\n");
    }

    /** Count truly-empty lines strictly between the first {@code from} match and the next {@code to}. */
    private static int blankLinesBetween(String text, String from, String to) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.length; i++) {
            if (start < 0) {
                if (lines[i].contains(from)) {
                    start = i;
                }
            } else if (lines[i].contains(to)) {
                end = i;
                break;
            }
        }
        if (start < 0 || end < 0) {
            return -1;
        }
        int blanks = 0;
        for (int i = start + 1; i < end; i++) {
            if (lines[i].isEmpty()) {
                blanks++;
            }
        }
        return blanks;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }
}
