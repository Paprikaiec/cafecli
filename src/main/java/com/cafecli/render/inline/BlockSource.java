package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable styled source, laid out into width-and-fold rows on demand.
 *
 * <p>It is <b>source only</b> and memoizes nothing (ADR 0016 D1). The rows it produces are cached by
 * whichever view asked for them ({@link EntryRendering}), because a cache here would be one cache
 * serving two views on two threads — which is what let the [[转写浏览层]] release the [[活区]]'s rows
 * out from under the render thread.
 */
final class BlockSource {

    enum LineLayout {
        WRAP,
        CLIP
    }

    private final List<AttributedString> collapsedSourceLines;
    private final List<AttributedString> expandedSourceLines;
    private final LineLayout lineLayout;

    BlockSource(List<AttributedString> sourceLines) {
        this(sourceLines, sourceLines);
    }

    BlockSource(List<AttributedString> collapsedSourceLines,
                  List<AttributedString> expandedSourceLines) {
        this(collapsedSourceLines, expandedSourceLines, LineLayout.WRAP);
    }

    BlockSource(List<AttributedString> collapsedSourceLines,
                  List<AttributedString> expandedSourceLines,
                  LineLayout lineLayout) {
        this.collapsedSourceLines = collapsedSourceLines == null
                ? List.of() : List.copyOf(collapsedSourceLines);
        this.expandedSourceLines = expandedSourceLines == null
                ? List.of() : List.copyOf(expandedSourceLines);
        this.lineLayout = lineLayout == null ? LineLayout.WRAP : lineLayout;
    }

    List<AttributedString> render(Terminal terminal, int width, boolean foldState) {
        int columns = Math.max(1, width);
        List<AttributedString> rendered = new ArrayList<>();
        List<AttributedString> sourceLines = foldState ? expandedSourceLines : collapsedSourceLines;
        for (AttributedString sourceLine : sourceLines) {
            if (lineLayout == LineLayout.CLIP) {
                rendered.add(clip(sourceLine, columns, terminal));
            } else {
                rendered.addAll(ProseEntityHighlighter.wrap(sourceLine, columns, terminal));
            }
        }
        return List.copyOf(rendered);
    }

    /**
     * Cut a source line to {@code columns} with a trailing ellipsis instead of wrapping it.
     *
     * <p>Shared with the [[工具块]]'s running command tail (ADR 0017 D5), which needs the same
     * property this exists for: one source line is one visual row, so a row budget is exact.
     */
    static AttributedString clip(AttributedString source, int columns, Terminal terminal) {
        if (source.columnLength(terminal) <= columns) {
            return source;
        }
        if (columns == 1) {
            return new AttributedString("…", source.styleAt(0));
        }
        AttributedString visible = source.columnSubSequence(0, columns - 1, terminal);
        int styleIndex = visible.isEmpty() ? 0 : visible.length() - 1;
        AttributedStyle ellipsisStyle = visible.isEmpty()
                ? source.styleAt(0) : visible.styleAt(styleIndex);
        return new AttributedStringBuilder()
                .append(visible)
                .append("…", ellipsisStyle)
                .toAttributedString();
    }
}
