package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One view's cache of {@link EntryRendering}s, keyed by entry <b>identity</b> (ADR 0016 D1).
 *
 * <p>Whoever holds one of these owns everything in it. Releasing an entry drops this view's rows for
 * it and nobody else's, which is the whole point: the [[转写浏览层]] used to evict entries that left
 * its working set by calling {@code releaseRenderCache()} on the block, and for the streaming answer
 * that threw away the [[活区]]'s markdown state on the render thread.
 *
 * <p>Not thread-safe, deliberately — an instance belongs to one view and therefore to one thread.
 */
final class RetainedRenderCache {

    private final Map<StripEntry, EntryRendering> renderings = new IdentityHashMap<>();

    List<AttributedString> rows(StripEntry entry, int width, FoldState foldState) {
        return renderings.computeIfAbsent(entry, StripEntry::newRendering).rows(width, foldState);
    }

    /** Drop this view's rows for {@code entry}. */
    void release(StripEntry entry) {
        renderings.remove(entry);
    }

    /** Drop this view's rows for everything except {@code keep} (an identity set). */
    void retainOnly(Set<StripEntry> keep) {
        renderings.keySet().removeIf(entry -> !keep.contains(entry));
    }

    /** Rendered rows this view is currently holding, summed over every entry. */
    int cachedRows() {
        return renderings.values().stream().mapToInt(EntryRendering::cachedRows).sum();
    }

    /** Whether this view is currently holding rendered rows for {@code entry}. */
    boolean holds(StripEntry entry) {
        EntryRendering rendering = renderings.get(entry);
        return rendering != null && rendering.cachedRows() > 0;
    }

    /** Entries this view is currently holding rows for. */
    int cachedEntries() {
        return (int) renderings.values().stream().filter(rendering -> rendering.cachedRows() > 0)
                .count();
    }
}
