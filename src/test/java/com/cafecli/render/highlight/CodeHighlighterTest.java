package com.cafecli.render.highlight;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeHighlighterTest {

    @Test
    void javaTokensUseDistinctStylesIncludingATrailingComment() {
        String source = "@Override public String value = \"hello\" + 42; // trailing";

        List<AttributedString> lines = CodeHighlighter.highlight(source, "java");

        AttributedString line = lines.get(0);
        assertEquals(source, line.toString());
        AttributedStyle annotation = styleOf(line, "@Override");
        AttributedStyle keyword = styleOf(line, "public");
        AttributedStyle string = styleOf(line, "\"hello\"");
        AttributedStyle number = styleOf(line, "42");
        AttributedStyle comment = styleOf(line, "// trailing");
        assertNotEquals(AttributedStyle.DEFAULT, annotation);
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(AttributedStyle.DEFAULT, string);
        assertNotEquals(AttributedStyle.DEFAULT, number);
        assertNotEquals(AttributedStyle.DEFAULT, comment);
        assertEquals(5, List.of(annotation, keyword, string, number, comment).stream().distinct().count());
    }

    @Test
    void blockCommentStyleCarriesAcrossLinesAndStopsAtTheClosingDelimiter() {
        List<AttributedString> lines = CodeHighlighter.highlight(
                "int value; /* starts\nstill comment */ int next;", "java");

        AttributedStyle comment = styleOf(lines.get(0), "/* starts");
        assertNotEquals(AttributedStyle.DEFAULT, comment);
        assertEquals(comment, styleOf(lines.get(1), "still comment"));
        assertNotEquals(comment, styleOf(lines.get(1), "int next"));
    }

    @Test
    void pythonTripleQuotedStringCarriesAcrossLines() {
        List<AttributedString> lines = CodeHighlighter.highlight(
                "value = \"\"\"first\nsecond\nlast\"\"\"\nreturn value", "python");

        AttributedStyle string = styleOf(lines.get(0), "\"\"\"first");
        assertNotEquals(AttributedStyle.DEFAULT, string);
        assertEquals(string, styleOf(lines.get(1), "second"));
        assertEquals(string, styleOf(lines.get(2), "last\"\"\""));
        assertNotEquals(string, styleOf(lines.get(3), "return"));
    }

    @Test
    void pythonKeywordStringAndTrailingCommentAreStyledSeparately() {
        AttributedString line = CodeHighlighter.highlight(
                "return 'value' 7 # trailing", "python").get(0);

        AttributedStyle keyword = styleOf(line, "return");
        AttributedStyle string = styleOf(line, "'value'");
        AttributedStyle comment = styleOf(line, "# trailing");
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(keyword, string);
        assertNotEquals(string, comment);
        assertNotEquals(styleOf(line, "7"), comment);
    }

    @Test
    void existingCompiledLanguagesRetainKeywordStringAndCommentStyles() {
        assertKeywordStringComment("kotlin", "fun run() = \"ok\" // note", "fun", "\"ok\"", "// note");
        assertKeywordStringComment("javascript", "const value = 'ok'; // note", "const", "'ok'", "// note");
        assertKeywordStringComment("typescript", "interface Value { name: \"ok\" } // note", "interface", "\"ok\"", "// note");
        assertKeywordStringComment("go", "func run() string { return \"ok\" } // note", "func", "\"ok\"", "// note");
        assertKeywordStringComment("rust", "fn run() { let value = \"ok\"; } // note", "fn", "\"ok\"", "// note");
    }

    @Test
    void jsonStylesStringsLiteralsAndNumbers() {
        AttributedString line = CodeHighlighter.highlight(
                "{\"enabled\": true, \"count\": 12, \"missing\": null}", "json").get(0);

        AttributedStyle string = styleOf(line, "\"enabled\"");
        AttributedStyle literal = styleOf(line, "true");
        AttributedStyle number = styleOf(line, "12");
        assertNotEquals(AttributedStyle.DEFAULT, string);
        assertNotEquals(AttributedStyle.DEFAULT, literal);
        assertNotEquals(string, literal);
        assertNotEquals(literal, number);
        assertEquals(literal, styleOf(line, "null"));
    }

    @Test
    void yamlStylesLiteralsStringsAndTrailingComments() {
        List<AttributedString> lines = CodeHighlighter.highlight(
                "enabled: true\nname: \"pai\" # note", "yaml");
        AttributedStyle keyword = styleOf(lines.get(0), "true");
        AttributedStyle string = styleOf(lines.get(1), "\"pai\"");
        AttributedStyle comment = styleOf(lines.get(1), "# note");
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(keyword, string);
        assertNotEquals(string, comment);
    }

    @Test
    void shellStylesKeywordsStringsAndTrailingComments() {
        assertKeywordStringComment("bash", "if test -f \"file\"; then echo ok; fi # note",
                "if", "\"file\"", "# note");
    }

    @Test
    void sqlStylesCaseInsensitiveKeywordsStringsAndTrailingComments() {
        assertKeywordStringComment("sql", "SELECT 'value' FROM things -- note",
                "SELECT", "'value'", "-- note");
    }

    @Test
    void xmlStylesTagNamesAttributeStringsAndComments() {
        AttributedString line = CodeHighlighter.highlight(
                "<root name=\"pai\"><!-- note --></root>", "xml").get(0);
        AttributedStyle tag = styleOf(line, "root");
        AttributedStyle string = styleOf(line, "\"pai\"");
        AttributedStyle comment = styleOf(line, "<!-- note -->");
        assertNotEquals(AttributedStyle.DEFAULT, tag);
        assertNotEquals(tag, string);
        assertNotEquals(string, comment);
    }

    @Test
    void unknownLanguageUsesGenericTokensWithoutAssumingJavaKeywords() {
        AttributedString line = CodeHighlighter.highlight(
                "public value = \"text\" + 3 # generic", "made-up").get(0);

        assertEquals(AttributedStyle.DEFAULT, styleOf(line, "public"));
        AttributedStyle string = styleOf(line, "\"text\"");
        AttributedStyle number = styleOf(line, "3");
        AttributedStyle comment = styleOf(line, "# generic");
        assertNotEquals(AttributedStyle.DEFAULT, string);
        assertNotEquals(string, number);
        assertNotEquals(AttributedStyle.DEFAULT, comment);
        assertNotEquals(string, comment);
        assertNotEquals(number, comment);
    }

    @Test
    void legacyTextAdapterPreservesContentAndEmitsAnsiStyles() {
        String source = "public class Value {}";

        String ansi = CodeHighlighter.highlightAnsi(source, "java");

        assertEquals(source, AttributedString.stripAnsi(ansi));
        assertTrue(ansi.contains("\u001B["));
    }

    private static AttributedStyle styleOf(AttributedString line, String token) {
        return line.styleAt(line.toString().indexOf(token));
    }

    private static void assertKeywordStringComment(String language, String source,
                                                   String keywordToken, String stringToken,
                                                   String commentToken) {
        AttributedString line = CodeHighlighter.highlight(source, language).get(0);
        AttributedStyle keyword = styleOf(line, keywordToken);
        AttributedStyle string = styleOf(line, stringToken);
        AttributedStyle comment = styleOf(line, commentToken);
        assertNotEquals(AttributedStyle.DEFAULT, keyword);
        assertNotEquals(keyword, string);
        assertNotEquals(string, comment);
    }
}
