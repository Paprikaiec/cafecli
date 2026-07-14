package com.paicli.render.inline;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative post-processing for code entities mentioned in rendered prose. */
final class ProseEntityHighlighter {

    private static final String CODE_EXTENSION =
            "java|kt|kts|py|js|jsx|ts|tsx|go|rs|c|h|cc|cpp|cxx|hpp|cs|rb|php|swift|scala|"
                    + "sh|bash|zsh|fish|sql|xml|html|css|scss|sass|less|json|jsonl|yaml|yml|"
                    + "toml|ini|properties|gradle|md|proto|vue|svelte|dart|lua|ex|exs|erl|hrl|"
                    + "clj|cljs|groovy|pl|pm|r|R|tf|hcl|dockerfile";
    private static final String PATH_TOKEN = "[\\p{Alnum}_@+.,~-]+";
    private static final Pattern PATH = Pattern.compile(
            "(?<![\\p{Alnum}_:/.~-])(?:"
                    + "(?:~?/|\\.{1,2}/)?" + PATH_TOKEN + "(?:/" + PATH_TOKEN + ")+"
                    + "|" + PATH_TOKEN + "\\.(?:" + CODE_EXTENSION + ")"
                    + ")(?::\\d+)?(?![\\p{Alnum}_/.-])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final AttributedStyle PATH_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN);
    private static final Pattern FUNCTION = Pattern.compile(
            "(?<![\\p{Alnum}_$#./?:=&%~-])(?:[\\p{L}_$][\\p{Alnum}_$]*)(?:[.#][\\p{L}_$][\\p{Alnum}_$]*)*\\(\\)"
                    + "(?![\\p{Alnum}_$])",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final AttributedStyle FUNCTION_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle ZERO_WIDTH_CONTROL = AttributedStyle.DEFAULT.hidden();
    private static final String OSC8_OPEN = "\u001b]8;;";
    private static final String OSC8_CLOSE = "\u001b]8;;\u001b\\";

    private ProseEntityHighlighter() {
    }

    static AttributedString highlight(AttributedString source, boolean osc8, Path workingDirectory) {
        if (source == null || source.length() == 0) {
            return source == null ? AttributedString.EMPTY : source;
        }
        AttributedStringBuilder builder = new AttributedStringBuilder(source.length());
        builder.append(source);
        builder.styleMatches(PATH, PATH_STYLE);
        builder.styleMatches(FUNCTION, FUNCTION_STYLE);
        AttributedString highlighted = builder.toAttributedString();
        return osc8 ? addFileLinks(highlighted, workingDirectory) : highlighted;
    }

    /** Wraps styled prose while balancing OSC 8 open/close controls inside every physical row. */
    static List<AttributedString> wrap(AttributedString source, int width, Terminal terminal) {
        int columns = Math.max(1, width);
        List<AttributedString> split = terminal == null
                ? source.columnSplitLength(columns, true, true)
                : source.columnSplitLength(columns, true, true, terminal);
        List<AttributedString> balanced = new ArrayList<>(split.size());
        String carriedOpen = null;
        for (AttributedString row : split) {
            String rowText = row.toString();
            String activeOpen = carriedOpen;
            int cursor = 0;
            while ((cursor = rowText.indexOf(OSC8_OPEN, cursor)) >= 0) {
                int terminator = rowText.indexOf("\u001b\\", cursor + OSC8_OPEN.length());
                if (terminator < 0) {
                    break;
                }
                String target = rowText.substring(cursor + OSC8_OPEN.length(), terminator);
                activeOpen = target.isEmpty()
                        ? null : rowText.substring(cursor, terminator + 2);
                cursor = terminator + 2;
            }
            if (carriedOpen == null && activeOpen == null && !rowText.contains(OSC8_OPEN)) {
                balanced.add(row);
                continue;
            }
            AttributedStringBuilder builder = new AttributedStringBuilder(row.length() + 64);
            if (carriedOpen != null) {
                builder.style(ZERO_WIDTH_CONTROL).append(carriedOpen);
            }
            builder.style(AttributedStyle.DEFAULT).append(row);
            if (activeOpen != null) {
                builder.style(ZERO_WIDTH_CONTROL).append(OSC8_CLOSE);
            }
            balanced.add(builder.toAttributedString());
            carriedOpen = activeOpen;
        }
        return List.copyOf(balanced);
    }

    private static AttributedString addFileLinks(AttributedString highlighted, Path workingDirectory) {
        Matcher matcher = PATH.matcher(highlighted.toString());
        AttributedStringBuilder linked = new AttributedStringBuilder(highlighted.length());
        int copiedThrough = 0;
        while (matcher.find()) {
            String token = matcher.group();
            int colon = token.lastIndexOf(':');
            if (colon < 1 || colon == token.length() - 1
                    || !token.substring(colon + 1).chars().allMatch(Character::isDigit)) {
                continue;
            }
            String uri = fileUri(token.substring(0, colon), token.substring(colon + 1), workingDirectory);
            if (uri == null) {
                continue;
            }
            linked.append(highlighted, copiedThrough, matcher.start());
            linked.style(ZERO_WIDTH_CONTROL).append(OSC8_OPEN + uri + "\u001b\\");
            linked.style(AttributedStyle.DEFAULT).append(highlighted, matcher.start(), matcher.end());
            linked.style(ZERO_WIDTH_CONTROL).append(OSC8_CLOSE);
            linked.style(AttributedStyle.DEFAULT);
            copiedThrough = matcher.end();
        }
        if (copiedThrough == 0) {
            return highlighted;
        }
        linked.append(highlighted, copiedThrough, highlighted.length());
        return linked.toAttributedString();
    }

    private static String fileUri(String rawPath, String line, Path workingDirectory) {
        try {
            Path path;
            if (rawPath.equals("~") || rawPath.startsWith("~/")) {
                String home = System.getProperty("user.home", "");
                path = Path.of(home).resolve(rawPath.length() == 1 ? "" : rawPath.substring(2));
            } else {
                path = Path.of(rawPath);
            }
            if (!path.isAbsolute()) {
                Path base = workingDirectory == null
                        ? Path.of("").toAbsolutePath() : workingDirectory.toAbsolutePath();
                path = base.resolve(path);
            }
            return path.normalize().toUri().toASCIIString() + "#L" + line;
        } catch (InvalidPathException | SecurityException ignored) {
            return null;
        }
    }
}
