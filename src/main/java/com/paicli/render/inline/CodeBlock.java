package com.paicli.render.inline;

import com.paicli.render.highlight.CodeHighlighter;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

import java.util.ArrayList;
import java.util.List;

/** Typed foldable code block with the issue-04 short/long default policy. */
final class CodeBlock extends LiveFoldableBlock {

    private static final int COLLAPSE_THRESHOLD_LINES = 10;

    CodeBlock(Terminal terminal, String language, String code) {
        this(terminal, normalizeLanguage(language), bodyLines(code));
    }

    private CodeBlock(Terminal terminal, String language, List<String> bodyLines) {
        super(terminal,
                collapsed(language, bodyLines.size()),
                expanded(language, bodyLines),
                bodyLines.size() <= COLLAPSE_THRESHOLD_LINES);
    }

    private static List<AttributedString> collapsed(String language, int lineCount) {
        String label = language.isEmpty() ? "code" : "code: " + language;
        return List.of(AttributedString.fromAnsi(AnsiStyle.subtle(
                "⏵ " + label + " (" + lineCount + " 行, ctrl+o to expand)")));
    }

    private static List<AttributedString> expanded(String language, List<String> bodyLines) {
        String label = language.isEmpty() ? "code" : "code: " + language;
        List<AttributedString> lines = new ArrayList<>();
        lines.add(AttributedString.fromAnsi(AnsiStyle.codeLabel("┌─ " + label)));
        for (AttributedString bodyLine : CodeHighlighter.highlight(String.join("\n", bodyLines), language)) {
            lines.add(new AttributedStringBuilder().append("    ").append(bodyLine).toAttributedString());
        }
        lines.add(AttributedString.fromAnsi(AnsiStyle.codeLabel("└─ end")));
        lines.add(new AttributedString("⏷ collapse (ctrl+o)"));
        return List.copyOf(lines);
    }

    private static String normalizeLanguage(String language) {
        return language == null ? "" : language.trim();
    }

    private static List<String> bodyLines(String code) {
        String normalized = code == null ? "" : code.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && normalized.endsWith("\n")) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() == 1 && lines.get(0).isEmpty() && normalized.isEmpty()) {
            lines.clear();
        }
        return List.copyOf(lines);
    }
}
