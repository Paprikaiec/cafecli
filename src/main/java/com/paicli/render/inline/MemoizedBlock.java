package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable styled source rendered as authoritative JLine-wrapped rows. */
final class MemoizedBlock {

    private final List<AttributedString> collapsedSourceLines;
    private final List<AttributedString> expandedSourceLines;
    private final Map<RenderKey, List<AttributedString>> renderedByKey = new HashMap<>();
    private int cacheHits;

    MemoizedBlock(List<AttributedString> sourceLines) {
        this(sourceLines, sourceLines);
    }

    MemoizedBlock(List<AttributedString> collapsedSourceLines,
                  List<AttributedString> expandedSourceLines) {
        this.collapsedSourceLines = collapsedSourceLines == null
                ? List.of() : List.copyOf(collapsedSourceLines);
        this.expandedSourceLines = expandedSourceLines == null
                ? List.of() : List.copyOf(expandedSourceLines);
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
            rendered.addAll(ProseEntityHighlighter.wrap(sourceLine, columns, terminal));
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

    private record RenderKey(int width, boolean foldState) {
    }
}
