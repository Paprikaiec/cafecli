package com.cafecli.render.inline;

import com.cafecli.llm.LlmClient;
import com.cafecli.render.StatusInfo;
import com.cafecli.render.inline.replay.TerminalScreenModel;
import com.cafecli.tool.ToolExecutionResult;
import com.cafecli.tool.ToolOutput;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.cafecli.render.inline.replay.TerminalScreenModel.blankRowsBetween;
import static com.cafecli.render.inline.replay.TerminalScreenModel.dump;
import static com.cafecli.render.inline.replay.TerminalScreenModel.maxBlankRun;
import static com.cafecli.render.inline.replay.TerminalScreenModel.rowsContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 屏幕真相回归：把渲染循环吐出的字节喂进 {@link TerminalScreenModel}，断言<em>用户实际看到的文档</em>
 * （回卷 + 当前屏），而不是字节流。
 *
 * <p>这是这两个症状唯一有效的接缝。字节流无法区分「画了又被原地擦掉」（正确的活区重绘）与「留在
 * 屏幕上被滚进回卷」（缺陷）；两者字节相同。状态坞重复与块间空行两个症状都恰好落在这条分界上。
 */
class ScreenTruthRegressionTest {

    private static final String DOCK_TOP = "Ctrl+Y";
    private static final String INPUT_TEXT = "explain the resize bug";
    private static final String DOCK_BOTTOM = "Auto Model";

    // --- rig -------------------------------------------------------------------------------------

    private static final class Rig implements AutoCloseable {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final List<int[]> resizes = new ArrayList<>();
        private final PipedOutputStream keys;
        final TracingTerminal terminal;
        final ManualFramePump pump = new ManualFramePump();
        final ManualClock clock = new ManualClock();
        final InlineRenderer renderer;
        private int width;
        private int height;
        /** The width the bound input frame claims, mirroring how the real capture tracks resizes. */
        volatile int liveWidth;

        Rig(int width, int height) throws Exception {
            this.width = width;
            this.height = height;
            this.liveWidth = width;
            PipedInputStream in = new PipedInputStream(1 << 16);
            keys = new PipedOutputStream(in);
            terminal = new TracingTerminal("screen-truth", "xterm-256color", in, sink);
            terminal.setSize(new Size(width, height));
            renderer = new InlineRenderer(terminal,
                    new PrintStream(sink, false, StandardCharsets.UTF_8), pump, new ManualFrameTicker(),
                    clock);
            renderer.start();
        }

        /**
         * One step of a gesture. Time advances by the measured p50 intra-gesture gap of the authentic
         * fullscreen animation (8.8 ms, n=800 gaps, 2026-08-13), so a run of these reads to the
         * renderer as one continuous [[geometry turbulence]] rather than as separate gestures.
         *
         * <p>The sleep is thread hand-off for the signal path, not timing: everything the turbulence
         * gate measures comes from the injected clock.
         */
        void resize(int newWidth, int newHeight) throws Exception {
            resize(newWidth, newHeight, 9);
        }

        /**
         * One step of a gesture whose steps are further apart than a fullscreen animation's.
         *
         * <p>The gap is what decides which half of the [[geometry turbulence]] gate a gesture
         * exercises, so it has to be a parameter rather than a constant: a hand drag of the window
         * edge steps every ~50 ms (measured from {@code scripts/resize-storm.sh}'s {@code drag} /
         * {@code heightdrag}), which is under the 100 ms quiet interval but long enough that a
         * sustained drag crosses the 500 ms max defer and owes a keep-alive repaint mid-gesture.
         */
        void resize(int newWidth, int newHeight, long gapMillis) throws Exception {
            resizes.add(new int[]{sink.size(), newWidth, newHeight});
            this.liveWidth = newWidth;
            clock.advance(gapMillis);
            terminal.setSize(new Size(newWidth, newHeight));
            terminal.raise(Terminal.Signal.WINCH);
            Thread.sleep(120);
        }

        /**
         * The same step on a terminal that delivers <b>no signal at all</b>. Nothing tells the
         * renderer to look; the sampler's slow heartbeat has to notice on its own (ADR 0010 amendment
         * A3c). The wait is real because the heartbeat is real: it is the sampler's own cadence, not
         * anything the [[geometry turbulence]] gate measures.
         */
        void resizeWithoutAnySignal(int newWidth, int newHeight) throws Exception {
            resizes.add(new int[]{sink.size(), newWidth, newHeight});
            this.liveWidth = newWidth;
            clock.advance(9);
            terminal.setSize(new Size(newWidth, newHeight));
            Thread.sleep(GeometrySampler.DEFAULT_HEARTBEAT_MILLIS * 2);
        }

        /**
         * The gesture ends: the user lets go, the geometry holds still, and the deferred redraw is
         * released. Under ADR 0010 amendment A3b this is the only moment a resize repaints — during
         * the gesture the screen deliberately holds nothing but the erase.
         */
        void settle() {
            // First let the renderer observe the geometry it has been signalled about. The quiet
            // interval is counted from the observation, because that is all a sampler can know: the
            // world may have stopped moving before anything looked.
            pump.drain();
            clock.advance(TurbulenceGate.DEFAULT_QUIET_MILLIS + 1);
            pump.drain();
        }

        /** Put keystrokes where the terminal will read them, as a user's keyboard would. */
        void type(String keys) throws Exception {
            this.keys.write(keys.getBytes(StandardCharsets.UTF_8));
            this.keys.flush();
        }

        /** How many bytes the renderer has written so far — a cut point for "what did this gesture write". */
        int writtenBytes() {
            return sink.size();
        }

        /** The bytes written between two cut points, decoded as the terminal would consume them. */
        String bytesBetween(int from, int to) {
            byte[] all = sink.toByteArray();
            int start = Math.min(Math.max(0, from), all.length);
            int end = Math.min(Math.max(start, to), all.length);
            return new String(all, start, end - start, StandardCharsets.UTF_8);
        }

        /** 原始字节流（转义可见），诊断擦除定位时用。 */
        String raw() {
            return sink.toString(StandardCharsets.UTF_8)
                    .replace("\u001b", "<ESC>")
                    .replace("\r", "<CR>")
                    .replace("\n", "<LF>\n");
        }

        /** Replay the whole captured stream through a fresh emulator, applying resizes in order. */
        List<String> document() {
            return replay().document();
        }

        /**
         * The same replay as {@link #document()}, serialized the way Terminal.app's
         * {@code history of tab} does it: soft-wrapped runs rejoined into logical lines. This is what
         * a user's copy yields, so it is how "the content survived" is asserted for output whose rows
         * are longer than the current width.
         */
        String historyText() {
            return replay().historyText();
        }

        private TerminalScreenModel replay() {
            byte[] all = sink.toByteArray();
            TerminalScreenModel screen = new TerminalScreenModel(width, height);
            int cursor = 0;
            for (int[] r : resizes) {
                int at = Math.min(r[0], all.length);
                if (at > cursor) {
                    screen.feed(new String(all, cursor, at - cursor, StandardCharsets.UTF_8));
                    cursor = at;
                }
                screen.resize(r[1], r[2]);
            }
            if (cursor < all.length) {
                screen.feed(new String(all, cursor, all.length - cursor, StandardCharsets.UTF_8));
            }
            return screen;
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

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    private static ToolExecutionResult tr(String name, String args, String text) {
        return new ToolExecutionResult(name + "-id", name, args,
                ToolOutput.text(text), 7, false);
    }

    // --- issue 01: a [[工具块]] is opened in the 活区 and committed once its results arrive -----------

    private static final String TOOL_RESULT = String.join("\n",
            "result line one", "result line two", "result line three",
            "result line four", "result line five");

    /**
     * The gating criterion of ADR 0017 (issue 01): a tool call that ran and completed is on screen
     * <b>exactly once</b>, and what it left there is line-for-line what the same block leaves when it
     * is committed already-complete.
     *
     * <p>The defect this shape exists for is a <b>copy</b>: the block is painted into the [[活区]]
     * while the tool runs and then written into [[冻结回卷]], and «painted then erased in place» is
     * byte-identical to «left on screen and scrolled away». Only the screen model separates them.
     *
     * <p>The control is the same event sequence with no frame in between, so the block never reaches
     * the live region at all — an independent source of truth for "what one committed tool block looks
     * like", rather than a hand-counted expectation (ADR 0010 amendment A19's lesson).
     */
    @Test
    void aCompletedToolCallIsOnScreenOnceAndMatchesTheSameBlockCommittedAlreadyComplete()
            throws Exception {
        List<String> paintedLive = toolTurn(true);
        List<String> committedWhole = toolTurn(false);

        assertEquals(1, rowsContaining(paintedLive, "ReadFile(README.md)"),
                "the call header must survive its live phase exactly once:\n" + dump(paintedLive));
        assertEquals(1, rowsContaining(paintedLive, "result line one"),
                "the result's first row must be on screen exactly once:\n" + dump(paintedLive));
        assertEquals(0, blankRowsBetween(paintedLive, "ReadFile(README.md)", "result line one"),
                "a call and its result are ONE block, so no separator blank divides them:\n"
                        + dump(paintedLive));
        assertEquals(1, blankRowsBetween(paintedLive, "Let me read that file.", "ReadFile(README.md)"),
                "and the block still earns exactly one separator from what precedes it:\n"
                        + dump(paintedLive));
        assertEquals(committedWhole, paintedLive,
                "a tool block painted live must leave the same screen as one committed whole:\n"
                        + dump(paintedLive));
    }

    /** The result preview is capped, and the overflow line says where the rest is (D3 / D7). */
    @Test
    void aToolResultIsPreviewedAndTheRestIsAnnounced() throws Exception {
        List<String> doc = toolTurn(true);

        assertEquals(1, rowsContaining(doc, "result line three"), dump(doc));
        assertEquals(0, rowsContaining(doc, "result line four"),
                "the preview is capped at three visual rows:\n" + dump(doc));
        assertEquals(1, rowsContaining(doc, "… +2 行 (ctrl+o 查看全部)"),
                "the overflow line must name the omitted source lines:\n" + dump(doc));
    }

    /**
     * A committed [[工具块]] survives a column-by-column drag as <b>one</b> block repainted at the new
     * width, and its overflow count follows the width down.
     *
     * <p>The result cap is in visual rows, so a narrower terminal fits fewer source lines into the
     * same three rows and {@code … +N 行} names a larger N. That is the intended consequence of D3's
     * unit choice, and it is safe precisely because a [[destructive resize reset]] re-renders a
     * retained entry from its source — the count is recomputed, never carried over. The copy half of
     * the drag regression is unchanged: many small steps must not accumulate duplicates.
     */
    @Test
    void aToolBlockIsRepaintedAtTheNewWidthAndItsOverflowCountFollows() throws Exception {
        String result = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "result line %d ".formatted(i) + "x".repeat(40))
                .collect(java.util.stream.Collectors.joining("\n"));
        try (Rig rig = new Rig(60, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", result)));
            rig.pump.pump();
            assertEquals(1, rowsContaining(rig.document(), "… +3 行 (ctrl+o 查看全部)"),
                    "at 60 columns each result line is one row, so three of six fit:\n"
                            + dump(rig.document()));

            for (int width = 59; width >= 40; width--) {
                rig.resize(width, 16, 50);
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "ReadFile(README.md)"),
                    "the drag must leave one block, not a copy per step:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "result line 1 "), dump(doc));
            assertEquals(1, rowsContaining(doc, "… +5 行 (ctrl+o 查看全部)"),
                    "at 40 columns each result line takes two rows, so only one of six fits:\n"
                            + dump(doc));
            assertEquals(0, rowsContaining(doc, "… +3 行"),
                    "the count rendered at the old width must not survive the reset:\n" + dump(doc));
        }
    }

    /**
     * A [[转向消息]] submitted <b>while the tool is running</b> is committed before the tool block,
     * even though the call happened first (ADR 0017 D2's priced consequence, and exactly the position
     * ADR 0016 D5 wants for a correction).
     *
     * <p>The control is the same turn with nobody steering: take the echo back out of the steered
     * screen and the tool block must be line-for-line what it is when it was never interrupted. That
     * is the whole of "the block still appears complete", stated against an independent screen rather
     * than a hand-counted expectation.
     */
    @Test
    void aSteeringEchoSubmittedWhileAToolRunsLandsBeforeTheToolBlock() throws Exception {
        List<String> steered = toolTurnWithSteering(true);
        List<String> control = toolTurnWithSteering(false);

        int echo = rowIndexOf(steered, STEER_TEXT);
        int header = rowIndexOf(steered, "ReadFile(README.md)");
        assertTrue(echo >= 0, "the steering echo must be on screen:\n" + dump(steered));
        assertTrue(header >= 0, "the tool block must be on screen:\n" + dump(steered));
        assertTrue(echo < header,
                "a correction submitted during execution is committed before the tool block:\n"
                        + dump(steered));

        List<String> withoutTheCorrection = trimmed(steered.stream()
                .filter(row -> !row.contains(STEER_TEXT)).toList());
        assertEquals(trimmed(control), withoutTheCorrection,
                "removing the correction must leave the same screen as the uninterrupted turn:\n"
                        + dump(steered));
    }

    /**
     * A document is scrollback plus a full screen, so its blank tail is padding rather than content;
     * dropping one row shortens that tail by one and would otherwise fail a control comparison for a
     * reason that has nothing to do with what is written.
     */
    private static List<String> trimmed(List<String> document) {
        int end = document.size();
        while (end > 0 && document.get(end - 1).isBlank()) {
            end--;
        }
        return document.subList(0, end);
    }

    private static final String STEER_TEXT = "actually read ROADMAP too";

    private static int rowIndexOf(List<String> document, String needle) {
        for (int i = 0; i < document.size(); i++) {
            if (document.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> toolTurnWithSteering(boolean steer) throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("Let me read that file.\n");
            answer.finish();
            rig.pump.pump();

            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            rig.pump.pump();
            if (steer) {
                r.printSteeringPrompt(STEER_TEXT);
                rig.pump.pump();
            }
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", TOOL_RESULT)));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            return rig.document();
        }
    }

    /**
     * One turn that calls one tool and receives its result.
     *
     * @param paintLive whether a frame runs while the tool is still executing, so the block spends
     *                  time in the [[活区]] before it is committed
     */
    private static List<String> toolTurn(boolean paintLive) throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("Let me read that file.\n");
            answer.finish();
            rig.pump.pump();

            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            if (paintLive) {
                rig.pump.pump();
                rig.pump.pump();
            }
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", TOOL_RESULT)));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            return rig.document();
        }
    }

