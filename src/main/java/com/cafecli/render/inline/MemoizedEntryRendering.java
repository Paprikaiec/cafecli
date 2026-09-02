package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The default {@link EntryRendering}: lay the entry's immutable source out on demand and remember the
 * rows per (width, fold state) (ADR 0016 D1).
 *
 * <p>This is where the memoization that used to sit on the blocks themselves went. Keeping it here
 * rather than on the entry is what makes it safe: the map belongs to one view on one thread, and the
 * eviction a view does when something leaves its viewport touches nothing anyone else is using.
 *
 * <p>An entry that is <b>still committing</b> is rendered afresh every time, because its rows are the
 * thing that is changing; there is at most one such entry (the streaming answer), and it supplies its
 * own incremental rendering rather than using this one.
 */
final class MemoizedEntryRendering implements EntryRendering {

    private final StripEntry entry;
    private final Map<RenderKey, List<AttributedString>> rowsByKey = new HashMap<>();

    MemoizedEntryRendering(StripEntry entry) {
        this.entry = entry;
    }

    @Override
    public List<AttributedString> rows(int width, FoldState foldState) {
        int columns = Math.max(1, width);
        if (entry.isStillCommitting()) {
            return entry.renderRetained(columns, foldState);
        }
        return rowsByKey.computeIfAbsent(new RenderKey(columns, foldState),
                key -> entry.renderRetained(key.width(), key.foldState()));
    }

    @Override
    public int cachedRows() {
        return rowsByKey.values().stream().mapToInt(List::size).sum();
    }

    private record RenderKey(int width, FoldState foldState) {
    }
}
