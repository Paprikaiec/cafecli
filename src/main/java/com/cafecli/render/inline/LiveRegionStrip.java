package com.cafecli.render.inline;

import com.cafecli.render.ToolResultView;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Curses;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Render-thread-owned JLine Display strip.
 *
 * <p>It has two responsibilities that share nothing, and the split is deliberate (ADR 0010 amendment
 * A17). <b>Composition</b> is incremental and geometry-stable: within one [[geometry epoch]] the
 * live region is repainted in place, completed blocks freeze into [[冻结回卷]], and the terminal owns
 * scrolling, copy and search exactly as before. <b>Reset</b> is wholesale and geometry-triggered:
 * any observed dimension change ends the epoch, and the strip erases the visible screen and the
 * terminal's saved scrollback and repaints the [[bounded replay window]] at the new geometry.
 *
 * <p>A reset never asks where the terminal moved anything. That question — answered by relative
 * erase distances, bottom-edge pinning and reflow row arithmetic — is what four rounds of fixes got
 * wrong, and it is gone: the previous epoch's screen is declared void rather than reconstructed.
 *
 * <p>An epoch boundary is not an instant, so the reset is split in time by {@link TurbulenceGate}
 * (ADR 0010 amendment A3b): the geometry-independent erase goes out eagerly on every observed
 * dimension change, the geometry-sensitive redraw exactly once when the geometry settles, and in
 * between <b>every</b> content write is gated off while producers keep filling the source model.
 */
final class LiveRegionStrip {

    private final Terminal terminal;
    private final Terminal displayTerminal;
    private final StringWriter captured = new StringWriter();
    private final PrintWriter capturedWriter = new PrintWriter(captured, false);
    private final GeometrySampler geometry;
    private final TimeSource clock;
    private final TurbulenceGate turbulence;
    private Display display;
    /** Whether the {@code Display} has ever been given a geometry; a fresh one never has. */
    private boolean displaySized;
    private final List<StripEntry> entries = new ArrayList<>();
    private final Set<StripEntry> finishCutoff =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private StripEntry leadingEntry;
    private final ReplayLog replayLog = new ReplayLog();
    private final List<PendingCommit> pendingCommits = new ArrayList<>();
    private boolean finishTurnRequested;
    private ApprovalBlock approval;
    private IntFunction<List<AttributedString>> dock;
    private Supplier<CaptureDisplay.Frame> inputBox;
    private int lastCursorPos = -1;
    private int lastRegionRows;
    /** This frame's sampled geometry; producer callbacks between frames reuse it. */
    private Size frameSize;
    private ResizeObserver resizeObserver;
    private long lastObservedGeneration;
    private Runnable frameRequest;
    /**
     * How many modals currently hold the terminal (ADR 0010 amendment A12). Written by whichever
     * thread opens the modal — the input thread — and read by the render thread every frame, so it is
     * atomic; nesting is a depth rather than a flag because a modal may open another.
     */
    private final AtomicInteger modalLeases = new AtomicInteger();

    LiveRegionStrip(Terminal terminal) {
        this(terminal, TimeSource.system());
    }

    LiveRegionStrip(Terminal terminal, TimeSource clock) {
        this(terminal,
                GeometrySampler.forTerminal(terminal),
                clock,
                TurbulenceGate.fromSystemProperties());
    }

    /**
     * Test seam: drive geometry, time and the turbulence constants without a terminal or real
     * waiting. The whole epoch mechanism — eager erase, blank hold, deferred redraw, keep-alive — is
     * reachable through this constructor alone.
     */
    LiveRegionStrip(Terminal terminal,
                    GeometrySampler geometry,
                    TimeSource clock,
                    TurbulenceGate turbulence) {
        this.terminal = terminal;
        this.geometry = geometry;
        this.clock = clock;
        this.turbulence = turbulence;
        this.displayTerminal = capturingTerminal(terminal);
        this.display = newDisplay();
    }

    /**
     * One unit of committed output waiting for the next frame to write it into [[冻结回卷]].
     *
     * <p>There is a single queue rather than one per source because <b>order is the contract</b>: a
     * [[destructive resize reset]] repaints from {@link ReplayLog}, so anything the queue writes in a
     * different order than the log recorded it changes places the moment the window is resized. That
     * was measurable — a frame that committed a streamed notice and then froze a settled block wrote
     * the block's rows first, and the reset then swapped them back.
     *
     * <p>Two shapes, because two things are committed: rows already laid out by a block, and raw ANSI
     * text handed over whole (the intercepted {@code printAbove}). The text keeps its own bytes rather
     * than being re-parsed into rows, so what reaches the terminal is exactly what the producer wrote.
     */
    private sealed interface PendingCommit {
    }

    private record PendingRows(List<AttributedString> rows) implements PendingCommit {
    }

    private record PendingText(String ansi) implements PendingCommit {
    }

    /**
     * Record that {@code entry} has begun reaching [[冻结回卷]]. What the log takes is the entry's
     * <b>immutable source</b> — never a live view of it (ADR 0016 D1) — because the log is read from
     * the input thread by the [[转写浏览层]].
     */
    private void commit(StripEntry entry) {
        replayLog.append(entry.committedSource());
    }

    private void queueRows(List<AttributedString> rows) {
        if (rows != null && !rows.isEmpty()) {
            pendingCommits.add(new PendingRows(List.copyOf(rows)));
        }
    }

    private void queueSeparatorBlank() {
        pendingCommits.add(new PendingRows(List.of(AttributedString.EMPTY)));
    }

    private void queueText(String ansi) {
        if (ansi != null && !ansi.isEmpty()) {
            pendingCommits.add(new PendingText(ansi));
        }
    }

