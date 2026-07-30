package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.List;

/** Render-thread-owned foldable block whose rows are memoized by width and fold state. */
class LiveFoldableBlock implements StripEntry {

    private final Terminal terminal;
    private final MemoizedBlock content;
    private boolean expanded;
    private boolean frozen;
    private boolean renderedLive;
    private boolean printedAbove;

    LiveFoldableBlock(Terminal terminal,
                      List<AttributedString> collapsedLines,
                      List<AttributedString> expandedLines,
                      boolean initiallyExpanded) {
        this(terminal, collapsedLines, expandedLines, initiallyExpanded,
                MemoizedBlock.LineLayout.WRAP);
    }

    LiveFoldableBlock(Terminal terminal,
                      List<AttributedString> collapsedLines,
                      List<AttributedString> expandedLines,
                      boolean initiallyExpanded,
                      MemoizedBlock.LineLayout lineLayout) {
        this.terminal = terminal;
        this.content = new MemoizedBlock(collapsedLines, expandedLines, lineLayout);
        this.expanded = initiallyExpanded;
    }

    @Override
    public List<AttributedString> render(int width) {
        if (frozen) {
            return List.of();
        }
        renderedLive = true;
        return content.render(terminal, width, expanded);
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return content.render(terminal, width, expanded);
    }

    @Override
    public List<AttributedString> render(int width, FoldState foldState) {
        return content.render(terminal, width, foldState == FoldState.EXPANDED);
    }

    @Override
    public boolean isFoldable() {
        return true;
    }

    @Override
    public FoldState foldState() {
        return expanded ? FoldState.EXPANDED : FoldState.COLLAPSED;
    }

    @Override
    public void persistFoldState(FoldState foldState) {
        expanded = foldState == FoldState.EXPANDED;
    }

    boolean isFrozen() {
        return frozen;
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        if (minimumRows <= 0 || frozen) {
            return List.of();
        }
        List<AttributedString> frozenRows = content.render(terminal, width, expanded);
        frozen = true;
        return frozenRows;
    }

    @Override
    public String drainPrintAbove(Terminal terminal, int width) {
        if (renderedLive || printedAbove || frozen) {
            return "";
        }
        StringBuilder batch = new StringBuilder();
        for (AttributedString line : content.render(this.terminal, width, expanded)) {
            batch.append(line.toAnsi(terminal)).append('\n');
        }
        printedAbove = true;
        frozen = true;
        return batch.toString();
    }

    @Override
    public int renderCacheEntries() {
        return content.cacheEntryCount();
    }

    int renderCacheHits() {
        return content.cacheHits();
    }

    @Override
    public int releaseRenderCache() {
        return content.releaseRenderCache();
    }
}
