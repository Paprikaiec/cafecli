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
    private volatile LineReader lineReader;
    private RenderLoop renderLoop;
    private StripEntry leadingEntry;
    private final StringBuilder pendingNotice = new StringBuilder();
    private final StringBuilder pendingPrompt = new StringBuilder();
    private final List<AttributedString> pendingEvictedRows = new ArrayList<>();
    private final List<AttributedString> pendingPromptRows = new ArrayList<>();
    private boolean forceDisplayFrame;
    private boolean frozenFoldableAvailable;
    private boolean finishTurnRequested;
    private ApprovalBlock approval;

    LiveRegionStrip(Terminal terminal) {
        this.terminal = terminal;
        this.displayTerminal = capturingTerminal(terminal);
        this.display = newDisplay();
    }

    void add(AnswerSegment answer) {
        entries.add(answer);
    }

    void add(LiveFoldableBlock block) {
        entries.add(block);
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

    void toggleLastFoldable() {
        boolean sawFrozen = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof LiveFoldableBlock block) {
                if (!block.isFrozen()) {
                    block.toggle();
                    forceDisplayFrame = true;
                    return;
                }
                sawFrozen = true;
            }
        }
        if ((sawFrozen || frozenFoldableAvailable) && pendingNotice.isEmpty()) {
            pendingNotice.append("Block frozen; view via Ctrl+T\n");
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
        if (entries.stream().anyMatch(LiveFoldableBlock.class::isInstance)) {
            // Keep the ordered reasoning/answer strip addressable through the idle Ctrl+O window.
            // The next submitted prompt freezes the complete strip in source order.
            finishTurnRequested = false;
            return;
        }
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
        pendingNotice.setLength(0);
        pendingPrompt.setLength(0);
        pendingEvictedRows.clear();
        pendingPromptRows.clear();
        forceDisplayFrame = false;
        frozenFoldableAvailable = false;
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
            appendRows(batch, pendingPromptRows);
            pendingPromptRows.clear();
            for (StripEntry entry : entries) {
                batch.append(entry.drainPrintAbove(terminal, width));
            }
            batch.append(pendingNotice);
            pendingNotice.setLength(0);
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
        int rows = Math.max(1, TerminalCapabilities.safeSize(terminal).getRows() - RESERVED_ROWS);
        if (displayColumns >= 0 && displayColumns != width) {
            display.update(List.of(), -1, false);
            display = newDisplay();
        }
        displayColumns = width;
        display.resize(rows, width);
        if (!pendingEvictedRows.isEmpty()) {
            display.update(List.of(), -1, false);
            emitFrozenBatch(pendingEvictedRows);
            pendingEvictedRows.clear();
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
        }
        if (!pendingNotice.isEmpty()) {
            display.update(List.of(), -1, false);
            capturedWriter.print(pendingNotice);
            pendingNotice.setLength(0);
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
        if (finishTurnRequested && !finishCutoff.isEmpty()) {
            List<StripEntry> finishing = entries.stream()
                    .filter(finishCutoff::contains)
                    .toList();
            renderEntries(leading, finishing, width, rows);
            capturedWriter.print('\n');
            display = newDisplay();
            displayColumns = width;
            display.resize(rows, width);
            applyFinishCutoff();
            renderEntries(List.of(), entries, width, rows);
        } else {
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
                boolean removed = entries.removeIf(entry -> entry instanceof LiveFoldableBlock block
                        && block.isFrozen());
                frozenFoldableAvailable = frozenFoldableAvailable || removed;
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