    /**
     * This frame's terminal width. Every producer action that runs on the render thread outside
     * {@link #renderFrame()} — freezing a block, echoing a submission, committing an overflowing tail —
     * uses it, so one frame never sees two widths.
     */
    private int frameWidth() {
        if (frameSize == null) {
            frameSize = geometry.lastSample();
        }
        return Math.max(1, frameSize.getColumns());
    }

    /**
     * Take a streaming answer into the live region, wrapped in the [[活区]]'s <b>own</b> view of it
     * (ADR 0016 D1). The bare {@link AnswerSegment} is immutable source and is what reaches the
     * ordered commit log; the projection that lays it out at this terminal's width belongs to the
     * wrapper, on this thread, and is reachable from nowhere else.
     */
    void add(AnswerSegment answer) {
        freezeSettled();
        entries.add(new LiveAnswer(answer));
    }

    /**
     * Commit a completed foldable (reasoning / tool / code / diff) straight to scrollback instead of
     * parking it in the repainted {@code entries} list for a frame.
     *
     * <p>This is the fix for issue #5B (the big blank gap). A foldable that streams live in the JLine
     * {@code Display} region for one or more frames and is only frozen on the next {@code add}/{@code
     * endTurn} produces a visible gap: the {@code Display} erases the live rows in place, leaving the
     * cursor at the bottom of the now-blank region, and {@code emitFrozenBatch} then re-emits the
     * frozen block <em>below</em> those erased rows. Per ADR 0012 / CONTEXT.md the live tail must
     * contain only the thinking spinner, the streaming answer tail, and the approval card, so
     * foldables must never enter {@code entries} at all.
     */
    void add(LiveFoldableBlock block) {
        freezeSettled();
        int width = frameWidth();
        List<AttributedString> rows = block.freezeTopRows(Integer.MAX_VALUE, width);
        if (!rows.isEmpty()) {
            queueRows(rows);
            queueSeparatorBlank();   // the single inter-block separator (issue #5A)
            commit(block);
        }
    }

    /**
     * Open a [[工具块]] in the [[活区]] carrying its call header and no result yet (ADR 0017 D2).
     *
     * <p>Unlike {@link #add(LiveFoldableBlock)} this does <b>not</b> commit: the batch is still
     * running, so it is not a completed foldable, and it waits here beside the spinner and the
     * [[审批应答]] card until {@link #completeTools(List)} arrives. That is also why
     * {@link #freezeSettled()} is untouched — a running batch is not a {@code LiveFoldableBlock}, so
     * the rule it already states covers this case without a new branch.
     *
     * <p>Whatever was settled before this batch is committed first, exactly as adding any other block
     * does: opening a new block is the moment the previous one can no longer change.
     */
    void addRunningTools(ToolCallBlock block) {
        freezeSettled();
        entries.add(block);
    }

    /**
     * A file changed. If a [[工具块]] is running, this is that batch's <b>result</b> and belongs to it
     * (ADR 0017 D4); otherwise it is a block of its own, exactly as before.
     *
     * <p>The write observer fires from inside {@code executeTools}, which is by construction between
     * {@code appendToolCalls} and {@code appendToolResults}, so a running batch is the batch that wrote
     * the file. Committing the diff separately would put the call header and the change it produced in
     * two blocks, which anything committed in between — a background notice, a [[转向消息]] — would
     * then be free to sit between.
     *
     * <p>The fallback is not a leftover: a diff can reach the renderer with no batch open at all (the
     * repro driver does exactly that), and a result view with no call to belong to is a block.
     */
    void addDiff(DiffBlock diff) {
        ToolCallBlock running = runningTools();
        if (running != null) {
            running.attachDiff(diff);
            return;
        }
        add(diff);
    }

    /**
     * A line a still-running command just printed: it belongs to the open [[工具块]]'s tail
     * (ADR 0017 D5).
     *
     * <p>It arrives here on the render thread, posted by {@code InlineRenderer} from the
     * {@code cafecli-command-output} thread, so the reader thread never writes the terminal — the
     * live region keeps exactly one writer, and this is just another producer event.
     *
     * <p>The open batch takes it. Only {@code execute_command} publishes at all (that decision lives
     * at the tool execution seam), so a batch that receives one is the batch running it; lines
     * arriving with no batch open — after the results already landed, say — are dropped, since there
     * is nothing on screen for them to belong to.
     */
    void appendRunningCommandOutput(String command, String line) {
        ToolCallBlock running = runningTools();
        if (running != null) {
            running.appendRunningOutput(command, line);
        }
    }

    /**
     * The batch currently open in the [[活区]], if any: the one with a header but no results yet.
     *
     * <p>Both a diff (D4) and a running command's output line (D5) are results looking for the call
     * they belong to, so they ask the same question here rather than each walking {@code entries}
     * with its own idea of which open batch wins.
     */
    private ToolCallBlock runningTools() {
        ToolCallBlock running = null;
        for (StripEntry entry : entries) {
            if (entry instanceof ToolCallBlock block && !block.isComplete()) {
                running = block;
            }
        }
        return running;
    }

    /**
     * The results arrived: complete the batch and commit it as one block.
     *
     * <p>It leaves the live region and reaches [[冻结回卷]] through the same queue-rows, separator,
     * append-to-log sequence every completed foldable uses, so the frame that writes it erases the
     * live rows it was occupying first ({@link #emitPendingScrollback}). Without that erase the block
     * would be on screen twice, and the two cases are byte-identical — only the screen model tells
     * them apart, which is why the criterion for this is in {@code ScreenTruthRegressionTest}.
     *
     * <p>Results with no open batch are dropped. Nothing announced those calls, so there is nothing on
     * screen for them to complete, and inventing a block here would put a result on screen with no
     * header above it.
     */
    void completeTools(List<ToolResultView> results) {
        ToolCallBlock running = null;
        for (StripEntry entry : entries) {
            if (entry instanceof ToolCallBlock block && !block.isComplete() && block.owns(results)) {
                running = block;
            }
        }
        if (running == null) {
            return;
        }
        running.completeWith(results);
        entries.remove(running);
        int width = frameWidth();
        List<AttributedString> rows = running.freezeTopRows(Integer.MAX_VALUE, width);
        if (!rows.isEmpty()) {
            queueRows(rows);
            queueSeparatorBlank();   // the single inter-block separator (issue #5A)
            commit(running);
        }
    }

