package com.cafecli.render.inline;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The [[转写浏览层]]'s layout of the ordered commit log: it lays out <b>only the rows the viewport
 * shows</b> (ADR 0010 amendment A10).
 *
 * <p>This is what makes whole-session retention deliverable. The overlay used to render the entire
 * document and slice the viewport out of it on every keystroke; at five retained turns that was
 * invisible, and at session length it rebuilds a five-figure row list per arrow-key press. Here an
 * item is laid out when the viewport reaches it, its height is remembered, and the rows are thrown
 * away again once it leaves the working set — so what grows with session length is one {@code int} per
 * item, not a row list.
 *
 * <p>It is deliberately <b>not</b> the virtualization ADR 0010 A5 excludes: there is no session-store
 * paging, no semantic-anchor positioning, and no layout cache keyed by block revision. The viewport is
 * an absolute row range, exactly as before; the only change is that rows outside it are never built.
 *
 * <p>Heights are measured lazily and forwards, so the document's height is known only for the prefix
 * that has been measured. That is enough for scroll clamping — the overlay extends the measurement
 * before it moves the viewport down, and jumping to the end measures the rest — and it is what keeps
 * opening the overlay independent of how long the session has run.
 */
final class OverlayDocument {

    /**
     * The ordered commit log, oldest first — the whole session (ADR 0010 amendment A10).
     *
     * <p>Not immutable: the overlay is open while the session runs (A13), so the log grows underneath
     * it and {@link #refresh(List)} takes the additions.
     *
     * <p>Its elements are <b>positions</b>, not whole entries (ADR 0016 D5): an answer interrupted by
     * a [[转向消息]] appears as two of them with the echo between, which is what makes this layer show
     * a correction where it landed rather than after the whole answer. A position that is still open
     * answers yes to {@link StripEntry#isStillCommitting()} and closes when something else is
     * committed, so the ordinary re-measure path below picks the change up — that is the same
     * mechanism live follow already used, not a new one.
     */
    private final List<StripEntry> items;

    /** Per fold mode: the height of every measured item, and where each one starts. */
    private final Map<Boolean, Index> indexes = new HashMap<>();

    /** Items whose rendered rows the overlay is currently showing; everything else is released. */
    private final Set<StripEntry> workingSet = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * <b>The overlay's own</b> rendering of the entries it lays out (ADR 0016 D1). It used to render
     * through the entries themselves and evict by calling {@code releaseRenderCache()} on them — which,
     * for the answer the render thread was streaming, threw away the [[活区]]'s markdown state from the
     * input thread. Releasing here is a purely local operation and is correct by construction.
     */
    private final RetainedRenderCache cache = new RetainedRenderCache();

    private int width = -1;
    private boolean thinkingExpanded;
    private List<FoldState> foldStates = List.of();

    OverlayDocument(List<StripEntry> items) {
        this.items = new ArrayList<>(items == null ? List.of() : items);
    }

    private static final class Index {
        private int[] height;
        /** {@code start[i]} is the first row of item {@code i}; valid for {@code i <= measured}. */
        private int[] start;
        /** Items measured while they were still streaming, so their height may already be stale. */
        private final java.util.BitSet unsettled = new java.util.BitSet();
        private int measured;

        private Index(int itemCount) {
            this.height = new int[itemCount];
            this.start = new int[itemCount + 1];
        }

        private void grow(int itemCount) {
            if (height.length < itemCount) {
                height = java.util.Arrays.copyOf(height, itemCount);
                start = java.util.Arrays.copyOf(start, itemCount + 1);
            }
        }
    }

    int itemCount() {
        return items.size();
    }

    /**
     * Take the commit log again, absorbing whatever the session committed since — the overlay's live
     * following (ADR 0010 amendment A13). Returns whether the document changed.
     *
     * <p>Two things can change it, and they cost differently. New items are <b>appended</b>, so nothing
     * already measured is touched: the cost is the new items alone, and only if the viewport reaches
     * them. A block that has not finished committing has no settled height, so it is re-measured and
     * everything after it shifts — which is one render of one block, because that is how many blocks
     * stream at once.
     *
     * <p>There is deliberately no revision key on the items (A10 excludes a layout cache keyed by block
     * revision): whether a still-streaming block grew is answered by measuring it, which is the same
     * work as rendering it and needs no cooperation from the block.
     */
    boolean refresh(List<StripEntry> latest) {
        List<StripEntry> log = latest == null ? List.of() : latest;
        boolean changed = false;
        if (log.size() < items.size() || !sharesPrefix(log)) {
            // The log was dropped and rebuilt (/clear): nothing measured still describes it.
            items.clear();
            items.addAll(log);
            indexes.clear();
            releaseWorkingSet();
            return true;
        }
        if (log.size() > items.size()) {
            for (int item = items.size(); item < log.size(); item++) {
                items.add(log.get(item));
            }
            indexes.values().forEach(index -> index.grow(items.size()));
            changed = true;
        }
        return remeasureUnsettled() || changed;
    }

