package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Completed reasoning source with collapsed-summary and quoted expanded renderings. */
final class ReasoningBlock extends LiveFoldableBlock {

    private static final AttributedStyle GRAY = AttributedStyle.DEFAULT
            .faint().foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT);
    private static final AttributedStyle THINKING = GRAY.italic();

    ReasoningBlock(Terminal terminal, String source) {
        this(terminal, logicalLines(source));
    }

    private ReasoningBlock(Terminal terminal, List<String> lines) {
        super(terminal, collapsed(lines.size()), expanded(terminal, lines), false);
    }

    @Override
    public boolean isThinking() {
        return true;
    }

    private static List<AttributedString> collapsed(int lineCount) {
        return List.of(BlockHeader.label("思考 " + lineCount + " 行"));
    }

    private static List<AttributedString> expanded(Terminal terminal, List<String> sourceLines) {
        List<AttributedString> lines = new ArrayList<>();
        lines.add(new AttributedString("Thinking...", THINKING));
        for (String sourceLine : sourceLines) {
            String normalized = sourceLine.replaceAll("\\s+", " ").trim();
            AttributedString quoted = new AttributedString(
                    normalized.isEmpty() ? "│" : "│ " + normalized, GRAY);
            lines.add(ProseEntityHighlighter.highlight(
                    quoted,
                    TerminalCapabilities.supportsOsc8(terminal),
                    Path.of("").toAbsolutePath()));
        }
        return List.copyOf(lines);
    }

    private static List<String> logicalLines(String source) {
        String normalized = source == null ? ""
                : source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        return List.copyOf(lines);
    }
}
