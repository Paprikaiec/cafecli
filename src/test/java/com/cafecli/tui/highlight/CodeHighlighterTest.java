package com.cafecli.tui.highlight;

import com.cafecli.render.highlight.CodeHighlighter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeHighlighterTest {

    @Test
    void highlightsRepresentativeJavaSyntax() {
        String code = "public class Foo { public static void main(String[] args) { } }";
        String result = CodeHighlighter.highlightAnsi(code, "java");

        assertNotNull(result);
        assertTrue(result.contains("public"));
    }

    @Test
    void supportsNonJavaKeywordSets() {
        String code = "def hello():\n    print('world')\n    return True";
        String result = CodeHighlighter.highlightAnsi(code, "python");

        assertNotNull(result);
        assertTrue(result.contains("def"));
        assertTrue(result.contains("return"));
    }

    @Test
    void handlesEmptyInputAndPreservesStringContent() {
        assertNull(CodeHighlighter.highlightAnsi(null, "java"));
        assertEquals("", CodeHighlighter.highlightAnsi("", "java"));

        String code = "String s = \"class\";";
        String result = CodeHighlighter.highlightAnsi(code, "java");

        assertNotNull(result);
        assertTrue(result.contains("\"class\""));
    }
}
