package com.cafecli.render.inline;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.NonBlockingReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin main-thread key-reading shell around the pure transcript overlay model.
 *
 * <p>It is a <b>modal</b>: for its duration it is the single writer of the terminal (ADR 0010
 * amendment A12), the render loop having handed it the lease at the call site. That is what makes its
 * own resize the easy case — it owns the whole alternate screen, which has no scrollback to preserve,
 * so it clears and repaints unconditionally rather than working out what the terminal reflowed.
 *
 * <p>It browses the ordered commit log — the whole session (A10) — and lays out only the rows its
 * viewport shows, through {@link OverlayDocument}.
 */
final class TranscriptOverlay {

    private static final String ENTER_ALTERNATE_SCREEN = "\u001b[?1049h";
    private static final String LEAVE_ALTERNATE_SCREEN = "\u001b[?1049l";
    private static final String ENABLE_MOUSE_TRACKING = "\u001b[?1000h\u001b[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001b[?1006l\u001b[?1000l";

    // DECSTBM reset (ESC [ r): Cafecli itself no longer installs any scroll region — the status dock
    // is drawn by the render thread as live-region rows (ADR 0014 决策 4) — so this is now purely
    // defensive against a region left behind by whatever ran in this terminal before us.
    private static final String RESET_SCROLL_REGION = ((char) 27) + "[r";

    /**
     * How long a key read waits before the loop looks at the geometry again.
     *
     * <p>The read has to poll rather than block, because a window resized while the overlay is open
     * must be noticed by the overlay itself: nobody else is allowed to write to the terminal, so
     * nobody else can fix its screen.
     */
    private static final long POLL_MILLIS = 50;

    private static final String FOOTER =
            "wheel/j/k/↑/↓ line  PgUp/PgDn page  g/G ends  Ctrl+T thinking  Ctrl+O/q/ESC close";

    private final Terminal terminal;
    private final GeometrySource geometry;
    private final Runnable onInterrupt;

    TranscriptOverlay(Terminal terminal) {
        this(terminal, terminal::getSize);
    }

    /**
     * @param geometry where the overlay learns the terminal's size — sampled, never probed on the
     *                 write path (ADR 0010 amendment A3c), and injected so a resize while the overlay
     *                 is open is drivable without a real window.
     */
    TranscriptOverlay(Terminal terminal, GeometrySource geometry) {
        this(terminal, geometry, () -> { });
    }

    /**
     * @param onInterrupt what a Ctrl+C read inside the overlay means (ADR 0016 D3): stop the running
     *                    turn, and <b>leave the overlay open</b>. The layer has to claim this itself
     *                    once the line editor reads continuously (issue 07). Before that, the mid-turn
     *                    overlay was opened from the turn pump — outside {@code readLine} — so the
     *                    session-level {@code Signal.INT} guard was live and did the job. Resident, the
     *                    overlay always runs as a widget <em>inside</em> {@code readLine}, where JLine
     *                    installs its own INT handler for the whole read and restores the previous one
     *                    only on exit; that handler interrupts the reading thread, so the interrupt
     *                    arrives here as an {@link java.io.InterruptedIOException} out of our own
     *                    {@code read} and the session guard never sees a thing (measured, issue 12).
     */
    TranscriptOverlay(Terminal terminal, GeometrySource geometry, Runnable onInterrupt) {
        this.terminal = terminal;
        this.geometry = geometry;
        this.onInterrupt = onInterrupt == null ? () -> { } : onInterrupt;
    }

    /**
     * Where the reader was when the overlay closed, so that reopening resumes there (ADR 0010
     * amendment A14) — an interruption by an [[审批应答]] costs the reader nothing.
     *
     * <p>It carries a row <em>and</em> whether that row was the tail, because at the tail the position
     * <b>is</b> the following mode (A13): a reader who was watching output arrive resumes watching it,
     * rather than being returned to the rows that happened to be the end when they were interrupted.
     */
    record ReadingPosition(int viewportStart, boolean following) {
        /** The default: no place kept, so the overlay opens where the session is now. */
        static final ReadingPosition TAIL = new ReadingPosition(0, true);
    }

    /** Opens over a fixed snapshot of the log — the shape a caller with nothing left to commit uses. */
    ReadingPosition open(List<StripEntry> items) {
        List<StripEntry> snapshot = items == null ? List.of() : List.copyOf(items);
        return open(() -> snapshot);
    }

