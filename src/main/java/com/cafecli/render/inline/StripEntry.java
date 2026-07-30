package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.List;

/** One ordered render-thread-owned entry in the bounded live strip. */
interface StripEntry {

    List<AttributedString> render(int width);

    /** Source-model render contract used by retained consumers with an explicit fold choice. */
    default List<AttributedString> render(int width, FoldState foldState) {
        return renderRetained(width);
    }

    default List<AttributedString> renderRetained(int width) {
        return render(width);
    }

    default List<AttributedString> renderRetained(int width, FoldState foldState) {
        return render(width, foldState);
    }

    default boolean isFoldable() {
        return false;
    }

    /** Thinking blocks stay collapsed by default in the transcript overlay (Ctrl+T reveals them). */
    default boolean isThinking() {
        return false;
    }

    default FoldState foldState() {
        return FoldState.COLLAPSED;
    }

    default void persistFoldState(FoldState foldState) {
    }

    List<AttributedString> freezeTopRows(int minimumRows, int width);

    default String drainPrintAbove(Terminal terminal, int width) {
        return "";
    }

    default int renderCacheEntries() {
        return 0;
    }

    default int releaseRenderCache() {
        return 0;
    }
}
