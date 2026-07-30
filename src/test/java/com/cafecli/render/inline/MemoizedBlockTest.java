package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MemoizedBlockTest {

    @Test
    void cjkSourceIsPrewrappedByJLineAndLineCountIsItsHeight() {
        Terminal terminal = Mockito.mock(Terminal.class);
        MemoizedBlock block = new MemoizedBlock(List.of(new AttributedString("中文中文中文")));

        List<AttributedString> lines = block.render(terminal, 4, false);

        assertEquals(3, lines.size());
        assertEquals(List.of("中文", "中文", "中文"),
                lines.stream().map(AttributedString::toString).toList());
        assertEquals(List.of(4, 4, 4),
                lines.stream().map(line -> line.columnLength(terminal)).toList());
    }

    @Test
    void renderedRowsAreMemoizedByWidthAndFoldState() {
        Terminal terminal = Mockito.mock(Terminal.class);
        MemoizedBlock block = new MemoizedBlock(List.of(new AttributedString("abcdef")));

        List<AttributedString> first = block.render(terminal, 4, false);
        List<AttributedString> sameKey = block.render(terminal, 4, false);
        List<AttributedString> newWidth = block.render(terminal, 6, false);
        List<AttributedString> newFoldState = block.render(terminal, 4, true);

        assertSame(first, sameKey);
        assertNotSame(first, newWidth);
        assertNotSame(first, newFoldState);
    }

    @Test
    void foldStateSelectsDistinctCollapsedAndExpandedStyledSource() {
        Terminal terminal = Mockito.mock(Terminal.class);
        MemoizedBlock block = new MemoizedBlock(
                List.of(new AttributedString("summary")),
                List.of(new AttributedString("expanded detail")));

        List<String> collapsed = block.render(terminal, 8, false).stream()
                .map(AttributedString::toString)
                .toList();
        List<String> expanded = block.render(terminal, 8, true).stream()
                .map(AttributedString::toString)
                .toList();

        assertEquals(List.of("summary"), collapsed);
        assertEquals(List.of("expanded", " detail"), expanded);
    }
}
