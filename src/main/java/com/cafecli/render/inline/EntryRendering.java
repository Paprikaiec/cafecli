package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.List;

/**
 * One view's private rendering of one retained entry's immutable source (ADR 0016 D1).
 *
 * <p>Entries in the ordered commit log expose source; turning that source into rows at a width, and
 * whatever is cached to avoid doing it twice, belongs to whoever is showing it. Every
 * {@link StripEntry#newRendering()} call returns a <b>new</b> one, so two views never share state,
 * and a view releasing what has left its viewport can never damage another view's.
 *
 * <p>Not thread-safe, deliberately: an instance belongs to exactly one view, and therefore to exactly
 * one thread.
 */
interface EntryRendering {

    List<AttributedString> rows(int width, FoldState foldState);

    /** Rendered rows this view is currently holding for the entry. */
    default int cachedRows() {
        return 0;
    }
}