    /**
     * Commit every entry that can no longer change into terminal scrollback, keeping only
     * genuinely-live content in the repainted Display region.
     *
     * <p>Completed foldables (reasoning / tool / code / diff) and finished answer segments are
     * immutable, so redrawing them every frame is what smears their rows when the user scrolls the
     * wheel mid-turn. Freezing them here shrinks the live region to the trailing block plus the
     * activity spinner, which is the only reliable way to avoid scroll tearing on terminals without
     * DEC 2026 synchronized output (e.g. Terminal.app). A blank spacer row separates each frozen
     * unit so thinking / tool / answer boundaries stay legible in scrollback.
     */
    void freezeSettled() {
        if (entries.isEmpty()) {
            return;
        }
        int width = frameWidth();
        List<StripEntry> settled = new ArrayList<>();
        for (StripEntry entry : entries) {
            if (entry == approval) {
                continue;
            }
            // Foldables no longer normally reach here: add(LiveFoldableBlock) commits them straight to
            // scrollback and never parks them in entries (issue #5B). This branch is kept only so any
            // stray foldable that ever lands in entries is still committed exactly once.
            if (entry instanceof LiveFoldableBlock) {
                settled.add(entry);
            } else if (entry instanceof LiveAnswer answer && answer.isComplete()) {
                settled.add(entry);
            }
        }
        for (StripEntry entry : settled) {
            List<AttributedString> rows = entry.freezeTopRows(Integer.MAX_VALUE, width);
            if (!rows.isEmpty()) {
                queueRows(rows);
                queueSeparatorBlank();
                entry.sealForReplay();
                commit(entry);
            }
        }
        entries.removeAll(settled);
    }

    void showApproval(ApprovalBlock block) {
        if (approval != null) {
            entries.remove(approval);
        }
        approval = block;
        if (block != null) {
            entries.add(block);
        }
    }

    void dismissApproval(ApprovalBlock block) {
        if (approval == block) {
            entries.remove(block);
            approval = null;
        }
    }

    void bindLeadingEntry(StripEntry entry) {
        leadingEntry = entry;
    }

    /**
     * Bind the bottom status dock as the pinned trailing rows of the live region. The render thread
     * asks for the dock's rows at the current width every frame and composes them after the live
     * tail, so the dock is drawn by the same single writer as everything else in the region.
     */
    void bindDock(IntFunction<List<AttributedString>> dockRows) {
        dock = dockRows;
    }

    /**
     * Bind the input line as the pinned trailing entry of the live region: after the live tail,
     * before the status dock, and <b>never</b> in frozen scrollback (ADR 0014 决策 2).
     *
     * <p>The frame is produced on the input thread by {@link CaptureDisplay} and read here on the
     * render thread — the line editor is now just another producer, not a second writer.
     */
    void bindInputBox(Supplier<CaptureDisplay.Frame> input) {
        inputBox = input;
    }

    /**
     * A modal takes the terminal (ADR 0010 amendment A12). Until it is handed back, every frame writes
     * nothing at all: one writer owns the terminal at a time and the modal is that writer for its
     * duration, which extends ADR 0014's single-writer invariant rather than bending it.
     *
     * <p>Nesting is counted, so a palette opened from inside another modal does not hand the screen
     * back early. Geometry keeps being observed throughout — a change under a lease still ends the
     * [[geometry epoch]] — and the modal redraws itself at the new size; what it must not do is let
     * <em>this</em> region write over it.
     */
    void acquireModalLease() {
        modalLeases.incrementAndGet();
    }

    /**
     * Hand the terminal back. If the geometry changed while it was held, the first frame from here on
     * performs the single deferred [[destructive resize reset]]; if it did not, nothing is repainted.
     *
     * <p>A frame is asked for explicitly because the return is not a producer event: nothing else is
     * guaranteed to post one, exactly as after the last dimension change of a gesture.
     */
    void releaseModalLease() {
        if (modalLeases.updateAndGet(depth -> Math.max(0, depth - 1)) == 0) {
            requestAnotherFrame();
        }
    }

    /** Whether a modal currently owns the terminal, in which case this region writes nothing. */
    private boolean modalHoldsTheTerminal() {
        return modalLeases.get() > 0;
    }

    /**
     * A SIGWINCH arrived. It is a wake-up hint only: it tells the sampler to look again, and the
     * next sample decides whether a [[geometry epoch]] actually ended (ADR 0010 amendment A3c).
     * Returns the signal generation, which the replay recorder pairs with the observed geometry.
     */
    long armGeometrySample() {
        return geometry.arm();
    }

    /**
     * Start sampling geometry in the background. Until this is called the sampler only observes what
     * {@link #armGeometrySample()} and any hand-driven poll bring it, which is what keeps a test's
     * injected geometry entirely under the test's control.
     */
    void startGeometrySampling() {
        geometry.start();
    }

    /** Stop sampling. The strip writes no bytes here; it only lets the sampler's driver finish. */
    void close() {
        geometry.close();
    }

    /** Bind the recorder to this frame's existing size sample; the observer must not query the tty. */
    void bindResizeObserver(ResizeObserver observer) {
        resizeObserver = observer;
    }

