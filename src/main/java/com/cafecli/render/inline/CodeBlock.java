package com.cafecli.render.inline;

import com.cafecli.render.highlight.CodeHighlighter;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.List;

/** Typed code block with capped inline and full retained views. */
final class CodeBlock extends LiveFoldableBlock {

    private static final int MAX_INLINE_LINES = 100;

    CodeBlock(Terminal terminal, String language, String code) {
        this(terminal, normalizeLanguage(language), bodyLines(code));
    }

    private CodeBlock(Terminal terminal, String language, List<String> bodyLines) {
        super(terminal,
                collapsed(language, bodyLines),
                expanded(language, bodyLines),
                false,
                MemoizedBlock.LineLayout.CLIP);
    }

    private static List<AttributedString> collapsed(String language, List<String> bodyLines) {
        int visibleLines = Math.min(MAX_INLINE_LINES, bodyLines.size());
        String label = language.isEmpty() ? "code" : "code: " + language;
        List<AttributedString> lines = new ArrayList<>();
        lines.add(BlockHeader.label(label));
        lines.addAll(formatted(language, bodyLines.subList(0, visibleLines),
                bodyLines.size() - visibleLines));
        return List.copyOf(lines);
    }

    private static List<AttributedString> expanded(String language, List<String> bodyLines) {
        return formatted(language, bodyLines, 0);
    }

    private static List<AttributedString> formatted(String language, List<String> bodyLines,
                                                     int omittedLines) {
        // Keep the single blank between the "code:" header and the body (intra-block spacing),
        // but emit no trailing edge blank: the live strip owns exactly one inter-block separator,
        // so a block that adds its own trailing blank produces a double blank between units.
        List<AttributedString> lines = new ArrayList<>();
        lines.add(AttributedString.EMPTY);
        lines.addAll(CodeHighlighter.highlight(String.join("\n", bodyLines), language));
        if (omittedLines > 0) {
            lines.add(new AttributedString("… +" + omittedLines + " 行 (ctrl+o 查看全部)"));
        }
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
