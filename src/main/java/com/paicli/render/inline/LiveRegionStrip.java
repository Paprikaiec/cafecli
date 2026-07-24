package com.paicli.render.inline;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
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
import java.util.function.IntSupplier;

/** Render-thread-owned JLine Display strip. */
final class LiveRegionStrip {

    private static final int RESERVED_ROWS = 3;

    private final Terminal terminal;
    private final Terminal displayTerminal;
    private final StringWriter captured = new StringWriter();
    private final PrintWriter capturedWriter = new PrintWriter(captured, false);
    private Display display;
    private int displayColumns = -1;
    private final List<StripEntry> entries = new ArrayList<>();
    private final Set<StripEntry> finishCutoff =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<StripEntry> answersSeparated =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile LineReader lineReader;
    private RenderLoop renderLoop;
    private StripEntry leadingEntry;
    private final StringBuilder pendingPrompt = new StringBuilder();
    private final List<AttributedString> pendingEvictedRows = new ArrayList<>();
    private final List<AttributedString> pendingFrozenRows = new ArrayList<>();
    private final List<AttributedString> pendingPromptRows = new ArrayList<>();
    private boolean forceDisplayFrame;
    private boolean finishTurnRequested;
    private ApprovalBlock approval;
    private IntSupplier reservedRowsSupplier;

    LiveRegionStrip(Terminal terminal) {
        this.terminal = terminal;
        this.displayTerminal = capturingTerminal(terminal);
        this.display = newDisplay();
    }

    void add(AnswerSegment answer) {
        freezeSettled();
        entries.add(answer);
    }