    /**
     * Bind how the strip asks the render loop for one more frame. It is used only while a deferred
     * redraw is owed — see {@link #requestAnotherFrame()}.
     */
    void bindFrameRequest(Runnable request) {
        frameRequest = request;
        // A geometry the sampler observes on its own thread only ends an epoch once a frame consumes
        // it, and nothing else posts one after the last change of a gesture — or ever, on a terminal
        // that raises no signal. The sampler asks for that frame through the same seam.
        geometry.onTransitionObserved(this::requestAnotherFrame);
    }

    /**
     * The destination of the line editor's intercepted {@code printAbove}: text becomes ordinary
     * frozen scrollback emitted above the live region by the render thread, instead of being written
     * to the terminal from the input thread. Mirrors JLine's own newline discipline.
     */
    void printAbove(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String terminated = text.endsWith("\n") ? text : text + "\n";
        queueText(terminated);
        replayLog.append(FrozenTextItem.ofAnsi(terminated));
    }

    /**
     * 擦掉[[活区]]并把光标送回该区域左上角的原点。
     *
     * <p>{@code Display.update(List.of(), …)} 会擦掉每一行，但把光标留在被擦区域的<b>底部</b>，
     * 而 {@link #emitFrozenBatch} 是从光标当前位置往下写的。于是一个 N 行的活区会给每一批
     * [[冻结回卷]]静默地垫上 N−1 个空行：活区只有一行时看不出来，但状态坞（issue 04）与输入行
     * （issue 05）把回合期的 N 抬到了 3–9，空行随之变成 2–8 个。
     *
     * <p>This is a within-epoch mechanism only. It never runs on a geometry change: a
     * [[destructive resize reset]] erases the whole screen instead, and asking where the cursor
     * ended up after the terminal reflowed is precisely the question that has no reliable answer.
     */
    private void clearLiveRegion() {
        display.update(List.of(), -1, false);
        if (lastRegionRows > 0) {
            // 空活区没有可归位的光标，此时任何额外字节都是纯噪声。
            if (lastRegionRows > 1) {
                capturedWriter.print(cursorUpSequence(lastRegionRows - 1));
            }
            capturedWriter.print('\r');
        }
        lastRegionRows = 0;
    }

    /** 光标上移 n 行；优先用带参数的 capability，退化成重复单行上移。 */
    private String cursorUpSequence(int n) {
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        String parameterized = terminal.getStringCapability(InfoCmp.Capability.parm_up_cursor);
        if (parameterized != null && !parameterized.isEmpty()) {
            Curses.tputs(writer, parameterized, n);
        } else {
            String single = terminal.getStringCapability(InfoCmp.Capability.cursor_up);
            if (single == null || single.isEmpty()) {
                return "";
            }
            for (int i = 0; i < n; i++) {
                Curses.tputs(writer, single);
            }
        }
        writer.flush();
        return out.toString();
    }

    /** The cursor position carried by the last composed frame; {@code -1} when unpositioned. */
    int lastCursorPos() {
        return lastCursorPos;
    }

    private CaptureDisplay.Frame inputFrame() {
        Supplier<CaptureDisplay.Frame> supplier = inputBox;
        if (supplier == null) {
            return null;
        }
        CaptureDisplay.Frame frame = supplier.get();
        return frame == null || frame.isEmpty() ? null : frame;
    }

    /**
     * Translate the input box's own cursor position into composed-frame coordinates.
     *
     * <p>JLine encodes a cursor as {@code row * (columns + 1) + col} ({@code Size.cursorPos}), so the
     * translation is a pure row shift by however many live-tail rows precede the input box. A width
     * mismatch (the frame was captured before a resize landed) yields {@code -1} rather than a wrong
     * position — the next frame carries the corrected one.
     *
     * <p><b>光标永远不落在最后一列</b>（{@code column} 夹到 {@code width - 1}）。这是活区那条既有规则
     * （「凡是补齐到整行的东西都少占一列」）在光标上的实现，此前只靠「我们补的行都短一列」间接成立，
     * 而输入行不是我们补的：行编辑器按终端整宽折行，一行填满时光标就落在 {@code col == width} 这个
     * <b>右边缘</b>位置。JLine 移到右边缘没有可移植的转义可用，于是 {@code Display.moveVisualCursorTo}
     * 改为<b>重打第 {@code width - 1} 列那个字符</b>（{@code columnSubSequence(columns - 1, columns)}）。
     * 那一列若是<b>双宽字符的后半</b>，取回来的是整个字（切不开），打在第 {@code width - 1} 列就多占一列、
     * 触发终端折行，后半落到下一行第 0 列——从此 JLine 的光标模型与屏幕差两列，后续每一帧都写错位置：
     * 下横线从第 2 列开始画，旧的输入行再也擦不掉。这正是手工验收报的「退格多行删除不完全」，离线复现
     * 见 {@code InputBoxChromeTest} 与 {@code ScreenTruthRegressionTest}（ASCII 不复现——它没有双宽字符，
     * 重打那一列是无害的）。夹一列的代价是：内容恰好填满一行时光标压在末字符上而不是它右边，那本来就是
     * 延迟折行终端上的歧义位置。
     */
    private static int composedCursorPos(CaptureDisplay.Frame frame, int rowOffset, int width) {
        if (frame == null || frame.cursorPos() < 0 || frame.columns() != width) {
            return -1;
        }
        int stride = width + 1;
        int row = frame.cursorPos() / stride;
        int column = Math.min(Math.max(0, width - 1), frame.cursorPos() % stride);
        return (rowOffset + row) * stride + column;
    }

    /**
     * The dock is laid out one column narrower than the terminal, so no live-region row ever
     * occupies the last column. It is the only row the strip pads to full width, and a row that
     * fills its last column leaves the cursor in an ambiguous place on terminals that delay the
     * wrap. The padding is dim filler either way, so a column costs nothing visually.
     */
    private List<AttributedString> dockRows(int width) {
        if (dock == null) {
            return List.of();
        }
        List<AttributedString> rows = dock.apply(Math.max(1, width - 1));
        return rows == null ? List.of() : rows;
    }

