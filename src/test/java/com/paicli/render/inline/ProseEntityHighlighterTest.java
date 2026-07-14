package com.paicli.render.inline;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProseEntityHighlighterTest {

    @Test
    void highlightsRelativeAbsoluteLineQualifiedAndBareCodePaths() {
        AttributedString source = new AttributedString(
                "src/main/Agent.java:232 /tmp/pai/config.yaml Foo.java pom.xml");

        AttributedString highlighted = ProseEntityHighlighter.highlight(
                source, false, Path.of("/workspace"));

        AttributedStyle relative = highlighted.styleAt(source.toString().indexOf("src/main"));
        assertNotEquals(AttributedStyle.DEFAULT, relative);
        assertEquals(relative, highlighted.styleAt(source.toString().indexOf("/tmp/pai")));
        assertEquals(relative, highlighted.styleAt(source.toString().indexOf("Foo.java")));
        assertEquals(relative, highlighted.styleAt(source.toString().indexOf("pom.xml")));
        assertEquals(AttributedStyle.DEFAULT, highlighted.styleAt(source.toString().indexOf(' ')));
    }

    @Test
    void highlightsOnlyFunctionReferencesWithTrailingParentheses() {
        AttributedString source = new AttributedString(
                "executeTools() Agent.run() Class#method() but executeTools and statement (value)");

        AttributedString highlighted = ProseEntityHighlighter.highlight(
                source, false, Path.of("/workspace"));

        AttributedStyle function = highlighted.styleAt(0);
        assertNotEquals(AttributedStyle.DEFAULT, function);
        assertEquals(function, highlighted.styleAt(source.toString().indexOf("Agent.run")));
        assertEquals(function, highlighted.styleAt(source.toString().indexOf("Class#method")));
        assertEquals(AttributedStyle.DEFAULT,
                highlighted.styleAt(source.toString().lastIndexOf("executeTools")));
        assertEquals(AttributedStyle.DEFAULT,
                highlighted.styleAt(source.toString().indexOf("statement")));
    }

    @Test
    void leavesVersionsAbbreviationsUrlsAndNumbersUnstyled() {
        AttributedString source = new AttributedString(
                "1.0.2 e.g. https://example.com/Foo.java:42 "
                        + "https://example.com/executeTools() 12345 ordinary (words)");

        AttributedString highlighted = ProseEntityHighlighter.highlight(
                source, false, Path.of("/workspace"));

        for (int i = 0; i < highlighted.length(); i++) {
            int offset = i;
            assertEquals(AttributedStyle.DEFAULT, highlighted.styleAt(i),
                    () -> "unexpected entity style at offset " + offset);
        }
    }

    @Test
    void entityColorComposesWithExistingInlineCodeAndQuoteStyles() {
        AttributedStyle inlineCode = AttributedStyle.DEFAULT.bold().underline();
        AttributedString source = new AttributedStringBuilder()
                .style(inlineCode)
                .append("`src/Agent.java:7`")
                .toAttributedString();

        AttributedString highlighted = ProseEntityHighlighter.highlight(
                source, false, Path.of("/workspace"));

        assertEquals(inlineCode, highlighted.styleAt(0));
        assertEquals(inlineCode.foreground(AttributedStyle.CYAN), highlighted.styleAt(1));
        assertEquals(inlineCode, highlighted.styleAt(highlighted.length() - 1));
    }

    @Test
    void lineQualifiedPathCarriesOsc8FileLinkOnlyWhenEnabled() {
        AttributedString source = new AttributedString("src/Agent.java:232");

        String enabled = ProseEntityHighlighter.highlight(source, true, Path.of("/workspace"))
                .toAnsi();
        String disabled = ProseEntityHighlighter.highlight(source, false, Path.of("/workspace"))
                .toAnsi();

        byte[] bytes = enabled.getBytes(StandardCharsets.UTF_8);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains(
                "\u001b]8;;file:///workspace/src/Agent.java#L232\u001b\\"));
        assertTrue(enabled.contains("src/Agent.java:232"));
        assertTrue(enabled.contains("\u001b]8;;\u001b\\"));
        assertFalse(disabled.contains("\u001b]8;;"));
    }

    @Test
    void everyWrappedRowBalancesItsOwnOsc8Link() {
        AttributedString linked = ProseEntityHighlighter.highlight(
                new AttributedString("src/main/java/com/paicli/VeryLongAgent.java:232"),
                true, Path.of("/workspace"));

        int linkedRows = 0;
        for (AttributedString row : ProseEntityHighlighter.wrap(linked, 12, null)) {
            String ansi = row.toAnsi();
            int opens = occurrences(ansi, "\u001b]8;;file:");
            int closes = occurrences(ansi, "\u001b]8;;\u001b\\");
            assertEquals(opens, closes, ansi.replace("\u001b", "<ESC>"));
            linkedRows += opens;
        }
        assertTrue(linkedRows > 0);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(token, at)) >= 0; at += token.length()) {
            count++;
        }
        return count;
    }
}
