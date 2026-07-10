package com.paicli.render.inline;

/** Immutable counters for deterministic render-budget assertions. */
public record RenderStats(long frames,
                          long writes,
                          long bytes,
                          long flushes,
                          long mergedEvents,
                          long cacheHits,
                          long reasoningCharsProcessed) {

    public String formatLine() {
        return "Render stats: frames=" + frames
                + " writes=" + writes
                + " bytes=" + bytes
                + " flushes=" + flushes
                + " merged=" + mergedEvents
                + " cache_hits=" + cacheHits
                + " reasoning_chars=" + reasoningCharsProcessed;
    }
}