    /**
     * Echo a submission into [[冻结回卷]]. The <b>source</b> text is what arrives here and what the
     * ordered commit log keeps, so a [[destructive resize reset]] re-renders the [[提交块]] for the
     * geometry it repaints into instead of replaying rows padded to an old width
     * (ADR 0010 amendment A8/A11). Laying it out here rather than on the producer thread also means
     * the width it is rendered at is this frame's width, not one sampled off-thread.
     */
    void submitPrompt(String prompt) {
        SubmittedPromptItem submitted = SubmittedPromptItem.of(terminal, prompt);
        if (submitted == null) {
            return;
        }
        int width = frameWidth();
        for (StripEntry entry : entries) {
            List<AttributedString> rows = entry.freezeTopRows(Integer.MAX_VALUE, width);
            if (!rows.isEmpty()) {
                queueRows(rows);
                commit(entry);
            }
        }
        entries.clear();
        approval = null;
        queueText(submitted.ansiText(width));
        commit(submitted);
    }

    /**
     * Echo a [[转向消息]] into [[冻结回卷]] <b>without ending the turn</b> (issue 10).
     *
     * <p>Two differences from {@link #submitPrompt}, and both follow from the turn still running.
     * The live entries stay live — the answer this correction interrupted goes on streaming below the
     * echo, so freezing and dropping them would strand the [[活区]]'s view of it. And the answer's
     * finished lines are committed <em>first</em>, so the echo takes its position after what the user
     * was reacting to rather than in the middle of it; that same commit is what closes the answer's
     * position in the ordered commit log at the right source line (ADR 0016 D5).
     */
    void steerPrompt(String prompt) {
        SubmittedPromptItem submitted = SubmittedPromptItem.of(terminal, prompt);
        if (submitted == null) {
            return;
        }
        int width = frameWidth();
        drainCompletedAnswerRows(width);
        queueText(submitted.ansiText(width));
        commit(submitted);
    }

    void finishTurn() {
        freezeSettled();
        finishTurnRequested = true;
        for (StripEntry entry : entries) {
            if (entry instanceof LiveAnswer) {
                finishCutoff.add(entry);
            }
        }
    }

    void clear() {
        entries.clear();
        finishCutoff.clear();
        pendingCommits.clear();
        finishTurnRequested = false;
        approval = null;
        replayLog.clear();
    }

    /**
     * Test seam: how many <b>positions</b> the [[bounded replay window]] can draw from. An answer
     * interrupted by another commit occupies more than one (ADR 0016 D5).
     */
    int replayLogSize() {
        return replayLog.size();
    }

    /**
     * The retained session, oldest first: every <b>position</b> committed to [[冻结回卷]] since the
     * session began (ADR 0010 amendment A10, ADR 0016 D5). This is what the [[转写浏览层]] browses —
     * unbounded, because retention answers to an overlay the user opens deliberately rather than to
     * the terminal.
     *
     * <p>Read from the input thread when the overlay opens; the log is synchronized for it.
     */
    List<StripEntry> retainedItems() {
        return replayLog.items();
    }

    /**
     * Rendered rows <b>this view</b> is holding (ADR 0016 D1). Retained entries hold none at all now:
     * every row on the screen was laid out by the [[活区]] and is cached by the [[活区]], and the
     * [[转写浏览层]] keeps its own. What is counted here is therefore the live answers' projections.
     */
    int liveRenderCacheRows() {
        int rows = 0;
        for (StripEntry entry : entries) {
            if (entry instanceof LiveAnswer answer) {
                rows += answer.cachedRows();
            }
        }
        return rows;
    }

    /** Where the overlay reads the terminal's geometry: the sampler's latest value, never a probe. */
    GeometrySource sampledGeometry() {
        return geometry::lastSample;
    }