    /**
     * Opens over the live commit log.
     *
     * <p>The log is re-read on every poll rather than snapshotted at open (ADR 0010 amendment A13), so
     * output committed while the overlay is up reaches the screen. Pinned at the tail the viewport
     * advances with it; scrolled up it holds, so browsing is never yanked.
     */
    ReadingPosition open(java.util.function.Supplier<List<StripEntry>> source) {
        return open(source, () -> false);
    }

    /** Opens at the tail, closing again if {@code approvalPending} ever answers true. */
    ReadingPosition open(java.util.function.Supplier<List<StripEntry>> source,
                         java.util.function.BooleanSupplier approvalPending) {
        return open(source, approvalPending, ReadingPosition.TAIL);
    }

    /**
     * Opens over the live commit log at {@code resume}, closing again if {@code approvalPending} ever
     * answers true.
     *
     * @param approvalPending whether an [[审批应答]] is waiting to be answered (ADR 0010 amendment
     *                        A14). It is re-read on every poll, in the same place as the log itself,
     *                        because a modal that holds the input thread is the only thing standing
     *                        between the parked worker and the [[输入框]] that answers it.
     * @param resume          where the reader was when this overlay last closed; the returned position
     *                        is where they are now. The overlay keeps nothing of its own between
     *                        openings — the place is carried out and back in, so the modal stays a
     *                        function of what it is given.
     */
    ReadingPosition open(java.util.function.Supplier<List<StripEntry>> source,
                         java.util.function.BooleanSupplier approvalPending,
                         ReadingPosition resume) {
        java.util.function.Supplier<List<StripEntry>> live =
                source == null ? List::of : source;
        java.util.function.BooleanSupplier interrupted =
                approvalPending == null ? () -> false : approvalPending;
        ReadingPosition resumeAt = resume == null ? ReadingPosition.TAIL : resume;
        List<StripEntry> log = live.get();
        OverlayDocument document = new OverlayDocument(log);
        List<Boolean> foldable = log.stream().map(StripEntry::isFoldable).toList();
        List<Boolean> thinking = log.stream().map(StripEntry::isThinking).toList();
        List<FoldState> retainedStates = log.stream().map(StripEntry::foldState).toList();

        Size size = safeSize();
        int pageSize = pageSizeFor(size);
        TranscriptOverlayModel.State state = TranscriptOverlayModel.start(
                foldable, thinking, retainedStates, pageSize);

        Display display = newDisplay(size);
        Attributes original = null;
        RuntimeException primaryFailure = null;
        try {
            terminal.writer().print(ENTER_ALTERNATE_SCREEN);
            terminal.writer().print(RESET_SCROLL_REGION);
            terminal.writer().flush();
            terminal.flush();
            original = terminal.enterRawMode();
            terminal.writer().print(ENABLE_MOUSE_TRACKING);
            terminal.writer().flush();
            terminal.flush();
            // Laid out only from here on, inside the try: a block that throws while being measured
            // must still leave the alternate screen and restore the terminal's attributes.
            document.layout(bodyWidth(size), state.foldStates(), state.thinkingExpanded());
            // Opening at the tail is what makes following the default rather than a mode to find
            // (A13). It costs one full measure of the document — the same one a jump to the end pays.
            // A reader who was interrupted mid-page is put back on their page instead (A14); one who
            // was at the tail resumes following, because there the position is the mode.
            state = resumeAt.following()
                    ? atTail(document, state)
                    : resumedAt(document, state, resumeAt.viewportStart(), pageSize);
            while (!state.closed()) {
                Size current = safeSize();
                if (current.getColumns() != size.getColumns() || current.getRows() != size.getRows()) {
                    // The modal's own geometry change: it owns the whole alternate screen and there is
                    // no scrollback under it to preserve, so it erases and repaints at the new size
                    // rather than asking where the terminal moved anything. The Display goes with it —
                    // resizing one leaves a ghost line behind (ADR 0010 amendment A18).
                    boolean following = TranscriptOverlayModel.pinnedToTail(state);
                    size = current;
                    pageSize = pageSizeFor(size);
                    document.layout(bodyWidth(size), state.foldStates(), state.thinkingExpanded());
                    state = TranscriptOverlayModel.withPageSize(state, pageSize);
                    state = following
                            ? atTail(document, state)
                            : clamped(state, document.ensureRows(state.viewportStart() + pageSize * 2));
                    display = newDisplay(size);
                    terminal.writer().print(AnsiSeq.ERASE_SCREEN);
                    terminal.writer().flush();
                }
                if (interrupted.getAsBoolean()) {
                    // A pending [[审批应答]] closes the overlay (ADR 0010 amendment A14). It is
                    // answered through the [[输入框]] on this very thread, which the overlay is
                    // holding — so the way to let the user answer is to stop holding it. Nothing is
                    // drawn here: the approval card belongs to the primary screen, and the render
                    // loop draws it there once the modal lease is handed back.
                    state = TranscriptOverlayModel.reduce(
                            state, TranscriptOverlayModel.Action.EXIT);
                    continue;
                }
                state = follow(document, live.get(), state, size, pageSize);
                display.update(render(document, state, pageSize), -1, true);
                terminal.flush();
                String key = readKey();
                TranscriptOverlayModel.Action action =
                        key == null ? null : TranscriptOverlayModel.decode(key);
                if (action != null) {
                    state = advance(document, state, action, pageSize);
                }
            }
            return new ReadingPosition(state.viewportStart(),
                    TranscriptOverlayModel.pinnedToTail(state));
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            try {
                terminal.writer().print(DISABLE_MOUSE_TRACKING);
                terminal.writer().flush();
                terminal.flush();
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
            }
            if (original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (RuntimeException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            try {
                terminal.writer().print(LEAVE_ALTERNATE_SCREEN);
                terminal.writer().flush();
                terminal.flush();
            } catch (RuntimeException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Take whatever the session committed since the last poll — the overlay's live following (ADR 0010
     * amendment A13).
     *
     * <p>The two halves of the decision live in different places on purpose. <i>Whether</i> the document
     * changed is a question about layout and is answered by {@link OverlayDocument#refresh}; <i>where
     * the viewport goes</i> when it did is a question about navigation and is answered by
     * {@link TranscriptOverlayModel#follow}, which has no terminal in it.
     */
    private TranscriptOverlayModel.State follow(OverlayDocument document,
                                                List<StripEntry> latest,
                                                TranscriptOverlayModel.State state,
                                                Size size,
                                                int pageSize) {
        if (!document.refresh(latest)) {
            return state;
        }
        // Only a longer log needs a new catalogue of fold choices. A poll where a still-streaming block
        // merely grew — which is most of them, once per committed line — leaves it alone.
        TranscriptOverlayModel.State catalogued = latest.size() == state.foldStates().size()
                ? state
                : TranscriptOverlayModel.withItems(state,
                        latest.stream().map(StripEntry::isFoldable).toList(),
                        latest.stream().map(StripEntry::isThinking).toList(),
                        latest.stream().map(StripEntry::foldState).toList());
        document.layout(bodyWidth(size), catalogued.foldStates(), catalogued.thinkingExpanded());
        // Pinned, the new end has to be known, which is what makes following cost a measure of what
        // arrived. Browsing, the viewport is not moving, so only the rows it can reach are measured.
        int height = TranscriptOverlayModel.pinnedToTail(catalogued)
                ? document.totalRows()
                : document.ensureRows(catalogued.viewportStart() + pageSize * 2 + 1);
        return TranscriptOverlayModel.follow(catalogued, height);
    }

    /** The viewport showing the end of the fully measured document. */
    private static TranscriptOverlayModel.State atTail(OverlayDocument document,
                                                       TranscriptOverlayModel.State state) {
        return TranscriptOverlayModel.reduce(
                TranscriptOverlayModel.withTotalLines(state, document.totalRows()),
                TranscriptOverlayModel.Action.LAST);
    }

    /**
     * Apply one navigation action, extending the measured document as far as the action can reach
     * first.
     *
     * <p>The document's height is known only for the prefix that has been laid out, and the model
     * clamps against it, so an action that moves the viewport down must be given room to move into.
     * A jump to the end is the one action that needs the real height and pays for measuring it.
     */
    private TranscriptOverlayModel.State advance(OverlayDocument document,
                                                 TranscriptOverlayModel.State state,
                                                 TranscriptOverlayModel.Action action,
                                                 int pageSize) {
        TranscriptOverlayModel.State measured = switch (action) {
            case LAST -> TranscriptOverlayModel.withTotalLines(state, document.totalRows());
            case DOWN, PAGE_DOWN -> TranscriptOverlayModel.withTotalLines(state,
                    document.ensureRows(state.viewportStart() + pageSize * 2 + 1));
            default -> state;
        };
        TranscriptOverlayModel.State reduced = TranscriptOverlayModel.reduce(measured, action);
        if (action != TranscriptOverlayModel.Action.TOGGLE_THINKING) {
            return reduced;
        }
        // Ctrl+T changes every thinking block's height at once, so the document is re-indexed for the
        // new fold mode — cheaply, because each mode keeps its own measured heights.
        document.layout(bodyWidth(safeSize()), reduced.foldStates(), reduced.thinkingExpanded());
        return TranscriptOverlayModel.withTotalLines(reduced,
                document.ensureRows(reduced.viewportStart() + pageSize * 2 + 1));
    }

    /**
     * The viewport back on the row the reader was last on, with the document measured far enough to
     * reach it (ADR 0010 amendment A14).
     *
     * <p>The row is clamped against what the document actually measures now, which is what makes a
     * restored place safe when the log grew or the width changed while the overlay was away.
     */
    private static TranscriptOverlayModel.State resumedAt(OverlayDocument document,
                                                          TranscriptOverlayModel.State state,
                                                          int viewportStart,
                                                          int pageSize) {
        return clamped(TranscriptOverlayModel.withViewportStart(state, viewportStart),
                document.ensureRows(viewportStart + pageSize * 2));
    }

    private static TranscriptOverlayModel.State clamped(TranscriptOverlayModel.State state,
                                                        int totalLines) {
        TranscriptOverlayModel.State measured =
                TranscriptOverlayModel.withTotalLines(state, totalLines);
        int last = Math.max(0, totalLines - measured.pageSize());
        return measured.viewportStart() <= last
                ? measured
                : TranscriptOverlayModel.withViewportStart(measured, last);
    }

    private List<AttributedString> render(OverlayDocument document,
                                          TranscriptOverlayModel.State state,
                                          int pageSize) {
        List<AttributedString> lines =
                new ArrayList<>(document.viewport(state.viewportStart(), pageSize));
        lines.add(new AttributedString(FOOTER));
        return lines;
    }

    private Display newDisplay(Size size) {
        Display display = new Display(terminal, true);
        // Defer writing the bottom-right cell so the last column never triggers an auto-scroll/drift.
        display.setDelayLineWrap(true);
        display.resize(Math.max(1, size.getRows()), Math.max(1, size.getColumns()));
        return display;
    }

    private Size safeSize() {
        Size size = null;
        try {
            size = geometry.get();
        } catch (RuntimeException ignored) {
            // A geometry probe that fails must not close the overlay; fall back to a usable default.
        }
        if (size == null || size.getColumns() <= 0 || size.getRows() <= 0) {
            return new Size(80, 24);
        }
        return size;
    }

    /** The rows the document may use: the screen minus the one-line footer. */
    private static int pageSizeFor(Size size) {
        return Math.max(1, size.getRows() - 1);
    }

    /** Item bodies are laid out two columns narrower, which is the indent every row carries. */
    private static int bodyWidth(Size size) {
        return Math.max(1, size.getColumns() - 2);
    }

    /**
     * The next key, or {@code null} when the poll expired without one.
     *
     * <p>An interrupt is <b>not</b> a key and must not be read as one: it is Ctrl+C, and Ctrl+C stops
     * the turn without taking the screen away from someone mid-read (ADR 0016 D3). Two shapes are
     * handled because the two readers behave differently — a pipe-backed reader throws
     * {@link java.io.InterruptedIOException} out of the blocked wait, while a reader that cannot be
     * interrupted mid-syscall simply returns from its 50 ms poll with the thread's interrupt flag
     * still set. Either way the flag is cleared here: it belongs to this keystroke, not to whatever
     * this thread does next.
     */
    private String readKey() {
        if (Thread.interrupted()) {
            onInterrupt.run();
            return null;
        }
        try {
            String key = readKey(terminal.reader());
            if (Thread.interrupted()) {
                onInterrupt.run();
                return null;
            }
            return key;
        } catch (java.io.InterruptedIOException interrupted) {
            Thread.interrupted();
            onInterrupt.run();
            return null;
        } catch (Exception ignored) {
            return "q";
        }
    }

    static String readKey(NonBlockingReader reader) throws java.io.IOException {
        int first = reader.read(POLL_MILLIS);
        if (first == NonBlockingReader.READ_EXPIRED) {
            return null;
        }
        if (first < 0) {
            return "q";
        }
        StringBuilder key = new StringBuilder().append((char) first);
        if (first != 27) {
            return key.toString();
        }
        while (true) {
            int next = reader.read(25);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return key.toString();
            }
            key.append((char) next);
            if (Character.isLetter(next) || next == '~') {
                return key.toString();
            }
        }
    }
}