    /**
     * Commit a completed foldable (reasoning / tool / code / diff) straight to scrollback instead of
     * parking it in the repainted {@code entries} list for a frame.
     *
     * <p>This is the fix for issue #5B (the big blank gap). A foldable that streams live in the JLine
     * {@code Display} region for one or more frames and is only frozen on the next {@code add}/{@code
     * endTurn} produces a visible gap on a real terminal with a live {@code Status} scroll region: the
     * {@code Display} erases the live rows in place (walking the cursor down while emitting {@code
     * ESC[K \r\r\n}), leaving the cursor at the bottom of the now-blank region, and {@code
     * emitFrozenBatch} then re-emits the frozen block <em>below</em> those erased rows. The erased live
     * region becomes an ~N-line blank gap above the re-emitted block. Per ADR 0012 / CONTEXT.md the
     * live tail must contain only the thinking spinner, the streaming answer tail, and the approval
     * card, so foldables must never enter {@code entries} at all.
     *
     * <p>In the real flow the current answer is always finished before a foldable is added (reasoning
     * is published before the answer starts; tools/code/diff come after the answer completes), so
     * committing the foldable immediately here — after {@link #freezeSettled()} has committed any prior
     * settled entry (a completed answer) first — preserves insertion order. A hypothetical still-
     * streaming answer is left live by {@code freezeSettled()} and keeps reflowing in the tail.
     */
    void add(LiveFoldableBlock block) {
        freezeSettled();
        int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
        List<AttributedString> rows = block.freezeTopRows(Integer.MAX_VALUE, width);
        if (!rows.isEmpty()) {
            pendingFrozenRows.addAll(rows);
            pendingFrozenRows.add(AttributedString.EMPTY);   // the single inter-block separator (issue #5A)
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
        int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
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
            } else if (entry instanceof AnswerSegment answer && answer.isComplete()) {
                settled.add(entry);
            }
        }
        for (StripEntry entry : settled) {
            List<AttributedString> rows = entry.freezeTopRows(Integer.MAX_VALUE, width);
            if (!rows.isEmpty()) {
                pendingFrozenRows.addAll(rows);
                pendingFrozenRows.add(AttributedString.EMPTY);
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
        forceDisplayFrame = true;
    }

    void dismissApproval(ApprovalBlock block) {
        if (approval == block) {
            entries.remove(block);
            approval = null;
            forceDisplayFrame = true;
        }
    }

    void bindLineReader(LineReader reader) {
        lineReader = reader;
    }

    void bind(RenderLoop loop) {
        renderLoop = loop;
    }

    void bindLeadingEntry(StripEntry entry) {
        leadingEntry = entry;
    }

    /**
     * Bind the live source of bottom-reserved rows (the JLine {@code Status} dock height). The strip
     * sizes its repaint region as {@code terminalRows - reserved} so its Display never overruns the
     * dock's scroll region; without a supplier it falls back to {@link #RESERVED_ROWS}.
     */
    void bindReservedRows(IntSupplier supplier) {
        reservedRowsSupplier = supplier;
    }

    private int reservedRows() {
        int reserved = reservedRowsSupplier != null ? reservedRowsSupplier.getAsInt() : RESERVED_ROWS;
        // A zero-height dock (never updated) or a nonsensical negative reading means "no real dock":
        // keep the historical guess so the spinner + tail always have room.
        return reserved > 0 ? reserved : RESERVED_ROWS;
    }

    void submitPrompt(String renderedPrompt) {
        if (renderedPrompt != null && !renderedPrompt.isEmpty()) {
            int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
            for (StripEntry entry : entries) {
                pendingPromptRows.addAll(entry.freezeTopRows(Integer.MAX_VALUE, width));
            }
            entries.clear();
            approval = null;
            pendingPrompt.append(renderedPrompt);
        }
    }

    void finishTurn() {
        freezeSettled();
        finishTurnRequested = true;
        for (StripEntry entry : entries) {
            if (entry instanceof AnswerSegment) {
                finishCutoff.add(entry);
            }
        }
    }

    void clear() {
        entries.clear();
        finishCutoff.clear();
        answersSeparated.clear();
        pendingPrompt.setLength(0);
        pendingEvictedRows.clear();
        pendingFrozenRows.clear();
        pendingPromptRows.clear();
        forceDisplayFrame = false;
        finishTurnRequested = false;
        approval = null;
    }

    void evict(List<StripEntry> evicted) {
        if (evicted == null || evicted.isEmpty()) {
            return;
        }
        int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
        for (StripEntry entry : evicted) {
            if (entries.remove(entry)) {
                pendingEvictedRows.addAll(entry.renderRetained(width));
                entry.releaseRenderCache();
            }
        }
    }

    String renderFrame() {
        captured.getBuffer().setLength(0);
        LineReader reader = readingReader();
        boolean forceDisplay = forceDisplayFrame;
        forceDisplayFrame = false;
        if (reader != null && !forceDisplay) {
            StringBuilder batch = new StringBuilder();
            int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
            appendPendingEvictions(batch);
            appendRows(batch, pendingFrozenRows);
            pendingFrozenRows.clear();
            appendRows(batch, pendingPromptRows);
            pendingPromptRows.clear();
            for (StripEntry entry : entries) {
                batch.append(entry.drainPrintAbove(terminal, width));
                // Idle background-push path: mirror the single inter-block separator the Display path's
                // drainCompletedAnswerRows and every foldable already emit. Answers stream here as raw
                // source with no trailing blank, so answer→tool / answer→diff would otherwise collapse
                // to zero blanks while foldables keep one. Append exactly one blank line once the answer
                // is fully printed (deduped), normalizing the line terminator first so it is a genuine
                // blank row whether or not the answer source ended in a newline.
                if (entry instanceof AnswerSegment answer
                        && answer.isFullyPrintedWithContent()
                        && answersSeparated.add(answer)) {
                    if (batch.length() > 0 && batch.charAt(batch.length() - 1) != '\n') {
                        batch.append('\n');
                    }
                    batch.append('\n');
                }
            }
            batch.append(pendingPrompt);
            pendingPrompt.setLength(0);
            if (!batch.isEmpty()) {
                String payload = externalPayload(batch.toString());
                reader.printAbove(payload);
                recordExternalWrite(payload);
            }
            entries.removeIf(entry -> entry instanceof AnswerSegment answer
                    && answer.fullyPrintedAndFinished());
            if (finishTurnRequested) {
                applyFinishCutoff();
            }
            return "";
        }
        int width = Math.max(1, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = Math.max(1, TerminalCapabilities.safeSize(terminal).getRows() - reservedRows());
        // Commit already-completed lines of the live streaming answer straight to scrollback each
        // frame, leaving only the growing tail in the repainted Display region. A long answer/table
        // is what previously filled the whole live region and smeared across the screen on a wheel
        // scroll; once its finished lines are immutable scrollback, only the ~1-row tail can tear.
        drainCompletedAnswerRows(width);
        if (displayColumns >= 0 && displayColumns != width) {
            display.update(List.of(), -1, false);
            display = newDisplay();
        }
        displayColumns = width;
        display.resize(rows, width);
        if (!pendingEvictedRows.isEmpty() || !pendingFrozenRows.isEmpty()) {
            display.update(List.of(), -1, false);
            emitFrozenBatch(pendingEvictedRows);
            pendingEvictedRows.clear();
            emitFrozenBatch(pendingFrozenRows);
            pendingFrozenRows.clear();
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
        }
        if (!pendingPrompt.isEmpty()) {
            display.update(List.of(), -1, false);
            emitFrozenBatch(pendingPromptRows);
            pendingPromptRows.clear();
            capturedWriter.print(pendingPrompt);
            pendingPrompt.setLength(0);
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
        }

        List<AttributedString> leading = leadingEntry == null
                ? List.of() : leadingEntry.render(width);
        List<StripEntry> finishing = finishTurnRequested && !finishCutoff.isEmpty()
                ? entries.stream().filter(finishCutoff::contains).toList()
                : List.of();
        boolean finishingHasContent = finishing.stream()
                .anyMatch(entry -> !entry.render(width).isEmpty());
        if (finishingHasContent) {
            renderEntries(leading, finishing, width, rows);
            capturedWriter.print('\n');
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
            applyFinishCutoff();
            renderEntries(List.of(), entries, width, rows);
        } else {
            // The finishing answer's lines were already committed to scrollback incrementally by
            // drainCompletedAnswerRows, so there is nothing left to freeze and no extra separator
            // to print; just drop the drained segments and repaint whatever remains live.
            renderEntries(leading, entries, width, rows);
            if (finishTurnRequested) {
                applyFinishCutoff();
            }
        }
        capturedWriter.flush();
        return captured.toString();
    }

    private void renderEntries(List<AttributedString> leading,
                               List<? extends StripEntry> visibleEntries,
                               int width,
                               int rows) {
        List<AttributedString> lines = new ArrayList<>(leading);
        for (StripEntry entry : visibleEntries) {
            lines.addAll(entry.render(width));
        }
        if (lines.size() > rows) {
            int overflow = lines.size() - rows;
            List<AttributedString> frozen = new ArrayList<>();
            for (StripEntry entry : visibleEntries) {
                if (frozen.size() >= overflow) {
                    break;
                }
                frozen.addAll(entry.freezeTopRows(overflow - frozen.size(), width));
            }
            display.update(List.of(), -1, false);
            emitFrozenBatch(frozen);
            if (visibleEntries == entries) {
                // Foldables are committed on add now (issue #5B), so this overflow-freeze path is only
                // ever hit by a live answer tail; the sweep is kept harmless for any stray foldable.
                entries.removeIf(entry -> entry instanceof LiveFoldableBlock block
                        && block.isFrozen());
            }
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
            lines.clear();
            lines.addAll(leading);
            for (StripEntry entry : visibleEntries) {
                lines.addAll(entry.render(width));
            }
            if (lines.size() > rows) {
                lines = new ArrayList<>(lines.subList(lines.size() - rows, lines.size()));
            }
        }
        display.update(lines, -1, false);
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

    private void appendPendingEvictions(StringBuilder batch) {
        appendRows(batch, pendingEvictedRows);
        pendingEvictedRows.clear();
    }

    private void appendRows(StringBuilder batch, List<AttributedString> rows) {
        for (AttributedString line : rows) {
            batch.append(line.toAnsi(terminal)).append('\n');
        }
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
            if (entry instanceof AnswerSegment answer) {
                pendingFrozenRows.addAll(answer.freezeTopRows(Integer.MAX_VALUE, width));
                // Once the answer is finished and fully drained, append the single inter-block
                // separator every foldable already emits (add(LiveFoldableBlock) / freezeSettled).
                // Without it, answer→tool / answer→diff boundaries collapsed to zero blanks while
                // tool→thinking kept one, so block spacing looked inconsistent (the screenshot bug).
                // The dedup set keeps it to exactly one even though drain runs every frame.
                if (answer.isFullyFrozen(width) && answersSeparated.add(answer)) {
                    pendingFrozenRows.add(AttributedString.EMPTY);
                }
            }
        }
    }

    private void applyFinishCutoff() {
        entries.removeIf(finishCutoff::contains);
        finishCutoff.clear();
        finishTurnRequested = false;
    }

    private void recordExternalWrite(String payload) {
        if (renderLoop != null) {
            renderLoop.recordExternalWrite(payload);
        }
    }

    private String externalPayload(String payload) {
        return renderLoop == null ? payload : renderLoop.externalPayload(payload);
    }

    private LineReader readingReader() {
        LineReader reader = lineReader;
        if (reader == null) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException ignored) {
            return null;
        }
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
                        return TerminalCapabilities.safeSize(delegate);
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
}