    /**
     * Test seam: the ordered commit log as plain text rows, in write order, laid out at {@code width}.
     *
     * <p>Coverage of the log — did the banner, the slash-command result, the background completion
     * notice reach it, and in what order — is asserted here rather than against emitted bytes or a
     * screen, because the log is a model and byte assertions are what four rounds of resize fixes
     * passed while the terminal stayed broken (ADR 0010 amendment A16).
     */
    List<String> replayLogRows(int width) {
        List<String> rows = new ArrayList<>();
        for (StripEntry item : replayLog.items()) {
            for (AttributedString row : item.replayRows(Math.max(1, width))) {
                rows.add(row.toString());
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Compose one frame. What it is allowed to write is not this method's decision — the
     * [[geometry turbulence]] gate makes it, and there are four answers: compose normally, write only
     * the geometry-independent erase, write nothing at all, or perform a whole
     * [[destructive resize reset]] and compose beneath it (ADR 0010 amendment A3b).
     *
     * <p>Committing to the replay log sits <b>above</b> that decision and is never gated. Gating the
     * terminal is the point; gating the source model would throw away the output that arrived while
     * the screen was blank.
     *
     * <p>There is exactly <b>one</b> freeze path (ADR 0014 决策 5). The old "the line editor is
     * reading → batch-append via {@code printAbove}" branch is gone: with an always-live reader it
     * would be taken forever, and because it only appends and never repaints it would swallow the
     * entire live region (the dock included).
     */
    String renderFrame() {
        captured.getBuffer().setLength(0);
        Size size = geometry.sample();
        int width = Math.max(1, size.getColumns());
        int rows = Math.max(1, size.getRows());
        frameSize = size;
        // Committing is what feeds the replay log, so it happens before the epoch decision and is
        // never gated: producers keep filling the source model throughout [[geometry turbulence]],
        // and whatever has reached scrollback belongs to the window that a reset repaints.
        drainCompletedAnswerRows(width);

        boolean leased = modalHoldsTheTerminal();
        switch (turbulence.observe(width, rows, clock.nanoTime(), leased)) {
            case CONTENT -> {
                if (!displaySized) {
                    // The first frame of a session establishes the epoch. There is no previous screen
                    // of ours to declare void, so nothing is erased — only the Display is given a
                    // geometry it has never had.
                    installFreshDisplay(width, rows);
                }
                emitPendingScrollback(width, rows);
                composeLiveRegion(width, rows);
            }
            case ERASE_ONLY -> {
                notifyGeometryObserver(rows, width);
                eraseEagerly(width, rows);
            }
            case NOTHING -> {
                // Deliberately blank. The screen holds the erase and nothing else until the geometry
                // settles; every producer above has already been served by the source model.
            }
            case RESET -> {
                notifyGeometryObserver(rows, width);
                destructiveResizeReset(width, rows);
                composeLiveRegion(width, rows);
            }
        }
        // While a modal holds the terminal there is nothing for another frame to do, and asking for one
        // every 33 ms for as long as the user reads would be a spin. The owed redraw is picked up by
        // the frame that releaseModalLease() asks for instead.
        if (turbulence.isTurbulent() && !leased) {
            requestAnotherFrame();
        }
        capturedWriter.flush();
        return captured.toString();
    }

    /**
     * The eager, geometry-independent half of a [[destructive resize reset]] (ADR 0010 amendment
     * A3b): erase the visible screen, erase the terminal's saved scrollback, home the cursor. Nothing
     * else — the geometry-sensitive redraw waits for the geometry to settle.
     *
     * <p>Erasing invalidates the {@code Display}'s line model as thoroughly as it invalidates the
     * screen, so a fresh one is installed. That costs an allocation and writes no bytes.
     */
    private void eraseEagerly(int width, int rows) {
        capturedWriter.print(AnsiSeq.ERASE_SCREEN_AND_SAVED_SCROLLBACK);
        installFreshDisplay(width, rows);
    }

    /**
     * Ask for one more frame, because a deferred redraw needs a frame to be released on.
     *
     * <p>The last dimension change of a gesture is followed by silence: the signal that armed the
     * sampler has been consumed and no producer has anything to say, so without this the screen would
     * stay blank until something else happened to post an event. Frames requested here are throttled
     * by the ordinary cadence and, while the gate is closed, write nothing at all.
     */
    private void requestAnotherFrame() {
        Runnable request = frameRequest;
        if (request != null) {
            request.run();
        }
    }

    /**
     * The geometry-sensitive half of a [[destructive resize reset]], written <b>once</b> per settled
     * gesture (or as a keep-alive when the max defer expires): erase the visible screen <b>and</b> the
     * terminal's saved scrollback, home the cursor, then repaint the [[bounded replay window]] at the
     * geometry the gate observed to have settled.
     *
     * <p>It carries its own erase rather than relying on the eager one still being the last thing on
     * the wire. That keeps this half self-contained — it homes the cursor itself instead of inheriting
     * a position — for a dozen bytes.
     *
     * <p>The erase is sent unconditionally — no capability probe, no terminal allowlist (ADR 0010
     * amendment A15). There is no better behaviour to switch to: erasing the visible screen is
     * universal, so only saved scrollback differs, and on a terminal that ignores the scrollback
     * erase the symptom is duplication <em>confined to scrollback</em> while the visible screen is
     * correct regardless. Terminal.app honours it, measured twice with byte-identical results.
     *
     * <p>Nothing queued for scrollback in the previous epoch is written: those items are in the
     * replay log and are re-rendered below at the new width, so writing their stale rows first would
     * duplicate them.
     */
    private void destructiveResizeReset(int width, int rows) {
        pendingCommits.clear();
        if (finishTurnRequested) {
            applyFinishCutoff();
        }
        capturedWriter.print(AnsiSeq.ERASE_SCREEN_AND_SAVED_SCROLLBACK);
        lastRegionRows = 0;
        emitFrozenBatch(ReplayWindow.select(
                replayLog.items(), width, ReplayWindow.budgetRows(rows)).rows());
        installFreshDisplay(width, rows);
    }

    /**
     * Write everything committed since the last frame into [[冻结回卷]], <b>in commit order</b>.
     *
     * <p>One pass over one queue is what makes the terminal agree with {@link ReplayLog}, and the
     * agreement is the point: the log is what a [[destructive resize reset]] repaints, so a queue that
     * reordered anything would show the user one order now and a different one after the next resize.
     */
    private void emitPendingScrollback(int width, int rows) {
        if (pendingCommits.isEmpty()) {
            return;
        }
        clearLiveRegion();
        for (PendingCommit commit : pendingCommits) {
            switch (commit) {
                case PendingRows queued -> emitFrozenBatch(queued.rows());
                // Text handed over by the line editor's intercepted printAbove is frozen scrollback
                // too, and keeps the producer's own bytes.
                case PendingText queued -> capturedWriter.print(queued.ansi());
            }
        }
        pendingCommits.clear();
        installFreshDisplay(width, rows);
    }

    private void composeLiveRegion(int width, int rows) {
        List<AttributedString> leading = leadingEntry == null
                ? List.of() : leadingEntry.render(width);
        List<StripEntry> finishing = finishTurnRequested && !finishCutoff.isEmpty()
                ? entries.stream().filter(finishCutoff::contains).toList()
                : List.of();
        boolean finishingHasContent = finishing.stream()
                .anyMatch(entry -> !entry.render(width).isEmpty());
        if (finishingHasContent) {
            // The finishing block is about to be pushed into scrollback by the '\n' below, so it must
            // be composed WITHOUT the dock — the dock is live-region furniture and would otherwise
            // leave a frozen copy of itself behind.
            renderEntries(leading, finishing, width, rows, false);
            capturedWriter.print('\n');
            finishing.forEach(this::commit);
            installFreshDisplay(width, rows);
            applyFinishCutoff();
            renderEntries(List.of(), entries, width, rows, true);
        } else {
            // The finishing answer's lines were already committed to scrollback incrementally by
            // drainCompletedAnswerRows, so there is nothing left to freeze and no extra separator
            // to print; just drop the drained segments and repaint whatever remains live.
            renderEntries(leading, entries, width, rows, true);
            if (finishTurnRequested) {
                applyFinishCutoff();
            }
        }
    }

    private void notifyGeometryObserver(int rows, int columns) {
        long generation = geometry.generation();
        if (resizeObserver != null && generation > 0 && generation != lastObservedGeneration) {
            resizeObserver.observe(generation, rows, columns);
            lastObservedGeneration = generation;
        }
    }

    /**
     * 活区尾这一帧能用几行：活区高度减去输入行与状态坞自己的高度。它们都钉在活区底部，所以尾巴
     * 的预算就是剩下的部分。
     */
    private static int liveTailBudget(int rows, int inputRows, int dockRows) {
        return Math.max(1, rows - dockRows - inputRows);
    }

    /**
     * 整个[[输入框]]（含横线）最多能占几行。留一行给[[活区尾]]：框可以长，但不能长到把活区尾吃光——
     * 回合期那一行是 spinner，空闲期那一行是最后一段答案的尾巴。超出这个上限时框内滚动，横线保持
     * 在首尾（{@link InputBoxChrome#decorate}）。
     */
    private static int maxInputBoxRows(int rows, int dockRows) {
        return Math.max(1, rows - dockRows - 1);
    }

    /** 捕获帧里光标落在第几行；未知（尚未定位 / 宽度不符）时 {@code -1}。 */
    private static int capturedInputCursorRow(CaptureDisplay.Frame frame,
                                              List<AttributedString> capturedRows,
                                              int width) {
        if (frame == null || capturedRows.isEmpty() || frame.cursorPos() < 0) {
            return -1;
        }
        return frame.cursorPos() / (width + 1);
    }

    private void renderEntries(List<AttributedString> leading,
                               List<? extends StripEntry> visibleEntries,
                               int width,
                               int rows,
                               boolean live) {
        // The input box and the dock are pinned to the bottom of the region, so the live tail's
        // budget is the region height minus their own heights. This is what keeps the tail from
        // overrunning them now that no scroll region reserves those rows. Neither is composed into
        // the frame that is about to be pushed into scrollback (live == false).
        List<AttributedString> dockLines = live ? dockRows(width) : List.of();
        CaptureDisplay.Frame input = live ? inputFrame() : null;
        // A capture from another width composes **nothing**, not rows laid out for that width
        // (ADR 0016 D4) — the same rule the cursor a few lines below has always followed, and for
        // the same reason: a value that describes a different geometry is worse than no value.
        // Two things go wrong otherwise, and only the first is cosmetic. The rows themselves were
        // wrapped, indented and right-prompted for a width the terminal no longer has; and their
        // count is what `liveTailBudget` subtracts, so a buffer captured as one row at 120 columns
        // budgets the tail as if it still needed one at 40. The next capture at this width carries
        // the box back — a keystroke on the signalled path, and on the heartbeat path (A3c: geometry
        // is sampled, not signalled) the next keystroke, because nothing asks the reader to redisplay.
        // Bounded and self-resolving, where stale rows are a wrong layout with no scheduled correction.
        List<AttributedString> capturedRows = input == null || input.columns() != width
                ? List.of() : input.lines();
        // The chrome (two rules + the [[提交块]]'s wash) only ever decorates rows we already have at
        // the terminal's own width — no vertical borders, so the line editor is never asked to lay
        // out against anything but the terminal (ADR 0016 D6). It also obeys D4 by construction:
        // given no captured rows it produces no rules and no wash, never an empty shell of two rules.
        InputBoxChrome.Box box = InputBoxChrome.decorate(capturedRows,
                capturedInputCursorRow(input, capturedRows, width), width, rows,
                maxInputBoxRows(rows, dockLines.size()), InputBoxChrome.Palette.of(terminal));
        List<AttributedString> inputLines = box.rows();
        int budget = liveTailBudget(rows, inputLines.size(), dockLines.size());
        List<AttributedString> lines = new ArrayList<>(leading);
        for (StripEntry entry : visibleEntries) {
            lines.addAll(entry.render(width));
        }
        if (lines.size() > budget) {
            int overflow = lines.size() - budget;
            List<AttributedString> frozen = new ArrayList<>();
            for (StripEntry entry : visibleEntries) {
                if (frozen.size() >= overflow) {
                    break;
                }
                List<AttributedString> rowsFrozen = entry.freezeTopRows(overflow - frozen.size(), width);
                if (!rowsFrozen.isEmpty()) {
                    frozen.addAll(rowsFrozen);
                    commit(entry);
                }
            }
            clearLiveRegion();
            emitFrozenBatch(frozen);
            if (visibleEntries == entries) {
                // Foldables are committed on add now (issue #5B), so this overflow-freeze path is only
                // ever hit by a live answer tail; the sweep is kept harmless for any stray foldable.
                entries.removeIf(entry -> entry instanceof LiveFoldableBlock block
                        && block.isFrozen());
            }
            installFreshDisplay(width, rows);
            lines.clear();
            lines.addAll(leading);
            for (StripEntry entry : visibleEntries) {
                lines.addAll(entry.render(width));
            }
            if (lines.size() > budget) {
                lines = new ArrayList<>(lines.subList(lines.size() - budget, lines.size()));
            }
        }
        int inputRowOffset = lines.size();
        lines.addAll(inputLines);
        lines.addAll(dockLines);
        // The chrome shifts the input rows down past the top rule, and back up again when a tall
        // buffer scrolls inside the box; the cursor moves with them or it points at a rule.
        lastCursorPos = composedCursorPos(input, inputRowOffset + box.cursorRowShift(), width);
        display.update(hardTerminated(lines), lastCursorPos, false);
        lastRegionRows = lines.size();
    }

    /**
     * 给活区的每一行补一个 {@code '\n'}，让 JLine 用<b>真换行</b>而不是自动折行把它们接起来。
     *
     * <p>JLine {@code Display} 在 {@code delayedWrapAtEol} 终端（xterm 系，含 Terminal.app）上默认
     * 这样走行与行之间：把光标 CUF 顶到右边缘、在最后一列写<b>下一行的首字符</b>、再靠自动折行落到
     * 下一行（{@code Display.update} 里的 {@code newWrap = !newNL && lineIndex < newLines.size()}
     * 分支）。这正是终端用来表达「这一行是上一行的软折行续行」的编码，于是整个活区在终端眼里是
     * <b>一条 {@code n × width} 的逻辑行</b>。补一个 {@code '\n'} 之后改走 {@code CR + LF} 下行，
     * 每一行都成为自己的逻辑行——与[[冻结回卷]]同一种形态。
     *
     * <p><b>保留，理由重述（ADR 0010 修订 A18，issue 10）。</b>它当初的两个理由之一（让擦除距离
     * 可算）已随擦除算术一起退役；活下来的是另一个，且与擦除距离无关：<b>逻辑行是终端自己的单位</b>。
     * 选中、复制、终端内查找、以及终端自己的重排，处理的都是逻辑行而不是单元格。两种写法画出的
     * <b>单元格完全相同</b>——这就是为什么整套离线门（含 31 条屏幕真相与 372 条 phase16-smoke）
     * 在删掉它之后仍然全绿：{@code document()} 看的是格子，看不见行结构。判据在
     * {@code ScreenTruthRegressionTest.everyLiveRegionRowIsItsOwnLineInWhatTheTerminalGivesBack}
     * 与 {@code aLiveRegionPaintedFromScratchIsAlsoOneLinePerRow}，它们读 {@code historyText()}
     * ——即用户复制走的东西。删掉本方法这两条立刻红：状态坞两行、以及整帧重画时的 spinner 行与
     * [[输入框]]行，都被并成一条逻辑行。
     */
    private static List<AttributedString> hardTerminated(List<AttributedString> lines) {
        List<AttributedString> terminated = new ArrayList<>(lines.size());
        for (AttributedString line : lines) {
            terminated.add(new AttributedStringBuilder()
                    .append(line)
                    .append('\n')
                    .toAttributedString());
        }
        return terminated;
    }

    private void emitFrozenBatch(List<AttributedString> frozen) {
        if (frozen.isEmpty()) {
            return;
        }
        StringBuilder batch = new StringBuilder();
        for (AttributedString line : frozen) {
            batch.append(line.toAnsi(terminal)).append('\n');
        }
        capturedWriter.print(batch);
    }

    /**
     * Replace the {@code Display} with a fresh one sized to the current geometry.
     *
     * <p>A fresh instance is used rather than {@link Display#resize} whenever anything has been
     * written below the previous region, because {@code resize} rebuilds its line model from the old
     * lines and would then diff against a screen that no longer holds them.
     */
    private void installFreshDisplay(int width, int rows) {
        display = newDisplay();
        display.resize(rows, width);
        displaySized = true;
        lastRegionRows = 0;
    }

    private Display newDisplay() {
        Display next = new Display(displayTerminal, false);
        next.setDelayLineWrap(true);
        return next;
    }

    /**
     * Move every finished line of the live streaming answer(s) into the scrollback batch, advancing
     * each segment's frozen pointer so its own {@code render()} then returns only the still-growing
     * tail. Completed lines become immutable terminal scrollback that is never repainted, so a wheel
     * scroll can no longer smear the body of a long answer — only the one-row tail lives in the
     * repainted Display. Foldables, the approval card, and the activity spinner are untouched.
     */
    private void drainCompletedAnswerRows(int width) {
        for (StripEntry entry : entries) {
            if (entry instanceof LiveAnswer answer) {
                List<AttributedString> rows = answer.freezeTopRows(Integer.MAX_VALUE, width);
                if (!rows.isEmpty()) {
                    queueRows(rows);
                    commit(answer);
                }
                // Once the answer is finished and fully drained, append the single inter-block
                // separator every foldable already emits (add(LiveFoldableBlock) / freezeSettled).
                // Without it, answer→tool / answer→diff boundaries collapsed to zero blanks while
                // tool→thinking kept one, so block spacing looked inconsistent (the screenshot bug).
                if (answer.claimTrailingSeparator(width)) {
                    queueSeparatorBlank();
                    commit(answer);
                }
            }
        }
    }

    private void applyFinishCutoff() {
        entries.removeIf(finishCutoff::contains);
        finishCutoff.clear();
        finishTurnRequested = false;
    }

    private Terminal capturingTerminal(Terminal delegate) {
        return (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[]{Terminal.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("writer")) {
                        return capturedWriter;
                    }
                    if (name.equals("getBufferSize") || name.equals("getSize")) {
                        return geometry.lastSample();
                    }
                    if (name.equals("flush")) {
                        return null;
                    }
                    if (name.equals("puts")) {
                        InfoCmp.Capability capability = (InfoCmp.Capability) args[0];
                        String sequence = delegate.getStringCapability(capability);
                        if (sequence == null) {
                            return false;
                        }
                        Object[] parameters = args.length > 1 && args[1] instanceof Object[] values
                                ? values
                                : new Object[0];
                        Curses.tputs(capturedWriter, sequence, parameters);
                        return true;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @FunctionalInterface
    interface ResizeObserver {
        void observe(long generation, int rows, int columns);
    }
}