    // --- issue 02: for a file-writing tool, the diff IS the result (ADR 0017 D4) ------------------

    private static final String EDIT_ARGS = "{\"path\":\"src/App.java\"}";
    private static final String EDIT_BEFORE = "one\ntwo\nthree\n";
    private static final String EDIT_AFTER = "one\ntwo point five\nthree\n";
    /** What {@code edit_file} returns as text — the summary the diff underneath already tells. */
    private static final String EDIT_SUMMARY =
            "文件已编辑: src/App.java，共 1 条编辑，替换 1 处匹配";

    /**
     * An edit is <b>one</b> block: the call header, and then the diff as its result — no 3-row text
     * preview of "文件已编辑: …，共 N 条编辑" stacked on top of a diff that says the same thing.
     *
     * <p>The screen-truth half is the same one issue 01 established: the block is painted into the
     * [[活区]] while the tool runs and then written into [[冻结回卷]], and «painted then erased in
     * place» is byte-identical to «left on screen and scrolled away», so only the screen model can
     * tell a copy from a commit. The control is the same event sequence with no frame in between, so
     * the block never reaches the live region at all.
     */
    @Test
    void anEditIsOneBlockOfCallHeaderThenDiffWithNoTextSummaryOnTopOfIt() throws Exception {
        List<String> paintedLive = editTurn(true);
        List<String> committedWhole = editTurn(false);

        assertEquals(1, rowsContaining(paintedLive, "编辑 1 个文件"),
                "the call header must survive its live phase exactly once:\n" + dump(paintedLive));
        assertEquals(1, rowsContaining(paintedLive, "Update src/App.java"),
                "the diff summary is the head of the result, and there is one of it:\n"
                        + dump(paintedLive));
        assertEquals(1, rowsContaining(paintedLive, "two point five"),
                "the diff body must be on screen exactly once:\n" + dump(paintedLive));
        assertEquals(0, rowsContaining(paintedLive, "文件已编辑"),
                "the text summary says what the diff says, so it is not shown as well:\n"
                        + dump(paintedLive));
        assertEquals(0, blankRowsBetween(paintedLive, "编辑 1 个文件", "Update src/App.java"),
                "a call and its diff are ONE block, so no separator blank divides them:\n"
                        + dump(paintedLive));
        assertEquals(committedWhole, paintedLive,
                "an edit painted live must leave the same screen as one committed whole:\n"
                        + dump(paintedLive));
    }

