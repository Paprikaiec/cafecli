package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.List;

/**
 * A foldable block of immutable styled source, laid out per width and fold state on demand.
 *
 * <p>It holds no rendered rows (ADR 0016 D1): whichever view asked for them caches them
 * ({@link EntryRendering}). Live, the block is committed to [[冻结回卷]] on the frame it is added, so
 * it is laid out roughly once anyway; retained, it is laid out by the view that is showing it.
 */
class LiveFoldableBlock implements StripEntry {

    private final Terminal terminal;
    private final BlockSource content;
    private boolean expanded;
    private boolean frozen;

    LiveFoldableBlock(Terminal terminal,
                      List<AttributedString> collapsedLines,
                      List<AttributedString> expandedLines,
                      boolean initiallyExpanded) {
        this(terminal, collapsedLines, expandedLines, initiallyExpanded,
                BlockSource.LineLayout.WRAP);
    }

    LiveFoldableBlock(Terminal terminal,
                      List<AttributedString> collapsedLines,
                      List<AttributedString> expandedLines,
                      boolean initiallyExpanded,
                      BlockSource.LineLayout lineLayout) {
        this.terminal = terminal;
        this.content = new BlockSource(collapsedLines, expandedLines, lineLayout);
        this.expanded = initiallyExpanded;
    }

    @Override
    public List<AttributedString> render(int width) {
        if (frozen) {
            return List.of();
        }
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
}
