package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable styled source rendered as authoritative width-laid-out rows. */
final class MemoizedBlock {

    enum LineLayout {
        WRAP,
        CLIP
    }

    private final List<AttributedString> collapsedSourceLines;
    private final List<AttributedString> expandedSourceLines;
    private final LineLayout lineLayout;
    private final Map<RenderKey, List<AttributedString>> renderedByKey = new HashMap<>();
    private int cacheHits;

    MemoizedBlock(List<AttributedString> sourceLines) {
        this(sourceLines, sourceLines);
    }

    MemoizedBlock(List<AttributedString> collapsedSourceLines,
                  List<AttributedString> expandedSourceLines) {
        this(collapsedSourceLines, expandedSourceLines, LineLayout.WRAP);
    }

    MemoizedBlock(List<AttributedString> collapsedSourceLines,
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
        RenderKey key = new RenderKey(columns, foldState);
        List<AttributedString> cached = renderedByKey.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        List<AttributedString> rendered = new ArrayList<>();
        List<AttributedString> sourceLines = foldState ? expandedSourceLines : collapsedSourceLines;
        for (AttributedString sourceLine : sourceLines) {
            if (lineLayout == LineLayout.CLIP) {
                rendered.add(clip(sourceLine, columns, terminal));
            } else {
                rendered.addAll(ProseEntityHighlighter.wrap(sourceLine, columns, terminal));
            }
        }
        List<AttributedString> result = List.copyOf(rendered);
        renderedByKey.put(key, result);
        return result;
    }

    int cacheEntryCount() {
        return renderedByKey.size();
    }

    int cacheHits() {
        return cacheHits;
    }

    int releaseRenderCache() {
        int released = renderedByKey.size();
        renderedByKey.clear();
        return released;
    }

    private static AttributedString clip(AttributedString source, int columns, Terminal terminal) {
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

    private record RenderKey(int width, boolean foldState) {
    }
}