    /** A brand-new file: {@code before == null}, so the diff is all additions and still the result. */
    @Test
    void writingANewFileShowsADiffOfAdditionsAndNoTextSummary() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("write_file", "{\"path\":\"notes.md\"}")));
            rig.pump.pump();
            r.appendDiff("notes.md", null, "alpha\nbeta\n");
            rig.pump.pump();
            r.appendToolResults(List.of(
                    tr("write_file", "{\"path\":\"notes.md\"}", "文件已写入: notes.md")));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "写入 1 个文件"), dump(doc));
            assertEquals(1, rowsContaining(doc, "Update notes.md  +2 -0"),
                    "a new file is two additions and no deletions:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "alpha"), dump(doc));
            assertEquals(1, rowsContaining(doc, "beta"), dump(doc));
            assertEquals(0, rowsContaining(doc, "文件已写入"),
                    "the diff is the result view, so the text summary is dropped:\n" + dump(doc));
        }
    }

    /**
     * The control group: {@code create_project} is <b>not</b> a file-change tool. It writes scaffolding
     * with a bare {@code Files.writeString} and never reaches {@code persistFileContent}, so no write
     * observer fires for it and it takes an ordinary text result (ADR 0017 D4). Without this case a
     * screen-truth assertion could go green against a screen that never existed — and it is also what
     * catches a future "helpful" wiring of the observer onto a scaffold generator.
     */
    @Test
    void createProjectShowsATextResultAndNoDiffAtAll() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("create_project", "{\"name\":\"demo\"}")));
            rig.pump.pump();
            r.appendToolResults(List.of(tr("create_project", "{\"name\":\"demo\"}",
                    String.join("\n", "已创建项目 demo", "  demo/pom.xml", "  demo/src/main/java",
                            "  demo/src/test/java", "  demo/README.md"))));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "创建 1 个项目"), dump(doc));
            assertEquals(1, rowsContaining(doc, "已创建项目 demo"),
                    "a scaffold generator takes the ordinary text result:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "… +2 行 (ctrl+o 查看全部)"),
                    "and the ordinary three-visual-row cap with it:\n" + dump(doc));
            assertEquals(0, rowsContaining(doc, "Update "),
                    "four pom.xml templates as a diff would be noise, so there is no diff:\n"
                            + dump(doc));
        }
    }

    /**
     * A batch is one block, and each result inside it is shown through the view its own type asks for:
     * the edit through its diff, the read through the ordinary text preview. Dropping one tool's text
     * must not drop the batch's.
     */
    @Test
    void inAMixedBatchOnlyTheWritingToolsTextIsReplacedByItsDiff() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(
                    tc("edit_file", EDIT_ARGS), tc("read_file", "{\"path\":\"README.md\"}")));
            rig.pump.pump();
            r.appendDiff("src/App.java", EDIT_BEFORE, EDIT_AFTER);
            r.appendToolResults(List.of(
                    tr("edit_file", EDIT_ARGS, EDIT_SUMMARY),
                    tr("read_file", "{\"path\":\"README.md\"}", "readme first line\nreadme second")));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "Update src/App.java"), dump(doc));
            assertEquals(0, rowsContaining(doc, "文件已编辑"), dump(doc));
            assertEquals(1, rowsContaining(doc, "readme first line"),
                    "the read's text result is untouched by the edit's diff:\n" + dump(doc));
        }
    }

    /**
     * One turn that edits a file.
     *
     * @param paintLive whether frames run while the tool is still executing, so the block spends time
     *                  in the [[活区]] — with and without its diff — before it is committed
     */
    private static List<String> editTurn(boolean paintLive) throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("Applying the edit now.\n");
            answer.finish();
            rig.pump.pump();

            r.appendToolCalls(List.of(tc("edit_file", EDIT_ARGS)));
            if (paintLive) {
                rig.pump.pump();
                rig.pump.pump();
            }
            r.appendDiff("src/App.java", EDIT_BEFORE, EDIT_AFTER);
            if (paintLive) {
                rig.pump.pump();
            }
            r.appendToolResults(List.of(tr("edit_file", EDIT_ARGS, EDIT_SUMMARY)));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            return rig.document();
        }
    }

    // --- issue 03: a running command's output tail lives in the 活区尾 (ADR 0017 D5) ---------------

    private static final String COMMAND_ARGS = "{\"command\":\"mvn -q test\"}";

    /**
     * While the command runs the [[活区尾]] shows its <b>last</b> five non-empty output lines, and
     * they scroll: what was pushed out is not on screen any more.
     *
     * <p>This is the whole reason the tail exists — {@code execute_command} is the one tool whose
     * duration is unbounded and where the user genuinely cannot tell a thirty-second command from a
     * hung one. The rows are transient content sitting beside the spinner: still no result, so still
     * not a completed block, so nothing is committed here (D2).
     */
    @Test
    void aRunningCommandShowsItsLastFiveNonEmptyOutputLines() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("execute_command", COMMAND_ARGS)));
            rig.pump.pump();

            for (int i = 1; i <= 8; i++) {
                r.appendCommandOutput("mvn -q test", "compiling module " + i);
                r.appendCommandOutput("mvn -q test", "   ");
                rig.pump.pump();
            }

            List<String> doc = rig.document();
            for (int i = 4; i <= 8; i++) {
                assertEquals(1, rowsContaining(doc, "compiling module " + i),
                        "the last five lines must be on screen, once each:\n" + dump(doc));
            }
            assertEquals(0, rowsContaining(doc, "compiling module 3"),
                    "the tail scrolls: older lines leave the screen rather than piling up:\n"
                            + dump(doc));
            assertEquals(0, rowsContaining(doc, "ctrl+o 查看全部"),
                    "a running command has no result yet, so nothing about it is committed:\n"
                            + dump(doc));
        }
    }

    /**
     * Four concurrent commands (the default parallelism) share the five-row budget: one row each.
     *
     * <p>The budget is the [[活区尾]]'s, not each command's, because what is being spent is the same
     * screen. Five is <b>picked, not measured</b> — the same unmade measurement as ADR 0016's 20 / 12,
     * and issue 04 owns it.
     */
    @Test
    void concurrentCommandsShareTheFiveRowBudget() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(
                    tc("execute_command", "{\"command\":\"cmd-a\"}"),
                    tc("execute_command", "{\"command\":\"cmd-b\"}"),
                    tc("execute_command", "{\"command\":\"cmd-c\"}"),
                    tc("execute_command", "{\"command\":\"cmd-d\"}")));
            rig.pump.pump();

            for (String command : List.of("cmd-a", "cmd-b", "cmd-c", "cmd-d")) {
                for (int i = 1; i <= 3; i++) {
                    r.appendCommandOutput(command, command + " step " + i);
                }
            }
            rig.pump.pump();

            List<String> doc = rig.document();
            int rows = 0;
            for (String command : List.of("cmd-a", "cmd-b", "cmd-c", "cmd-d")) {
                assertEquals(1, rowsContaining(doc, command + " step 3"),
                        command + " must still show its newest line:\n" + dump(doc));
                assertEquals(0, rowsContaining(doc, command + " step 2"),
                        "four commands get one row each, not more:\n" + dump(doc));
                rows += rowsContaining(doc, command + " step 3");
            }
            assertTrue(rows <= 5, "the shared budget is five rows, got " + rows + ":\n" + dump(doc));
        }
    }

    /**
     * The gating criterion of issue 03: when the command finishes, the running tail is <b>replaced</b>
     * by the completed result — its rows are not on screen any more, anywhere.
     *
     * <p>This is precisely what byte assertions cannot judge. Erasing the tail in place and letting it
     * scroll away emit the same bytes (AGENTS.md is explicit), so only the screen model separates the
     * two, and only one of them is correct. The control group is the same turn in which the command
     * printed nothing at all: with the tail gone, the two screens must be identical.
     */
    @Test
    void aFinishedCommandsRunningTailIsGoneFromTheScreen() throws Exception {
        List<String> withTail = commandTurn(true);
        List<String> withoutTail = commandTurn(false);

        for (int i = 1; i <= 8; i++) {
            assertEquals(0, rowsContaining(withTail, "compiling module " + i),
                    "no row of the running tail may survive the command:\n" + dump(withTail));
        }
        assertEquals(1, rowsContaining(withTail, "Shell(mvn -q test)"),
                "the command's block is on screen exactly once:\n" + dump(withTail));
        assertEquals(trimmed(withoutTail), trimmed(withTail),
                "a command that printed while it ran must leave the same screen as one that did not:\n"
                        + dump(withTail));
    }

    /**
     * Running shows the <b>tail</b>, completed shows the <b>head</b>, and they differ on purpose: while
     * it runs the newest line is what tells you it is alive; once it is done the first rows are what
     * tell you what happened.
     */
    @Test
    void theRunningTailIsReplacedByTheCompletedResultHead() throws Exception {
        List<String> doc = commandTurn(true);

        assertEquals(1, rowsContaining(doc, "命令执行完成 (exit code: 0)"),
                "the completed block shows the head of its result:\n" + dump(doc));
        assertEquals(1, rowsContaining(doc, "BUILD SUCCESS"), dump(doc));
        assertEquals(0, rowsContaining(doc, "artifact: target/cafecli.jar"),
                "the head is the first three rows, not the last ones the tail was showing:\n" + dump(doc));
        assertEquals(1, rowsContaining(doc, "… +2 行 (ctrl+o 查看全部)"),
                "and the rest of the result is announced, not shown:\n" + dump(doc));
    }

    /**
     * The control group of ADR 0017 D5: <b>only</b> {@code execute_command} has a running tail.
     * {@code read_file} and {@code grep_code} return in milliseconds, so a running block of theirs adds
     * nothing at all to the [[活区尾]] — its rows are its call header and nothing else.
     *
     * <p>Stated as a difference rather than as an absolute, because "no rows appeared" is exactly the
     * kind of assertion that goes green against a screen that never existed (ADR 0010 amendment A19).
     */
    @Test
    void onlyACommandGrowsTheLiveTailWhileItRuns() throws Exception {
        int readingRows = nonBlankRows(runningToolScreen(tc("read_file", "{\"path\":\"README.md\"}")));
        int grepRows = nonBlankRows(runningToolScreen(tc("grep_code", "{\"pattern\":\"dock\"}")));
        int commandRows = nonBlankRows(runningToolScreen(tc("execute_command", COMMAND_ARGS)));

        assertEquals(readingRows, grepRows,
                "neither read_file nor grep_code shows anything while it runs");
        assertEquals(readingRows + 5, commandRows,
                "the command's five tail rows are the only difference between the two screens");
    }

    /**
     * The observer runs on the {@code cafecli-command-output} thread, and the render thread is the
     * live region's only writer. So the producing thread's call writes <b>zero</b> bytes: the rows
     * appear on the next frame, composed by the render thread like every other event.
     */
    @Test
    void theCommandOutputThreadWritesNothingItselfTheRenderThreadDoes() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("execute_command", COMMAND_ARGS)));
            rig.pump.pump();

            int before = rig.writtenBytes();
            Thread producer = new Thread(
                    () -> r.appendCommandOutput("mvn -q test", "written by the reader thread"),
                    "cafecli-command-output");
            producer.start();
            producer.join();

            assertEquals(before, rig.writtenBytes(),
                    "the command-output thread must not write the terminal");
            rig.pump.pump();
            assertEquals(1, rowsContaining(rig.document(), "written by the reader thread"),
                    "the render thread puts it on screen on the next frame:\n" + dump(rig.document()));
        }
    }

    /**
     * A command killed by {@code destroyForcibly} on timeout is cleaned up as thoroughly as one that
     * exited: the tail goes, and the timeout message is the result.
     */
    @Test
    void aTimedOutCommandLeavesNoTailAndShowsTheTimeoutAsItsResult() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("execute_command", COMMAND_ARGS)));
            rig.pump.pump();
            for (int i = 1; i <= 8; i++) {
                r.appendCommandOutput("mvn -q test", "compiling module " + i);
            }
            rig.pump.pump();

            r.appendToolResults(List.of(tr("execute_command", COMMAND_ARGS,
                    "命令执行超时（120秒），已强制终止")));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "命令执行超时"), dump(doc));
            for (int i = 1; i <= 8; i++) {
                assertEquals(0, rowsContaining(doc, "compiling module " + i),
                        "a killed command's tail is cleaned up like any other:\n" + dump(doc));
            }
        }
    }

    /** The screen while one tool batch is open in the [[活区]] and has not returned yet. */
    private static List<String> runningToolScreen(LlmClient.ToolCall call) throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(call));
            rig.pump.pump();
            if ("execute_command".equals(call.function().name())) {
                for (int i = 1; i <= 5; i++) {
                    r.appendCommandOutput("mvn -q test", "compiling module " + i);
                }
            }
            rig.pump.pump();
            rig.pump.pump();
            return rig.document();
        }
    }

    private static int nonBlankRows(List<String> document) {
        return (int) document.stream().filter(row -> !row.isBlank()).count();
    }

    /**
     * One turn running one command to completion.
     *
     * @param printWhileRunning whether the command prints anything while it runs, so its output tail
     *                          is painted into the [[活区]] before the result replaces it
     */
    private static List<String> commandTurn(boolean printWhileRunning) throws Exception {
        // The result deliberately shares no line with what the command printed while it ran: the tail
        // showed the newest lines, the completed block shows the first ones.
        String result = String.join("\n",
                "命令执行完成 (exit code: 0)", "BUILD SUCCESS", "total time: 3.2 s",
                "finished at 2026-08-31", "artifact: target/cafecli.jar");
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.appendToolCalls(List.of(tc("execute_command", COMMAND_ARGS)));
            rig.pump.pump();
            if (printWhileRunning) {
                for (int i = 1; i <= 8; i++) {
                    r.appendCommandOutput("mvn -q test", "compiling module " + i);
                    rig.pump.pump();
                }
            }
            r.appendToolResults(List.of(tr("execute_command", COMMAND_ARGS, result)));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();
            return rig.document();
        }
    }

    // --- symptom 1: the dock must never become scrollback -----------------------------------------

    @Test
    void theDockIsNeverDuplicatedWhenBlocksFreezeDuringATurn() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();

            // 一个普通回合：思考 → 回答 → 工具 → 回答 → 工具。每次冻结都要求活区先擦干净自己。
            var reasoning = r.beginReasoning();
            reasoning.append("weigh the options");
            reasoning.finish();
            rig.pump.pump();
            var a1 = r.beginAnswer();
            a1.append("First paragraph of the answer.\n");
            a1.finish();
            rig.pump.pump();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", "ok")));
            rig.pump.pump();
            var a2 = r.beginAnswer();
            a2.append("Second paragraph of the answer.\n");
            a2.finish();
            rig.pump.pump();
            r.appendToolCalls(List.of(tc("list_dir", "{\"path\":\"src\"}")));
            r.appendToolResults(List.of(tr("list_dir", "{\"path\":\"src\"}", "ok")));
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM),
                    "the status dock's footer row must exist exactly once (live), never in scrollback:\n"
                            + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "the status dock's header row must exist exactly once (live), never in scrollback:\n"
                            + dump(doc));
        }
    }

    @Test
    void theDockIsNeverDuplicatedAcrossAWidthChangeMidTurn() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var a1 = r.beginAnswer();
            a1.append("Before the resize.\n");
            a1.finish();
            rig.pump.pump();

            rig.resize(64, 16);
            rig.settle();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", "ok")));
            rig.pump.pump();
            rig.resize(52, 16);
            rig.settle();
            var a2 = r.beginAnswer();
            a2.append("After the resize.\n");
            a2.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM),
                    "a width change must not leave stale dock copies in scrollback:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "a width change must not leave stale dock copies in scrollback:\n" + dump(doc));
            // 护栏：不得靠擦掉历史来「修好」重复——resize 前写下的转写必须原样还在。
            assertEquals(1, rowsContaining(doc, "Before the resize."),
                    "a width change must not destroy transcript written before it:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "After the resize."),
                    "a width change must not destroy transcript written after it:\n" + dump(doc));
        }
    }

    @Test
    void repeatedResizesUnderALiveSpinnerLeaveNoCopies() throws Exception {
        // 真实终端上最刺眼的那一幕：回合运行中来回缩放，屏上堆出十几份 "Thinking... (esc to cancel, 1s)"
        // ——**时间戳全都一样**，所以它们不是随时间画出来的帧，而是每一次尺寸变化各留下的一整份副本。
        // 这一幕与前两条 resize 回归的差别在于活区里同时有「每帧都变的行」（spinner）和「不变的行」
        // （状态坞），JLine 的 Display 是**差分**渲染，只重写变了的行——上一帧结束时光标停在哪里，
        // 因此不是「最后一行」这么简单。
        try (Rig rig = new Rig(100, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            // 先把屏铺满：真实会话里活区永远贴着屏幕底部，而空屏上的活区贴着**顶部**——窄化让内容
            // 变高时，贴顶的活区会被终端自己滚进回卷，那是任何擦除都够不到的，与本条要测的东西无关。
            var filler = r.beginAnswer();
            for (int i = 0; i < 30; i++) {
                filler.append("transcript line " + i + "\n");
            }
            filler.finish();
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();
            rig.pump.pump();

            for (int width : new int[]{92, 84, 76, 84, 92, 100}) {
                rig.resize(width, 16);
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "每次尺寸变化都不得留下一份 spinner 副本:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "每次尺寸变化都不得留下一份状态坞副本:\n" + dump(doc));
        }
    }

    // --- the gating assertion: a geometry change leaves the replay window and nothing else ---------

    /**
     * The gating assertion of ADR 0010 amendment A16: after a geometry change the terminal holds the
     * [[bounded replay window]] rendered at the new geometry <b>and nothing else</b>.
     *
     * <p>It deliberately references no erase distance, no cursor arithmetic and no frame internals —
     * only the document a user ends up looking at. That is what gives the offline screen model
     * arbitration power again: the property no longer shares its assumptions with the code under
     * test, because a [[destructive resize reset]] makes the previous screen irrelevant instead of
     * computing how far to walk back over it.
     *
     * <p>The session deliberately commits more than the window holds (20 items of two rows each
     * against a 3 × 10 = 30-row budget), so both halves are observable in one assertion: the newest
     * items are present exactly once, and everything older is gone from screen and saved scrollback
     * alike.
     */
    @Test
    void aSingleWidthChangeLeavesExactlyTheReplayWindow() throws Exception {
        assertOneGeometryChangeLeavesExactlyTheReplayWindow(48, 10);
    }

    /**
     * A [[destructive resize reset]] erases scrollback Cafecli did not write, which is the stated
     * price of the amendment (A4) — but only when a [[geometry epoch]] actually ends. The first
     * frame of a session establishes the epoch instead: there is no previous screen of ours to
     * declare void, so starting Cafecli must leave the user's shell history exactly where it was.
     */
    @Test
    void startingUpDoesNotEraseTheScreenCafecliInherited() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            rig.sink.write("shell history line\n".getBytes(StandardCharsets.UTF_8));

            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var first = r.beginAnswer();
            first.append("first output\n");
            first.finish();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, occurrencesOf(doc, "shell history line"),
                    "starting a session must not erase the screen it inherited:\n" + dump(doc));
            assertEquals(1, occurrencesOf(doc, "first output"), dump(doc));
        }
    }

    /**
     * Height alone — the case every previous attempt got wrong.
     *
     * <p>A height-only change was measured shifting the cursor by one row through a ghost line in
     * the display's own line model, and that silent shift was what made each <em>subsequent</em>
     * width change erase one row short. Under an epoch boundary the dimensions are not classified at
     * all: any observed change ends the epoch, so there is no interaction left to be right about.
     */
    @Test
    void aSingleHeightChangeLeavesExactlyTheReplayWindow() throws Exception {
        assertOneGeometryChangeLeavesExactlyTheReplayWindow(60, 8);
    }

    /** Growing in height alone — the budget grows with it, so more of the log comes back. */
    @Test
    void growingTheHeightAloneLeavesExactlyTheReplayWindow() throws Exception {
        assertOneGeometryChangeLeavesExactlyTheReplayWindow(60, 14);
    }

    /** Both dimensions at once, which is what a zoom step actually does. */
    @Test
    void aSimultaneousWidthAndHeightChangeLeavesExactlyTheReplayWindow() throws Exception {
        assertOneGeometryChangeLeavesExactlyTheReplayWindow(44, 8);
    }

    /**
     * A terminal that never delivers SIGWINCH still gets its resize handled correctly.
     *
     * <p>The signal is only a wake-up hint (ADR 0010 amendment A3c), so removing it entirely must
     * cost latency and nothing else: the sampler's slow heartbeat observes the new geometry, the
     * epoch ends, and the screen ends up holding exactly the [[bounded replay window]] at the new
     * width — the same document the signalled path produces.
     */
    @Test
    void aGeometryChangeWithNoSignalAtAllIsStillObservedAndRepainted() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 6; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            rig.resizeWithoutAnySignal(44, 10);
            rig.settle();

            List<String> screen = trimmedDocument(rig.document());
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                expected.add("item " + i);
                expected.add("");
            }
            assertEquals(expected, screen.subList(0, Math.max(0, screen.size() - 2)),
                    "an unsignalled geometry change must leave exactly the replay window:\n"
                            + dump(screen));
            assertTrue(screen.get(screen.size() - 2).contains(DOCK_TOP), dump(screen));
            assertTrue(screen.get(screen.size() - 1).contains(DOCK_BOTTOM), dump(screen));
        }
    }

    private void assertOneGeometryChangeLeavesExactlyTheReplayWindow(int newWidth, int newHeight)
            throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 20; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            rig.resize(newWidth, newHeight);
            rig.pump.pump();
            rig.settle();

            List<String> screen = trimmedDocument(rig.document());
            // Three screenfuls of the *new* height, filled backwards from the newest item; each item
            // is one line plus its single separator blank, so the budget buys half as many items as
            // it has rows. Which items survive is width- and height-dependent by design: the bound
            // exists to cap repaint cost, not to preserve a fixed amount of history.
            int survivors = Math.min(20, (3 * newHeight + 1) / 2);
            List<String> expected = new ArrayList<>();
            for (int i = 20 - survivors; i < 20; i++) {
                expected.add("item " + i);
                expected.add("");
            }
            assertEquals(expected, screen.subList(0, Math.max(0, screen.size() - 2)),
                    "the terminal must hold exactly the replay window after a geometry change:\n"
                            + dump(screen));
            assertEquals(2, screen.size() - expected.size(),
                    "only the live region may follow the replay window:\n" + dump(screen));
            assertTrue(screen.get(screen.size() - 2).contains(DOCK_TOP), dump(screen));
            assertTrue(screen.get(screen.size() - 1).contains(DOCK_BOTTOM), dump(screen));
        }
    }

    /**
     * A resize must never cost the user their draft, and must never cost them the ability to type.
     *
     * <p>The [[输入框]] is a permanent entry of the [[活区]], so it is never committed and therefore
     * never in the [[bounded replay window]]; it is composed fresh at the new geometry like the rest
     * of the live region. What this pins is the user-visible consequence: the half-written line is
     * still there afterwards, exactly once, and the next keystroke still lands in it.
     */
    @Test
    void typedButUnsubmittedTextSurvivesAResizeAndTheBoxStaysEditable() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            AtomicReference<String> typed = new AtomicReference<>("half a thought");
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> " + typed.get())),
                    2 + typed.get().length(), rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var committed = r.beginAnswer();
            committed.append("already committed\n");
            committed.finish();
            rig.pump.pump();

            rig.resize(44, 10);
            rig.pump.pump();
            rig.settle();

            List<String> afterResize = rig.document();
            assertEquals(1, occurrencesOf(afterResize, "> half a thought"),
                    "a resize must not lose or duplicate the user's draft:\n" + dump(afterResize));

            // The next keystroke still reaches the box, and redraws it in place rather than
            // stacking a second copy.
            typed.set("half a thought completed");
            r.beforeInput();
            rig.pump.pump();

            List<String> afterTyping = rig.document();
            assertEquals(1, occurrencesOf(afterTyping, "> half a thought completed"),
                    "the input box must still be editable after a reset:\n" + dump(afterTyping));
            assertEquals(0, occurrencesOf(afterTyping, "> half a thought"),
                    "the earlier draft must be repainted in place, not left behind:\n"
                            + dump(afterTyping));
        }
    }

    /**
     * The [[输入框]] reads as a box on the user's actual screen (issue 09, ADR 0016 D6).
     *
     * <p>This is the only place the rule can be asserted as the glyph it is. JLine never writes
     * {@code U+2500} verbatim: {@code AttributedCharSequence.toAnsi(Terminal)} switches the terminal
     * into DEC Special Graphics and sends {@code q}. So a byte- or frame-level assertion would be
     * asserting an encoding, while the screen model decodes the charset designation exactly as
     * Terminal.app does and gives back what the user sees.
     */
    @Test
    void theInputBoxIsARuleTheInputRowAndARuleOnTheUsersScreen() throws Exception {
        try (Rig rig = new Rig(80, 24)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> " + INPUT_TEXT)),
                    2 + INPUT_TEXT.length(), rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.stream().println("some transcript");
            rig.pump.pump();

            List<String> screen = trimmedDocument(rig.document());
            int at = -1;
            for (int i = 0; i < screen.size(); i++) {
                if (screen.get(i).contains("> " + INPUT_TEXT)) {
                    at = i;
                }
            }
            assertTrue(at > 0, dump(screen));
            String rule = "─".repeat(79);
            assertEquals(rule, screen.get(at - 1).stripTrailing(),
                    "the input row is bracketed by a full-width rule above:\n" + dump(screen));
            assertEquals(rule, screen.get(at + 1).stripTrailing(),
                    "…and one below:\n" + dump(screen));
            assertTrue(screen.get(at + 2).contains(DOCK_TOP),
                    "the dock still follows the box:\n" + dump(screen));
            for (String row : screen) {
                assertTrue(row.indexOf('│') < 0 && row.indexOf('╭') < 0,
                        "the box has no vertical borders (ADR 0016 D6):\n" + dump(screen));
            }
        }
    }

    /**
     * A captured [[输入框]] frame from another width composes <b>nothing</b>, not stale rows
     * (ADR 0016 D4).
     *
     * <p>The cosmetic half — one frame of input wrapped for the previous width — is the lesser
     * problem. The real one is that the number of input rows is what the live tail's budget is
     * subtracted from: a draft captured as one row at 120 columns needs three at 40, so a budget
     * derived from the stale count lets the tail overrun the box and the dock. That is a geometry
     * error inside the region the epoch model exists to keep clean, and no byte assertion can see it.
     *
     * <p>The stale capture is not hypothetical after issue 07. Geometry is <em>sampled, not
     * signalled</em> (A3c): on the heartbeat path the sampler observes a change from a terminal that
     * sent nothing, so the line editor never redisplays and no corrective capture arrives until the
     * next keystroke. Before issue 07 this was idle-only, because {@code doCleanup} cleared the
     * capture whenever a turn took the terminal; with the reader resident, every reset during a turn
     * is exposed.
     */
    @Test
    void anInputFrameCapturedAtAnotherWidthComposesNothingAndCannotOverrunTheDock() throws Exception {
        try (Rig rig = new Rig(120, 12)) {
            InlineRenderer r = rig.renderer;
            // The capture models what JLine hands over: one row at 120 columns, three at 40.
            AtomicReference<Integer> capturedAt = new AtomicReference<>(120);
            rig.renderer.liveRegion().bindInputBox(() -> capturedAt.get() >= 120
                    ? new CaptureDisplay.Frame(
                            List.of(new AttributedString("> " + "draft ".repeat(18))),
                            110, 120, false)
                    : new CaptureDisplay.Frame(
                            List.of(new AttributedString("> " + "draft ".repeat(6)),
                                    new AttributedString("draft ".repeat(6)),
                                    new AttributedString("draft ".repeat(6))),
                            110, 40, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 8; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            // A terminal that raises no signal: nothing asks the line editor to lay the box out
            // again, so the capture keeps claiming the old width.
            rig.resizeWithoutAnySignal(40, 12);
            rig.settle();

            List<String> screen = trimmedDocument(rig.document());
            assertEquals(0, rowsWith(screen, "draft"),
                    "a capture from another width must compose nothing, not rows laid out for it:\n"
                            + dump(screen));
            // Issue 09: the chrome obeys the same rule. Two rules with nothing between them would be
            // an empty shell, so the whole box goes away together and comes back together.
            assertEquals(0, rowsWith(screen, "───"),
                    "no rules and no wash without the rows they decorate:\n" + dump(screen));
            assertTrue(screen.get(screen.size() - 2).contains(DOCK_TOP),
                    "the live tail must not overrun the dock:\n" + dump(screen));
            assertTrue(screen.get(screen.size() - 1).contains(DOCK_BOTTOM),
                    "the live tail must not overrun the dock:\n" + dump(screen));

            // Bounded and self-resolving: the next capture at the current width brings the box back.
            capturedAt.set(40);
            r.beforeInput();
            rig.pump.drain();

            List<String> healed = trimmedDocument(rig.document());
            assertEquals(3, rowsWith(healed, "draft"),
                    "the next keystroke must bring the input box back, laid out for this width:\n"
                            + dump(healed));
            assertEquals(1, rowsWith(healed, "───"),
                    "…and the rule comes back with the rows it brackets (12 rows: top rule only):\n"
                            + dump(healed));
            assertTrue(healed.get(healed.size() - 1).contains(DOCK_BOTTOM),
                    "and the dock is still the bottom of the live region:\n" + dump(healed));
        }
    }

    /**
     * Every [[活区]] row is a line of its own <em>as the terminal itself counts lines</em> — not just
     * as a rectangle of cells.
     *
     * <p>The two are different things and only one of them is what the user gets back. A terminal
     * distinguishes a row that ended because the writer ended it from a row that ended because it ran
     * out of columns; the second is a <b>soft-wrap continuation</b> of the first, and the pair is one
     * logical line. Selecting it, copying it, and searching it all operate on the logical line, which
     * is why this assertion reads {@code historyText()} — the serialization Terminal.app's
     * {@code history of tab} produces, i.e. what a copy yields — rather than the cell grid, which
     * cannot tell the two apart.
     *
     * <p>Nothing in the live region is naturally full-width, so nothing here should soft-wrap. If two
     * live rows come back as one line, the region was stitched together by auto-wrap.
     *
     * <p>This is the surviving justification for terminating every live row (ADR 0010 amendment A17,
     * issue 10). It makes no claim about how far anything is erased and no prediction about how a
     * terminal re-flows: it is a statement about the region the renderer just painted, within one
     * [[geometry epoch]].
     */
    @Test
    void everyLiveRegionRowIsItsOwnLineInWhatTheTerminalGivesBack() throws Exception {
        try (Rig rig = new Rig(60, 12)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> explain the resize bug")), 24, rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var a = r.beginAnswer();
            a.append("committed transcript row\n");
            a.finish();
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();
            rig.pump.pump();

            // Each row is on screen exactly once — true under both regimes, and the reason the cell
            // grid alone cannot arbitrate this. The defect lives one level up, in line structure.
            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"), dump(doc));
            assertEquals(1, rowsContaining(doc, INPUT_TEXT), dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP), dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM), dump(doc));

            List<String> copied = List.of(rig.historyText().split("\n", -1));
            assertLiveRowsAreSeparateLines(copied, doc);
        }
    }

    /**
     * The same property on the frame that paints the whole region at once. Which rows get stitched by
     * auto-wrap otherwise depends on what the previous frame left in the display's line model, so a
     * region painted from scratch is the case where every row is a candidate.
     */
    @Test
    void aLiveRegionPaintedFromScratchIsAlsoOneLinePerRow() throws Exception {
        try (Rig rig = new Rig(60, 12)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> explain the resize bug")), 24, rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.beginThinking("Thinking");
            rig.pump.pump();

            assertLiveRowsAreSeparateLines(
                    List.of(rig.historyText().split("\n", -1)), rig.document());
        }
    }

    /** No line the user could copy may hold two different live rows. */
    private static void assertLiveRowsAreSeparateLines(List<String> copied, List<String> doc) {
        String[] rows = {"esc to cancel", INPUT_TEXT, DOCK_TOP, DOCK_BOTTOM};
        for (int i = 0; i < rows.length; i++) {
            for (int j = i + 1; j < rows.length; j++) {
                for (String line : copied) {
                    assertTrue(!line.contains(rows[i]) || !line.contains(rows[j]),
                            "live rows '" + rows[i] + "' and '" + rows[j] + "' came back as one "
                                    + "soft-wrapped line, so the terminal joined the live region "
                                    + "instead of keeping one line per row:\n[" + line + "]\n"
                                    + dump(doc));
                }
            }
        }
    }

    /** The document with trailing whitespace and trailing blank rows removed. */
    private static List<String> trimmedDocument(List<String> document) {
        List<String> rows = new ArrayList<>(document.stream().map(String::stripTrailing).toList());
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isBlank()) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /**
     * Dragging the window edge one column at a time — the gesture that four rounds of erase-distance
     * arithmetic failed to survive.
     *
     * <p>This test asserts the <b>inverse</b> of what it asserted before ADR 0010 amendment A4/A16.
     * It used to demand that no transcript row was destroyed; under a [[destructive resize reset]]
     * every row outside the [[bounded replay window]] is <em>supposed</em> to be destroyed, and the
     * old assertion would block a correct implementation. What survives a geometry change is exactly
     * the replay window: the newest items rendered once at the new width, everything older gone from
     * both the screen and the terminal's saved scrollback.
     *
     * <p>What has not changed is the copy half: a gesture of many small steps must not accumulate
     * spinner / dock / input duplicates. Destroying old rows is the contract; duplicating live rows
     * is still the defect.
     */
    @Test
    void aSlowWindowDragLeavesExactlyTheReplayWindow() throws Exception {
        assertSlowDragLeavesExactlyTheReplayWindow(false);
    }

    /** The same drag with the input box bound — the real interactive shape (ADR 0014 决策 2). */
    @Test
    void aSlowWindowDragWithTheInputLineBoundLeavesExactlyTheReplayWindow() throws Exception {
        assertSlowDragLeavesExactlyTheReplayWindow(true);
    }

    /**
     * A hand drag long enough to owe a keep-alive repaint, ending right after it is paid.
     *
     * <p>This is the shape the two <b>incremental</b> storm patterns have and the step patterns do
     * not (measured from {@code scripts/resize-storm.sh}): {@code drag} and {@code heightdrag} step
     * every 50 ms, under the 100 ms quiet interval, so a sustained sweep never settles and crosses
     * the 500 ms max defer instead. {@code zoom} / {@code height} step every 450 ms and {@code storm}
     * every 120 ms — each of their steps settles on its own — and the authentic fullscreen animation
     * is over in ~275 ms, below the max defer. So only these two ever put a full repaint on the
     * screen while the gate is still closed, and only they can hand the <em>next</em> erase a screen
     * with a [[活区]] on it.
     *
     * <p>What that cost, measured 2026-08-21 in Terminal.app: {@code CSI 2 J} pushes the visible
     * screen into the saved scrollback before blanking it, so an erase ordered
     * {@code 3J 2J H} wipes the scrollback and then immediately deposits the screen back into it.
     * Eight {@code heightdrag 6} runs deposited one screenful each; in one of them that screenful was
     * the keep-alive repaint, spinner and dock included — the user-reported failure, reproduced with
     * the same history line numbers.
     */
    @Test
    void aDragThatPaysAKeepAliveRepaintLeavesNoCopyOfItWhenItSettles() throws Exception {
        try (Rig rig = new Rig(100, 12)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> " + INPUT_TEXT)),
                    2 + INPUT_TEXT.length(), rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 30; i++) {
                var line = r.beginAnswer();
                line.append("transcript line " + i + "\n");
                line.finish();
            }
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();

            // Eleven steps at the measured 50 ms hand-drag cadence: the first establishes the blank,
            // the eleventh is 500 ms later and is the one the keep-alive repaint rides out on. The
            // drag then ends, so nothing erases that repaint away before the gesture settles.
            int width = 100;
            for (int step = 0; step < 11; step++) {
                rig.resize(--width, 12, 50);
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "the keep-alive repaint must not survive as a spinner copy in scrollback:\n"
                            + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "…nor as a status-dock copy:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM), dump(doc));
            assertEquals(1, rowsContaining(doc, INPUT_TEXT),
                    "…nor as an input-box copy:\n" + dump(doc));
            for (int i = 15; i < 30; i++) {
                String line = "transcript line " + i;
                assertEquals(1, occurrencesOf(doc, line),
                        "the replay window must be painted exactly once ('" + line + "'):\n"
                                + dump(doc));
            }
        }
    }

    /**
     * 动画式缩放（原生全屏 / 绿键最大化）：**高度与宽度交替各变一步**，而不是只变宽。
     *
     * <p>这是此前整套 resize 回归缺掉的那一维——`zoom` / `drag` / 上面两条慢拖全都把高度钉死，于是
     * 全绿的同时真机 `fullscreen` 每次留上百份副本。真机 trace 逐事件回放显示动画就是这个形状：
     * {@code 186x54 → 186x55}（只变高）紧接 {@code 186x55 → 187x55}（只变宽），如此几十步。
     *
     * <p>致命的是这两步的**顺序**。只变高的那一步不触发擦除（{@code LiveRegionStrip} 只跟踪
     * {@code displayColumns}），却会让 {@code Display.resize} 改写 JLine 的行模型并在下一帧吐出一个
     * 裸 {@code CR LF}，把光标推到活区**最后一行的下一行**；紧接着只变宽的那一步按「光标在最后一行」
     * 算擦除距离，于是每次都少擦一行，活区顶端的 spinner 行被留在屏上——每个来回铸出一份副本。
     */
    @Test
    void anAnimatedResizeSteppingHeightThenWidthLeavesNoCopies() throws Exception {
        assertAlternatingResizeIsClean(true, false);
    }

    /** 同一个动画，步序相反——擦除与幽灵行谁先发生不该改变结论。 */
    @Test
    void anAnimatedResizeSteppingWidthThenHeightLeavesNoCopies() throws Exception {
        assertAlternatingResizeIsClean(false, true);
    }

    /**
     * 同一个动画，但活区里挂着输入行——这才是产品实际的形态（ADR 0014 决策 2：输入行是活区的常驻
     * 条目）。缩放风暴的 repro driver 从不调 {@code readLine}，因此上面两条走的是 {@code lastCursorPos
     * < 0} 那条分支；这一条走带真实光标位置的分支。
     */
    @Test
    void anAnimatedResizeWithTheInputLineBoundLeavesNoCopies() throws Exception {
        assertAlternatingResizeIsClean(true, true);
    }

    /**
     * The gesture the user actually reports: native fullscreen / green-button maximise.
     *
     * <p>This replays the <b>measured shape</b> of that animation rather than an invented one
     * (measured 2026-08-13, 3 × {@code fullscreen 6} captures at 1 ms sampling): 22 dimension changes
     * over roughly 275 ms, alternating one height step and one width step, at a p50 inter-change gap
     * of 8.8 ms. ~275 ms is a fixed macOS animation constant, and it sits below the 500 ms max defer,
     * so the authentic gesture pays no mid-animation repaint at all.
     *
     * <p>Two properties are asserted together, which is what makes this the criterion test for the
     * whole slice. During the gesture the renderer writes <b>no content</b> — so the terminal cannot be
     * holding a queue of stale repaints, by construction rather than by timing argument. And once the
     * geometry settles, the screen holds the [[bounded replay window]] exactly once over: no spinner
     * copy, no dock copy, no [[输入框]] copy, and no transcript row lost or doubled.
     */
    @Test
    void theMeasuredFullscreenGestureWritesNoContentAndLeavesNoResidualRows() throws Exception {
        try (Rig rig = new Rig(100, 30)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> " + INPUT_TEXT)),
                    2 + INPUT_TEXT.length(), rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var filler = r.beginAnswer();
            for (int i = 0; i < 20; i++) {
                filler.append("transcript line " + i + "\n");
            }
            filler.finish();
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();

            int beforeGesture = rig.writtenBytes();
            // Entering fullscreen: the window grows one height step and one width step at a time.
            int width = 100;
            int height = 30;
            for (int step = 0; step < 11; step++) {
                rig.resize(width, ++height);
                rig.pump.pump();
                rig.resize(width += 3, height);
                rig.pump.pump();
            }
            int afterGesture = rig.writtenBytes();

            assertEquals("", TerminalBytes.displayedCharacters(
                            rig.bytesBetween(beforeGesture, afterGesture)),
                    "the whole animation must write erases and no content: "
                            + rig.bytesBetween(beforeGesture, afterGesture));

            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "the settled fullscreen gesture must leave exactly one spinner row:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "…and exactly one status-dock header row:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM), dump(doc));
            assertEquals(1, rowsContaining(doc, INPUT_TEXT),
                    "…and exactly one input row:\n" + dump(doc));
            for (int i = 0; i < 20; i++) {
                String line = "transcript line " + i;
                assertEquals(1, occurrencesOf(doc, line),
                        "the replay window must come back exactly once ('" + line + "'):\n" + dump(doc));
            }
        }
    }

    /**
     * A modal spanning a resize: the [[转写浏览层]] is open, the user resizes the window, and closes it.
     *
     * <p>Two properties, and the first is the defect A12 closes. While the modal owns the terminal not
     * one byte of the [[活区]] reaches it — before the lease existed a resize posted a frame regardless
     * of idleness, so the erase, the [[bounded replay window]] and the dock were all painted onto the
     * alternate screen the modal was using, and the primary screen the terminal then restored had just
     * been erased. Second, the return pays the deferred reset exactly once, so the screen ends up
     * holding the replay window at the <b>new</b> geometry and nothing else.
     */
    @Test
    void aModalSpanningAResizeWritesNothingAndLeavesExactlyTheReplayWindowOnReturn() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 20; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            // The modal takes the terminal, exactly as opening the overlay does.
            rig.renderer.liveRegion().acquireModalLease();
            int whileLeased = rig.writtenBytes();
            rig.resize(44, 8);
            rig.pump.drain();
            rig.clock.advance(TurbulenceGate.DEFAULT_QUIET_MILLIS + 1);
            rig.pump.drain();
            rig.clock.advance(TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS * 4);
            rig.pump.drain();
            assertEquals(whileLeased, rig.writtenBytes(),
                    "a modal owns the terminal: the live region may not write a single byte, even "
                            + "across a resize and a keep-alive interval:\n"
                            + rig.bytesBetween(whileLeased, rig.writtenBytes()));

            rig.renderer.liveRegion().releaseModalLease();
            rig.settle();

            List<String> screen = trimmedDocument(rig.document());
            int survivors = Math.min(20, (3 * 8 + 1) / 2);
            List<String> expected = new ArrayList<>();
            for (int i = 20 - survivors; i < 20; i++) {
                expected.add("item " + i);
                expected.add("");
            }
            assertEquals(expected, screen.subList(0, Math.max(0, screen.size() - 2)),
                    "returning from a modal that spanned a resize must leave exactly the replay "
                            + "window at the new geometry:\n" + dump(screen));
            assertEquals(2, screen.size() - expected.size(),
                    "only the live region may follow the replay window:\n" + dump(screen));
            assertTrue(screen.get(screen.size() - 2).contains(DOCK_TOP), dump(screen));
            assertTrue(screen.get(screen.size() - 1).contains(DOCK_BOTTOM), dump(screen));
        }
    }

    /**
     * The price of the [[destructive resize reset]] and its compensation, in one run.
     *
     * <p>A reset erases the terminal's saved scrollback, so output older than the
     * [[bounded replay window]] genuinely leaves the terminal — that is the stated cost (A4), and the
     * first half of this asserts it against the screen. It is not <b>destroyed</b>, though: retention
     * is the whole session and the [[转写浏览层]] is where a user reaches it (A10). Without that second
     * half a resize would simply be amnesia, which is why the two belong in the same test.
     */
    @Test
    void whatTheResetErasedFromTheTerminalIsStillReachableInTheOverlay() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 40; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            rig.resize(60, 8);
            rig.pump.pump();
            rig.settle();

            List<String> screen = rig.document();
            assertEquals(0, occurrencesOf(screen, "item 0"),
                    "the oldest output is outside the replay window and the reset erased it:\n"
                            + dump(screen));
            assertEquals(1, occurrencesOf(screen, "item 39"), dump(screen));

            // Ctrl+O: the overlay opens over the retained session and closes on the first key.
            rig.type("q");
            r.openTranscript();

            String browsed = rig.bytesBetween(0, rig.writtenBytes());
            assertTrue(TerminalBytes.displayedCharacters(browsed).contains("item0"),
                    "output the reset erased must still be reachable in the overlay");
        }
    }

    /**
     * The other half of the deferred reset: <b>none</b> fires when the geometry did not change while
     * the modal was open.
     *
     * <p>Asserted as what the user keeps rather than as an absence of bytes: a reset erases the
     * terminal's saved scrollback, so a spurious one would destroy the transcript rows that live above
     * the [[bounded replay window]]. Forty items at a ten-row height put most of them well outside the
     * budget, so they survive only if nothing repainted.
     */
    @Test
    void closingAModalOnAnUnchangedGeometryKeepsTheScrollbackAboveTheReplayWindow() throws Exception {
        try (Rig rig = new Rig(60, 10)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 40; i++) {
                var item = r.beginAnswer();
                item.append("item " + i + "\n");
                item.finish();
            }
            rig.pump.pump();

            rig.renderer.liveRegion().acquireModalLease();
            rig.clock.advance(TurbulenceGate.DEFAULT_MAX_DEFER_MILLIS * 4);
            rig.pump.drain();
            rig.renderer.liveRegion().releaseModalLease();
            rig.settle();

            List<String> doc = rig.document();
            for (int i = 0; i < 40; i++) {
                assertEquals(1, occurrencesOf(doc, "item " + i),
                        "closing a modal without a resize must not erase scrollback ('item " + i
                                + "'):\n" + dump(doc));
            }
        }
    }

    /**
     * The commit path this rig never exercised, and the one the real-terminal storm harness builds
     * its entire transcript from: generic streamed output through
     * {@link com.cafecli.render.Renderer#stream()} rather than typed blocks through
     * {@code beginAnswer()}.
     *
     * <p>Every other geometry assertion in this class builds its transcript out of
     * {@code AnswerSegment}s, so the [[bounded replay window]] was only ever proven over typed
     * blocks — and that is exactly why the 2026-08-17 real-terminal pass could report
     * {@code spinner=1 dock=1 replay=0/60 FAIL} against a fully green offline suite. Streamed text
     * did not enter the ordered commit log at all, so the erase half of the
     * [[destructive resize reset]] ran and the redraw had nothing to bring back.
     *
     * <p>The gesture is the measured fullscreen animation (22 dimension changes, alternating one
     * height step and one width step), because that is the gesture the failure was measured under;
     * the transcript lines are the driver's own shape, ASCII marker plus CJK body, so the wrap
     * boundary is the wcwidth one rather than a byte one.
     */
    @Test
    void transcriptStreamedThroughTheRendererSurvivesTheFullscreenGesture() throws Exception {
        try (Rig rig = new Rig(100, 30)) {
            InlineRenderer r = rig.renderer;
            rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                    List.of(new AttributedString("> " + INPUT_TEXT)),
                    2 + INPUT_TEXT.length(), rig.liveWidth, false));
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            for (int i = 0; i < 20; i++) {
                r.stream().println("transcript line " + i + " —— 这一行用来把屏幕铺满。");
            }
            rig.pump.pump();
            r.beginTurn();
            r.beginThinking("Thinking");
            rig.pump.pump();

            int width = 100;
            int height = 30;
            for (int step = 0; step < 11; step++) {
                rig.resize(width, ++height);
                rig.pump.pump();
                rig.resize(width += 3, height);
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            for (int i = 0; i < 20; i++) {
                String line = "transcript line " + i + " ";
                assertEquals(1, rowsContaining(doc, line),
                        "streamed transcript must come back exactly once after the gesture ('"
                                + line.strip() + "'):\n" + dump(doc));
            }
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "…without leaving a spinner copy:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "…or a status-dock copy:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, INPUT_TEXT),
                    "…or an input-box copy:\n" + dump(doc));
        }
    }

    /**
     * A [[destructive resize reset]] repaints from the ordered commit log, so the log's order
     * <b>is</b> the order the user reads after any geometry change. This pins the consequence
     * directly: the sequence of committed rows on screen after a resize is the sequence that was on
     * screen before it.
     *
     * <p>It deliberately asserts an invariant rather than a chosen order. Which order is "right" for
     * a streamed notice against a typed block that has not settled yet is a judgement — a block is
     * committed when it settles, not when its content was produced — but the screen and the log
     * disagreeing is never right, because that disagreement is exactly what a resize makes visible.
     *
     * <p>Streamed text and typed blocks are interleaved inside a single frame, which is where the two
     * used to diverge: the strip wrote frozen block rows before external text regardless of which was
     * committed first, while the log recorded them in commit order.
     */
    @Test
    void aResizeDoesNotReorderInterleavedStreamedTextAndTypedBlocks() throws Exception {
        try (Rig rig = new Rig(60, 20)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            r.stream().println("notice one");
            var a1 = r.beginAnswer();
            a1.append("answer one\n");
            a1.finish();
            r.stream().println("notice two");
            var a2 = r.beginAnswer();
            a2.append("answer two\n");
            a2.finish();
            r.endTurn();
            rig.pump.pump();

            List<String> before = transcriptOrder(rig.document());
            assertEquals(4, before.size(),
                    "all four committed rows must be on screen before the resize:\n"
                            + dump(rig.document()));

            rig.resize(52, 20);
            rig.pump.pump();
            rig.settle();

            assertEquals(before, transcriptOrder(rig.document()),
                    "a resize must repaint committed output in the order it was written:\n"
                            + dump(rig.document()));
        }
    }

    /**
     * The same invariant with a [[转向消息]] landing in the middle of a streaming answer (issue 10).
     *
     * <p>This is the case a source-span log position exists for (ADR 0016 D5): the correction is
     * committed while the answer is still growing, so the answer occupies two positions with the echo
     * between them. Two things have to hold at once and they pull in opposite directions — the reset
     * must repaint the three in the order they were written, <b>and</b> it must not gain or lose a row
     * doing it, which is what a position that is a span rather than an entry makes possible.
     */
    @Test
    void aResizeDoesNotReorderAnAnswerInterruptedByASteeringMessage() throws Exception {
        try (Rig rig = new Rig(60, 20)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("answer before\n\n");
            rig.pump.pump();
            r.printSteeringPrompt("steer me now");
            rig.pump.pump();
            answer.append("answer after\n\n");
            answer.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> sentinels = List.of("answer before", "steer me now", "answer after");
            List<String> before = committedOrder(rig.document(), sentinels);
            assertEquals(sentinels, before,
                    "the correction must be on screen between the two halves of the answer:\n"
                            + dump(rig.document()));

            rig.resize(48, 20);
            rig.pump.pump();
            rig.settle();

            assertEquals(before, committedOrder(rig.document(), sentinels),
                    "a resize must repaint committed output in the order it was written:\n"
                            + dump(rig.document()));
        }
    }

    /**
     * The same invariant with one frame per step, which is the cadence a real session runs at: each
     * notice and each block is committed by its own frame, so the reset has to preserve an order that
     * spans frames rather than one established inside a single frame.
     */
    @Test
    void aResizeDoesNotReorderOutputCommittedOneFrameAtATime() throws Exception {
        try (Rig rig = new Rig(60, 20)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 3; i++) {
                r.stream().println("notice " + i);
                rig.pump.pump();
                var answer = r.beginAnswer();
                answer.append("answer " + i + "\n");
                answer.finish();
                rig.pump.pump();
            }
            r.endTurn();
            rig.pump.pump();

            List<String> before = transcriptOrder(rig.document());
            assertEquals(6, before.size(), dump(rig.document()));

            rig.resize(48, 20);
            rig.pump.pump();
            rig.settle();

            assertEquals(before, transcriptOrder(rig.document()),
                    "a resize must repaint committed output in the order it was written:\n"
                            + dump(rig.document()));
        }
    }

    /**
     * The case that decides the whole coverage slice (ADR 0010 amendment A8): a background task
     * finishes, prints its completion notice above the input while the user is looking elsewhere, and
     * the window is then resized. Before the ordered commit log covered generic streamed output, the
     * eager erase took the notice and the deferred redraw had nothing to bring it back from — the user
     * could simply never learn the task had finished.
     */
    @Test
    void aBackgroundCompletionNoticeSurvivesAResizeRightAfterItIsPrinted() throws Exception {
        try (Rig rig = new Rig(72, 12)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.stream().println("✅ task_2 完成：3 files updated");
            rig.pump.pump();

            rig.resize(56, 12);
            rig.pump.pump();
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "task_2 完成"),
                    "a background completion notice must survive the resize that follows it:\n"
                            + dump(doc));
        }
    }

    /**
     * The same notice, but the resize lands <b>before</b> any frame has written it. During
     * [[geometry turbulence]] the gate writes no content at all, so the notice exists only in the
     * ordered commit log until the geometry settles — which is exactly why committing is above the gate
     * and never gated (ADR 0010 amendment A3b).
     */
    @Test
    void aBackgroundCompletionNoticePrintedDuringTurbulenceStillArrives() throws Exception {
        try (Rig rig = new Rig(72, 12)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            rig.pump.pump();

            rig.resize(64, 12);
            r.stream().println("✅ task_7 完成：while the window was moving");
            rig.resize(56, 12);
            rig.pump.pump();
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "task_7 完成"),
                    "output produced while the screen was deliberately blank must appear in the "
                            + "redraw that ends the turbulence:\n" + dump(doc));
        }
    }

    /**
     * Committed generic streamed output is still on screen after a resize when it fits the row budget
     * (ADR 0010 amendment A9) — including lines longer than the new width, which the terminal
     * soft-wraps rather than clipping, so the content is whole even though it occupies two rows.
     */
    @Test
    void committedStreamedOutputSurvivesNarrowingWhenItFitsTheRowBudget() throws Exception {
        try (Rig rig = new Rig(100, 14)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            for (int i = 0; i < 8; i++) {
                r.stream().println("notice " + i + " " + "y".repeat(70));
            }
            rig.pump.pump();

            // Narrow to well under the committed line length: 8 lines × 2 rows is inside the
            // 3 × 14 = 42-row budget, so every one of them is supposed to come back.
            rig.resize(60, 14);
            rig.pump.pump();
            rig.settle();

            String history = rig.historyText();
            for (int i = 0; i < 8; i++) {
                String line = "notice " + i + " " + "y".repeat(70);
                assertEquals(1, countOccurrences(history, line),
                        "committed streamed output must survive narrowing whole ('notice " + i
                                + "'):\n" + dump(rig.document()));
            }
        }
    }

    /**
     * The [[提交块]] is retained as its source text, not as rows baked at the width it was submitted
     * at, so a reset re-renders it for the geometry it is being repainted into.
     *
     * <p>The visible consequence is the one row it is supposed to be. The block's dim wash is padded
     * out to the full terminal width, so a row baked at 100 columns and repainted at 60 leaves a
     * 40-column stub of wash on a second row — the ragged half row that was an accepted cost while
     * [[冻结回卷]] was never reflowed, and is not one now that a reset repaints from source.
     */
    @Test
    void theSubmittedPromptEchoIsRepaintedAtTheNewWidthNotTheOneItWasSubmittedAt() throws Exception {
        try (Rig rig = new Rig(100, 14)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.appendUserPrompt("resize me now");
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("answer body\n");
            answer.finish();
            rig.pump.pump();
            rig.pump.pump();

            assertEquals(0, blankRowsBetween(rig.document(), "resize me now", "answer body"),
                    "a one-line submission occupies one row to begin with:\n" + dump(rig.document()));

            rig.resize(60, 14);
            rig.pump.pump();
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(0, blankRowsBetween(doc, "resize me now", "answer body"),
                    "the submitted prompt must be repainted at the new width, with no ragged half row "
                            + "of wash left over from the old one:\n" + dump(doc));
        }
    }

    /**
     * The same for a [[转向消息]]'s echo (issue 10). It is the same source-form [[提交块]], and it is
     * the one that lands mid-turn — so it is the one most likely to still be on screen when the user
     * grabs the window edge.
     */
    @Test
    void theSteeringEchoIsAlsoRepaintedAtTheNewWidthNotTheOneItWasSubmittedAt() throws Exception {
        try (Rig rig = new Rig(100, 14)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            answer.append("answer body\n\n");
            rig.pump.pump();
            r.printSteeringPrompt("correct yourself now");
            rig.pump.pump();
            answer.append("after the correction\n\n");
            answer.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            assertEquals(0,
                    blankRowsBetween(rig.document(), "correct yourself now", "after the correction"),
                    "a one-line correction occupies one row to begin with:\n" + dump(rig.document()));

            rig.resize(60, 14);
            rig.pump.pump();
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(0, blankRowsBetween(doc, "correct yourself now", "after the correction"),
                    "the correction must be repainted at the new width, with no ragged half row of "
                            + "wash left over from the old one:\n" + dump(doc));
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    // --- the freeze boundary lives in source coordinates (ADR 0010 amendment A11) ------------------

    /**
     * One answer, streamed in four chunks, so a resize can land between any two of them.
     *
     * <p>The table is deliberately three columns wide. <b>Measured 2026-08-19:</b> the styled-line
     * count of a source is width-independent for prose — {@code TerminalMarkdownRenderer} folds prose
     * into rows later, outside that count — and width-dependent for a table, whose row height is its
     * tallest wrapped cell (this table: 13 styled lines at width 40, 7 at 120). A two-column table with
     * long cells would not do, because it degrades into a width-independent key/value form. So a table
     * is not one case among several for the freeze boundary; it is the case.
     */
    private static final String[] ANSWER_CHUNKS = {
        "sentence one, before any geometry change\n\n",
        """
        | stage | what happens | why it matters |
        |---|---|---|
        | erase | the visible screen and saved scrollback both go | it is geometry independent |
        """,
        """
        | redraw | the replay window comes back at the new width | it is the only wrong-able half |

        """,
        "sentence two, after every geometry change\n\n",
        "sentence three, after the last one of them\n\n",
    };

    /**
     * Stream {@link #ANSWER_CHUNKS} at {@code startWidth}, resizing to {@code widthAfterChunk[i]}
     * (when non-zero) once chunk {@code i} has been committed, and return the answer as it ends up on
     * screen — the whole document minus the live-region furniture, which is composed fresh every frame
     * and is not part of the answer.
     */
    private List<String> streamedAnswerOnScreen(int startWidth, int[] widthAfterChunk)
            throws Exception {
        try (Rig rig = new Rig(startWidth, 30)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var answer = r.beginAnswer();
            for (int i = 0; i < ANSWER_CHUNKS.length; i++) {
                answer.append(ANSWER_CHUNKS[i]);
                rig.pump.pump();
                if (widthAfterChunk[i] > 0) {
                    rig.resize(widthAfterChunk[i], 30);
                    rig.pump.pump();
                    rig.settle();
                }
            }
            answer.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();
            return answerRows(rig.document());
        }
    }

    /** The document's content rows: no blanks, and none of the live region's own furniture. */
    private static List<String> answerRows(List<String> document) {
        return document.stream()
                .map(String::stripTrailing)
                .filter(row -> !row.isBlank())
                .filter(row -> !row.contains(DOCK_TOP) && !row.contains(DOCK_BOTTOM)
                        && !row.contains("esc to cancel"))
                .toList();
    }

    /**
     * The property that decides the slice: an answer resized mid-stream ends up showing <b>exactly</b>
     * what the same answer shows when nothing was ever resized at that width. Truncation of the live
     * tail and repetition of committed content are both excluded by one comparison, and neither
     * assertion mentions a boundary, a row count or a byte.
     */
    private void assertMidAnswerResizesMatchTheUnresizedAnswer(int startWidth, int[] widthAfterChunk)
            throws Exception {
        int finalWidth = startWidth;
        for (int width : widthAfterChunk) {
            if (width > 0) {
                finalWidth = width;
            }
        }
        List<String> resized = streamedAnswerOnScreen(startWidth, widthAfterChunk);
        List<String> unresized = streamedAnswerOnScreen(finalWidth, new int[widthAfterChunk.length]);

        assertEquals(unresized, resized,
                "an answer resized mid-stream must end up identical to the same answer at width "
                        + finalWidth + " with no resize at all");
        assertTrue(resized.stream().anyMatch(row -> row.contains("sentence one")),
                "…and it must actually contain what was streamed before the resize: " + resized);
        assertTrue(resized.stream().anyMatch(row -> row.contains("sentence three")),
                "…and what was streamed after the last one: " + resized);
    }

    /** Narrowing mid-answer, which <em>increases</em> the styled-line count of the table. */
    @Test
    void narrowingMidAnswerNeitherTruncatesTheTailNorRepeatsCommittedContent() throws Exception {
        assertMidAnswerResizesMatchTheUnresizedAnswer(100, new int[]{0, 0, 64, 0, 0});
    }

    /** Widening mid-answer, which <em>decreases</em> it — the direction that silently emptied the tail. */
    @Test
    void wideningMidAnswerNeitherTruncatesTheTailNorRepeatsCommittedContent() throws Exception {
        assertMidAnswerResizesMatchTheUnresizedAnswer(64, new int[]{0, 0, 116, 0, 0});
    }

    /** The resize lands with a markdown table still open, whose column allocation is width-dependent. */
    @Test
    void resizingMidAnswerWithATableInFlightNeitherTruncatesNorRepeats() throws Exception {
        assertMidAnswerResizesMatchTheUnresizedAnswer(100, new int[]{0, 60, 0, 0, 0});
    }

    /**
     * Several geometry epochs over one answer. Each new epoch has to <b>re-derive</b> the boundary at
     * its own geometry rather than inherit the previous epoch's, so the errors cannot cancel out: the
     * run narrows, widens past its start, and narrows again while the table is open.
     */
    @Test
    void anAnswerCrossingSeveralGeometryEpochsMatchesTheUnresizedAnswer() throws Exception {
        assertMidAnswerResizesMatchTheUnresizedAnswer(100, new int[]{72, 0, 64, 128, 0});
    }

    /** The committed transcript rows, in the order they appear on screen. */
    /**
     * The sequence of committed sentinels the user can read on screen, top to bottom. Each matching
     * row maps to the sentinel it carries, so a duplicated row shows up as a repeat and a lost one as
     * an omission — the [[提交块]] wears a marker and a wash, so it cannot be matched by a prefix.
     */
    private static List<String> committedOrder(List<String> document, List<String> sentinels) {
        return document.stream()
                .map(String::strip)
                .flatMap(row -> sentinels.stream().filter(row::contains))
                .toList();
    }

    private static List<String> transcriptOrder(List<String> document) {
        return document.stream()
                .map(String::strip)
                .filter(row -> row.startsWith("notice ") || row.startsWith("answer "))
                .toList();
    }

    private void assertAlternatingResizeIsClean(boolean heightFirst, boolean withInputLine)
            throws Exception {
        try (Rig rig = new Rig(100, 14)) {
            InlineRenderer r = rig.renderer;
            if (withInputLine) {
                rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                        List.of(new AttributedString("> " + INPUT_TEXT)),
                        2 + INPUT_TEXT.length(), rig.liveWidth, false));
            }
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var filler = r.beginAnswer();
            for (int i = 0; i < 20; i++) {
                filler.append("transcript line " + i + "\n");
            }
            filler.finish();
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();

            int width = 100;
            int height = 14;
            for (int step = 0; step < 10; step++) {
                if (heightFirst) {
                    rig.resize(width, ++height);
                    rig.pump.pump();
                    rig.resize(++width, height);
                } else {
                    rig.resize(++width, height);
                    rig.pump.pump();
                    rig.resize(width, ++height);
                }
                rig.pump.pump();
            }
            for (int step = 0; step < 10; step++) {
                if (heightFirst) {
                    rig.resize(width, --height);
                    rig.pump.pump();
                    rig.resize(--width, height);
                } else {
                    rig.resize(--width, height);
                    rig.pump.pump();
                    rig.resize(width, --height);
                }
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "交替改高改宽的动画式缩放不得留下 spinner 副本:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "交替改高改宽的动画式缩放不得留下状态坞副本:\n" + dump(doc));
            if (withInputLine) {
                assertEquals(1, rowsContaining(doc, INPUT_TEXT),
                        "输入行同样不得被复制进回卷:\n" + dump(doc));
            }
            for (int i = 0; i < 20; i++) {
                String line = "transcript line " + i;
                assertEquals(1, (int) doc.stream().filter(row -> row.strip().equals(line)).count(),
                        "动画式缩放不得吃掉或复制转写行 '" + line + "':\n" + dump(doc));
            }
        }
    }

    private void assertSlowDragLeavesExactlyTheReplayWindow(boolean withInputLine) throws Exception {
        // 12 rows tall, so the replay budget is 3 × 12 = 36 rows. 30 committed items of two rows
        // each (one line plus its separator) is 60 rows — comfortably more than the window holds,
        // which is what makes both halves of the contract observable in one gesture.
        try (Rig rig = new Rig(100, 12)) {
            InlineRenderer r = rig.renderer;
            if (withInputLine) {
                rig.renderer.liveRegion().bindInputBox(() -> new CaptureDisplay.Frame(
                        List.of(new AttributedString("> " + INPUT_TEXT)),
                        2 + INPUT_TEXT.length(), rig.liveWidth, false));
            }
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            for (int i = 0; i < 30; i++) {
                var line = r.beginAnswer();
                line.append("transcript line " + i + "\n");
                line.finish();
            }
            rig.pump.pump();
            r.beginThinking("Thinking");
            rig.pump.pump();

            for (int width = 99; width >= 88; width--) {
                rig.resize(width, 12);
                rig.pump.pump();
            }
            for (int width = 89; width <= 100; width++) {
                rig.resize(width, 12);
                rig.pump.pump();
            }
            rig.settle();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, "esc to cancel"),
                    "no step of the drag may leave a spinner copy behind:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "no step of the drag may leave a status-dock copy behind:\n" + dump(doc));
            if (withInputLine) {
                assertEquals(1, rowsContaining(doc, INPUT_TEXT),
                        "the input box must not be copied into scrollback either:\n" + dump(doc));
            }
            // The newest items survive, exactly once each: the replay window is repainted at the
            // final width and nothing repaints twice.
            for (int i = 15; i < 30; i++) {
                String line = "transcript line " + i;
                assertEquals(1, occurrencesOf(doc, line),
                        "the newest items must survive the drag exactly once ('" + line + "'):\n"
                                + dump(doc));
            }
            // Everything older than the window is gone — from the screen and from saved scrollback
            // alike. This is the amendment's stated price (A4), not a defect.
            for (int i = 0; i < 10; i++) {
                String line = "transcript line " + i;
                assertEquals(0, occurrencesOf(doc, line),
                        "output older than the replay window must not survive a resize ('" + line
                                + "'):\n" + dump(doc));
            }
        }
    }

    /** Rows whose stripped text is exactly {@code line}. */
    /** How many rows contain {@code text} anywhere — for rows the terminal may have soft-wrapped. */
    private static int rowsWith(List<String> document, String text) {
        return (int) document.stream().filter(row -> row.contains(text)).count();
    }

    private static int occurrencesOf(List<String> document, String line) {
        return (int) document.stream().filter(row -> row.strip().equals(line)).count();
    }

    @Test
    void wideningAlsoLeavesNoDockCopyAndEatsNoTranscript() throws Exception {
        // 加宽是「擦多了」的方向：终端只会把行合并得更短、活区顶端只会往下走，因此按 reflow 算出的
        // 上退距离绝不能越过活区顶去啃转写。这条与窄化那条一起，把擦除量从两侧夹住。
        try (Rig rig = new Rig(52, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();
            var a1 = r.beginAnswer();
            a1.append("Narrow before.\n");
            a1.finish();
            rig.pump.pump();

            rig.resize(96, 16);
            rig.settle();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", "ok")));
            rig.pump.pump();
            var a2 = r.beginAnswer();
            a2.append("Wide after.\n");
            a2.finish();
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM),
                    "a widening resize must not leave stale dock copies in scrollback:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "Narrow before."),
                    "a widening resize must not destroy transcript written before it:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, "Wide after."),
                    "a widening resize must not destroy transcript written after it:\n" + dump(doc));
        }
    }

    @Test
    void theDockIsNeverDuplicatedByRepeatedIdleFramesAndBackgroundPushes() throws Exception {
        try (Rig rig = new Rig(80, 16)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            for (int i = 0; i < 5; i++) {
                r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
                rig.pump.pump();
            }
            // 空闲等输入时的后台 push：也不得把状态坞顶进回卷。
            for (int i = 0; i < 3; i++) {
                var a = r.beginAnswer();
                a.append("background push " + i + "\n");
                a.finish();
                rig.pump.pump();
            }

            List<String> doc = rig.document();
            assertEquals(1, rowsContaining(doc, DOCK_BOTTOM),
                    "idle frames and background pushes must not duplicate the dock:\n" + dump(doc));
            assertEquals(1, rowsContaining(doc, DOCK_TOP),
                    "idle frames and background pushes must not duplicate the dock:\n" + dump(doc));
        }
    }

    // --- symptom 2: exactly one blank row between frozen block units -------------------------------

    @Test
    void frozenBlockUnitsKeepExactlyOneBlankRowOnScreenAcrossDrainSchedules() throws Exception {
        // The bug is drain-timing dependent, so sweep every pump schedule over the event sequence
        // rather than trusting one fixed cadence.
        for (int schedule = 0; schedule < 64; schedule++) {
            try (Rig rig = new Rig(80, 20)) {
                InlineRenderer r = rig.renderer;
                r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
                r.beginTurn();

                List<Runnable> steps = new ArrayList<>();
                steps.add(() -> {
                    var x = r.beginReasoning();
                    x.append("weigh the options");
                    x.finish();
                });
                steps.add(() -> {
                    r.appendToolCalls(List.of(tc("list_dir", "{\"path\":\"src\"}")));
                    r.appendToolResults(List.of(tr("list_dir", "{\"path\":\"src\"}", "ok")));
                });
                steps.add(() -> {
                    var x = r.beginAnswer();
                    x.append("Reading the relevant files now.\n");
                    x.finish();
                });
                steps.add(() -> {
                    r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
                    r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", "ok")));
                });
                steps.add(() -> {
                    var x = r.beginAnswer();
                    x.append("Here is what I found.\n");
                    x.finish();
                });
                steps.add(() -> {
                    r.appendToolCalls(List.of(tc("grep_code", "{\"pattern\":\"dock\"}")));
                    r.appendToolResults(List.of(tr("grep_code", "{\"pattern\":\"dock\"}", "ok")));
                });

                for (int i = 0; i < steps.size(); i++) {
                    steps.get(i).run();
                    if ((schedule & (1 << i)) != 0) {
                        rig.pump.pump();
                    }
                }
                r.endTurn();
                rig.pump.pump();
                rig.pump.pump();

                List<String> doc = rig.document();
                int run = maxBlankRun(doc);
                assertTrue(run <= 1,
                        "schedule " + schedule + ": expected at most one blank row between frozen units, found "
                                + run + ":\n" + dump(doc));
            }
        }
    }

    @Test
    void everyAdjacentBlockBoundaryHasExactlyOneBlankRowOnScreen() throws Exception {
        try (Rig rig = new Rig(80, 20)) {
            InlineRenderer r = rig.renderer;
            r.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
            r.beginTurn();

            var reasoning = r.beginReasoning();
            reasoning.append("weigh the options");
            reasoning.finish();
            var a1 = r.beginAnswer();
            a1.append("Committing to the pipeline plan now.\n");
            a1.finish();
            rig.pump.pump();
            r.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            r.appendToolResults(List.of(tr("read_file", "{\"path\":\"README.md\"}", "ok")));
            rig.pump.pump();
            var a2 = r.beginAnswer();
            a2.append("Now applying the edit.\n");
            a2.finish();
            rig.pump.pump();
            r.appendDiff("ROADMAP.md", "- old\n", "- new\n");
            rig.pump.pump();
            r.endTurn();
            rig.pump.pump();

            List<String> doc = rig.document();
            assertEquals(1, blankRowsBetween(doc, "思考", "Committing to the pipeline plan"),
                    "reasoning→answer:\n" + dump(doc));
            assertEquals(1, blankRowsBetween(doc, "Committing to the pipeline plan", "ReadFile"),
                    "answer→tool:\n" + dump(doc));
            assertEquals(1, blankRowsBetween(doc, "ReadFile", "Now applying the edit"),
                    "tool→answer:\n" + dump(doc));
            assertEquals(1, blankRowsBetween(doc, "Now applying the edit", "Update ROADMAP.md"),
                    "answer→diff:\n" + dump(doc));
        }
    }
}
