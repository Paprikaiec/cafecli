package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CodeBlockTest {

    @Test
    void expandedCodeRowsPreserveSyntaxStyleSpans() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "java",
                "public String value = \"pai\"; // note");

        List<AttributedString> rows = block.render(120, FoldState.EXPANDED);

        AttributedString code = rows.stream()
                .filter(line -> line.toString().contains("public String"))
                .findFirst().orElseThrow();
        AttributedStyle keyword = code.styleAt(code.toString().indexOf("public"));
        AttributedStyle string = code.styleAt(code.toString().indexOf("\"pai\""));
        AttributedStyle comment = code.styleAt(code.toString().indexOf("// note"));
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(keyword, string);
        assertNotEquals(string, comment);
    }

    @Test
    void completedHighlightedRowsAreMemoizedAcrossRenderStorms() {
        Terminal terminal = Mockito.mock(Terminal.class);
        CodeBlock block = new CodeBlock(terminal, "json", "{\"count\": 42}");

        List<AttributedString> first = block.render(120, FoldState.EXPANDED);
        for (int frame = 0; frame < 100; frame++) {
            assertSame(first, block.render(120, FoldState.EXPANDED));
        }

        assertEquals(100, block.renderCacheHits());
    }
}