    private boolean sharesPrefix(List<StripEntry> log) {
        for (int item = 0; item < items.size(); item++) {
            if (log.get(item) != items.get(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-measure every item whose height was taken while it was still streaming, and re-base the row
     * offsets after the earliest one that moved.
     */
    private boolean remeasureUnsettled() {
        boolean changed = false;
        for (Index index : indexes.values()) {
            int earliest = -1;
            for (int item = index.unsettled.nextSetBit(0); item >= 0 && item < index.measured;
                    item = index.unsettled.nextSetBit(item + 1)) {
                if (!items.get(item).isStillCommitting()) {
                    index.unsettled.clear(item);
                }
                int height = measure(item);
                if (height != index.height[item]) {
                    index.height[item] = height;
                    earliest = earliest < 0 ? item : earliest;
                }
            }
            if (earliest >= 0) {
                for (int item = earliest; item < index.measured; item++) {
                    index.start[item + 1] = index.start[item] + index.height[item];
                }
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Point the document at a geometry and a set of fold choices.
     *
     * <p>A width change invalidates every measured height, which is why the overlay re-measures rather
     * than reflowing what it already drew: the same source at a different width is a different number
     * of rows, and only the source knows how many.
     */
    void layout(int bodyWidth, List<FoldState> states, boolean thinkingRevealed) {
        int columns = Math.max(1, bodyWidth);
        if (columns != width) {
            width = columns;
            indexes.clear();
            releaseWorkingSet();
        }
        this.foldStates = states == null ? List.of() : states;
        this.thinkingExpanded = thinkingRevealed;
    }

    /** Rows the document is known to have so far — the measured prefix. */
    int knownRows() {
        Index index = index();
        return index.start[index.measured];
    }

    /** Whether every item has been measured, so {@link #knownRows()} is the document's real height. */
    boolean fullyMeasured() {
        return index().measured == items.size();
    }

    /**
     * Measure forwards until the document is known to be at least {@code rows} tall, or until there is
     * nothing left to measure. Returns the rows now known.
     */
    int ensureRows(int rows) {
        Index index = index();
        while (index.measured < items.size() && index.start[index.measured] < rows) {
            measureNext(index);
        }
        return index.start[index.measured];
    }

    /**
     * The document's real height, measuring whatever is left.
     *
     * <p>Only a jump to the end needs this: it is the one navigation that cannot be answered without
     * knowing where the end is. Everything measured on the way is remembered, so a second jump at the
     * same width and fold mode costs nothing.
     */
    int totalRows() {
        Index index = index();
        while (index.measured < items.size()) {
            measureNext(index);
        }
        return index.start[index.measured];
    }

    /**
     * The rows in {@code [fromRow, fromRow + count)}, laying out only the items that overlap them.
     *
     * <p>Items rendered for this viewport become the working set; whatever left it has its rendered
     * rows released, so the memory the overlay holds tracks the retained <b>source</b> rather than
     * everything the user has scrolled past.
     */
    List<AttributedString> viewport(int fromRow, int count) {
        if (count <= 0 || items.isEmpty()) {
            releaseAllButWorkingSet(Set.of());
            return List.of();
        }
        int start = Math.max(0, fromRow);
        ensureRows(start + count);
        Index index = index();
        List<AttributedString> rows = new ArrayList<>(count);
        Set<StripEntry> nowShowing = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int item = itemAtRow(index, start); item < index.measured && rows.size() < count; item++) {
            if (index.height[item] == 0) {
                continue;
            }
            nowShowing.add(items.get(item));
            List<AttributedString> itemRows = renderItem(item);
            int skip = Math.max(0, start - index.start[item]);
            for (int row = skip; row < itemRows.size() && rows.size() < count; row++) {
                rows.add(itemRows.get(row));
            }
        }
        releaseAllButWorkingSet(nowShowing);
        return rows;
    }

    /** The item whose rows contain {@code row}, by binary search over the measured prefix. */
    private static int itemAtRow(Index index, int row) {
        int low = 0;
        int high = index.measured;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (index.start[middle + 1] <= row) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private Index index() {
        return indexes.computeIfAbsent(thinkingExpanded, ignored -> new Index(items.size()));
    }

    private void measureNext(Index index) {
        int item = index.measured;
        int height = measure(item);
        index.height[item] = height;
        index.start[item + 1] = index.start[item] + height;
        index.measured = item + 1;
        if (items.get(item).isStillCommitting()) {
            // Measured mid-flight: this height is a snapshot of a block that is still growing, so a
            // later refresh has to take it again.
            index.unsettled.set(item);
        }
    }

    private int measure(int item) {
        int height = renderItem(item).size();
        if (!workingSet.contains(items.get(item))) {
            // Measuring is a render, and a render fills this view's row cache. An item measured on the
            // way past is not being shown, so its rows go straight back out again — this view's rows,
            // and only this view's.
            cache.release(items.get(item));
        }
        return height;
    }

    /**
     * One item's rows at the current width, indented two columns and followed by the same single
     * separator blank a sealed item gets in [[冻结回卷]], so the overlay reads like the screen does.
     */
    private List<AttributedString> renderItem(int item) {
        StripEntry entry = items.get(item);
        FoldState fold = item < foldStates.size() ? foldStates.get(item) : FoldState.COLLAPSED;
        List<AttributedString> source = cache.rows(entry, width, fold);
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<AttributedString> rows = new ArrayList<>(source.size() + 1);
        for (AttributedString line : source) {
            rows.add(new AttributedStringBuilder().append("  ").append(line).toAttributedString());
        }
        if (entry.isReplaySealed()) {
            rows.add(AttributedString.EMPTY);
        }
        return rows;
    }

    /** Rendered rows <b>this view</b> is holding. Nobody else's, by construction (ADR 0016 D1). */
    int cachedRows() {
        return cache.cachedRows();
    }

    /** Items <b>this view</b> is holding rendered rows for — its working set. */
    int cachedItems() {
        return cache.cachedEntries();
    }

    /** Whether <b>this view</b> is holding rendered rows for {@code entry}. */
    boolean holdsRowsFor(StripEntry entry) {
        return cache.holds(entry);
    }

    private void releaseAllButWorkingSet(Set<StripEntry> keep) {
        cache.retainOnly(keep);
        workingSet.clear();
        workingSet.addAll(keep);
    }

    private void releaseWorkingSet() {
        releaseAllButWorkingSet(Set.of());
    }
}
