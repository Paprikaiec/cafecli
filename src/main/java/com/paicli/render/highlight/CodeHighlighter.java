package com.paicli.render.highlight;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lightweight rendering-neutral syntax highlighter producing JLine style spans. */
public final class CodeHighlighter {

    private static final AttributedStyle KEYWORD = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final AttributedStyle STRING = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle COMMENT = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT).faint();
    private static final AttributedStyle NUMBER = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
    private static final AttributedStyle ANNOTATION = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);

    private static final Set<String> JAVA_KEYWORDS = keywords(
            "abstract assert boolean break byte case catch char class const continue default do double else enum "
                    + "extends final finally float for if implements import instanceof int interface long native new "
                    + "package private protected public return short static strictfp super switch synchronized this "
                    + "throw throws transient try void volatile while var record sealed permits yield");
    private static final Set<String> PYTHON_KEYWORDS = keywords(
            "and as assert async await break class continue def del elif else except finally for from global if "
                    + "import in is lambda nonlocal not or pass raise return try while with yield True False None match case");
    private static final Set<String> KOTLIN_KEYWORDS = keywords(
            "as break class continue do else false for fun if in interface is null object package return super this "
                    + "throw true try typealias val var when while by catch constructor finally import init where "
                    + "actual abstract annotation companion const data enum expect external final inline inner internal "
                    + "lateinit open operator out override private protected public reified sealed suspend tailrec vararg");
    private static final Set<String> JS_KEYWORDS = keywords(
            "break case catch class const continue debugger default delete do else export extends false finally for "
                    + "function if implements import in instanceof interface let new null package private protected public "
                    + "return static super switch this throw true try typeof var void while with async await yield of from as undefined");
    private static final Set<String> GO_KEYWORDS = keywords(
            "break case chan const continue default defer else fallthrough for func go goto if import interface map "
                    + "package range return select struct switch type var nil true false iota int string error");
    private static final Set<String> RUST_KEYWORDS = keywords(
            "as break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub "
                    + "ref return self Self static struct super trait true type unsafe use where while async await yield");
    private static final Set<String> JSON_KEYWORDS = keywords("true false null");
    private static final Set<String> YAML_KEYWORDS = keywords("true false null yes no on off");
    private static final Set<String> SHELL_KEYWORDS = keywords(
            "if then elif else fi for while until do done case esac in function select time coproc true false");
    private static final Set<String> SQL_KEYWORDS = keywords(
            "select from where insert into update delete create alter drop table join inner left right full on as and or "
                    + "not null is values set group by order having limit offset union all distinct case when then else end");

    private CodeHighlighter() {
    }

    /** Highlights a complete code block in source order. */
    public static List<AttributedString> highlight(String code, String language) {
        if (code == null || code.isEmpty()) {
            return List.of();
        }
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
        String normalizedLanguage = normalizeLanguage(language);
        Set<String> languageKeywords = switch (normalizedLanguage) {
            case "java" -> JAVA_KEYWORDS;
            case "python", "py" -> PYTHON_KEYWORDS;
            case "kotlin", "kt" -> KOTLIN_KEYWORDS;
            case "javascript", "js", "typescript", "ts" -> JS_KEYWORDS;
            case "go", "golang" -> GO_KEYWORDS;
            case "rust", "rs" -> RUST_KEYWORDS;
            case "json", "jsonc" -> JSON_KEYWORDS;
            case "yaml", "yml" -> YAML_KEYWORDS;
            case "shell", "sh", "bash", "zsh" -> SHELL_KEYWORDS;
            case "sql" -> SQL_KEYWORDS;
            default -> Set.of();
        };
        State state = new State();
        List<AttributedString> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            lines.add(highlightLine(line, normalizedLanguage, languageKeywords, state));
        }
        return List.copyOf(lines);
    }

    /** Compatibility adapter for text widgets that still consume ANSI strings. */
    public static String highlightAnsi(String code, String language) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        return highlight(code, language).stream()
                .map(AttributedString::toAnsi)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static AttributedString highlightLine(String line, String language,
                                                  Set<String> languageKeywords, State state) {
        AttributedStringBuilder out = new AttributedStringBuilder(line.length());
        int cursor = 0;
        boolean expectsXmlTagName = false;
        while (cursor < line.length()) {
            if (state.xmlComment) {
                int close = line.indexOf("-->", cursor);
                int end = close < 0 ? line.length() : close + 3;
                out.append(line.substring(cursor, end), COMMENT);
                cursor = end;
                if (close < 0) {
                    break;
                }
                state.xmlComment = false;
                continue;
            }
            if (language.equals("xml") && line.startsWith("<!--", cursor)) {
                int close = line.indexOf("-->", cursor + 4);
                int end = close < 0 ? line.length() : close + 3;
                out.append(line.substring(cursor, end), COMMENT);
                cursor = end;
                state.xmlComment = close < 0;
                continue;
            }
            if (state.tripleQuote != null) {
                int close = line.indexOf(state.tripleQuote, cursor);
                int end = close < 0 ? line.length() : close + state.tripleQuote.length();
                out.append(line.substring(cursor, end), STRING);
                cursor = end;
                if (close < 0) {
                    break;
                }
                state.tripleQuote = null;
                continue;
            }
            if (state.blockComment) {
                int close = line.indexOf("*/", cursor);
                int end = close < 0 ? line.length() : close + 2;
                out.append(line.substring(cursor, end), COMMENT);
                cursor = end;
                if (close < 0) {
                    break;
                }
                state.blockComment = false;
                continue;
            }
            if (line.startsWith("/*", cursor)) {
                int close = line.indexOf("*/", cursor + 2);
                int end = close < 0 ? line.length() : close + 2;
                out.append(line.substring(cursor, end), COMMENT);
                cursor = end;
                state.blockComment = close < 0;
                continue;
            }
            if (line.startsWith("//", cursor)) {
                out.append(line.substring(cursor), COMMENT);
                break;
            }
            if (language.equals("sql") && line.startsWith("--", cursor)) {
                out.append(line.substring(cursor), COMMENT);
                break;
            }
            if (usesHashComment(language) && line.charAt(cursor) == '#') {
                out.append(line.substring(cursor), COMMENT);
                break;
            }
            String tripleQuote = pythonTripleQuoteAt(line, cursor, language);
            if (tripleQuote != null) {
                int close = line.indexOf(tripleQuote, cursor + tripleQuote.length());
                int end = close < 0 ? line.length() : close + tripleQuote.length();
                out.append(line.substring(cursor, end), STRING);
                cursor = end;
                if (close < 0) {
                    state.tripleQuote = tripleQuote;
                }
                continue;
            }
            char current = line.charAt(cursor);
            if (language.equals("xml") && current == '<') {
                int end = cursor + 1;
                if (end < line.length() && line.charAt(end) == '/') {
                    end++;
                }
                out.append(line.substring(cursor, end));
                cursor = end;
                expectsXmlTagName = true;
                continue;
            }
            if (language.equals("xml") && current == '>') {
                out.append(current);
                cursor++;
                expectsXmlTagName = false;
                continue;
            }
            if (current == '"' || current == '\'') {
                int end = quotedEnd(line, cursor, current);
                out.append(line.substring(cursor, end), STRING);
                cursor = end;
                continue;
            }
            if (current == '@' && cursor + 1 < line.length()
                    && Character.isJavaIdentifierStart(line.charAt(cursor + 1))) {
                int end = identifierEnd(line, cursor + 1);
                out.append(line.substring(cursor, end), ANNOTATION);
                cursor = end;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = cursor + 1;
                while (end < line.length() && isNumberPart(line.charAt(end))) {
                    end++;
                }
                out.append(line.substring(cursor, end), NUMBER);
                cursor = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int end = identifierEnd(line, cursor);
                String token = line.substring(cursor, end);
                String keywordCandidate = language.equals("sql") ? token.toLowerCase(Locale.ROOT) : token;
                boolean keyword = languageKeywords.contains(keywordCandidate)
                        || (language.equals("xml") && expectsXmlTagName);
                out.append(token, keyword ? KEYWORD : AttributedStyle.DEFAULT);
                expectsXmlTagName = false;
                cursor = end;
                continue;
            }
            out.append(current);
            cursor++;
        }
        return out.toAttributedString();
    }

    private static int quotedEnd(String line, int start, char quote) {
        int cursor = start + 1;
        while (cursor < line.length()) {
            if (line.charAt(cursor) == '\\' && cursor + 1 < line.length()) {
                cursor += 2;
            } else if (line.charAt(cursor++) == quote) {
                break;
            }
        }
        return cursor;
    }

    private static String pythonTripleQuoteAt(String line, int cursor, String language) {
        if (!(language.equals("python") || language.equals("py"))) {
            return null;
        }
        if (line.startsWith("\"\"\"", cursor)) {
            return "\"\"\"";
        }
        return line.startsWith("'''", cursor) ? "'''" : null;
    }

    private static boolean usesHashComment(String language) {
        return language.equals("python") || language.equals("py")
                || language.equals("yaml") || language.equals("yml")
                || language.equals("shell") || language.equals("sh")
                || language.equals("bash") || language.equals("zsh")
                || !isRecognizedLanguage(language);
    }

    private static boolean isRecognizedLanguage(String language) {
        return switch (language) {
            case "java", "kotlin", "kt", "python", "py", "javascript", "js", "typescript", "ts",
                    "go", "golang", "rust", "rs", "json", "jsonc", "yaml", "yml", "shell", "sh",
                    "bash", "zsh", "sql", "xml" -> true;
            default -> false;
        };
    }

    private static int identifierEnd(String line, int start) {
        int cursor = start + 1;
        while (cursor < line.length() && Character.isJavaIdentifierPart(line.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isNumberPart(char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '_';
    }

    private static Set<String> keywords(String words) {
        return Set.copyOf(new HashSet<>(List.of(words.split("\\s+"))));
    }

    private static String normalizeLanguage(String language) {
        return language == null ? "text" : language.trim().toLowerCase(Locale.ROOT);
    }

    private static final class State {
        private boolean blockComment;
        private String tripleQuote;
        private boolean xmlComment;
    }
}
