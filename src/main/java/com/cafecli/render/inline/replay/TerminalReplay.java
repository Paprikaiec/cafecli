package com.cafecli.render.inline.replay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Replays a recorded terminal trace without consulting renderer layout code. */
public final class TerminalReplay {

    private TerminalReplay() {
    }

    public static Result replay(ReplayTrace trace) {
        trace.requireComplete();
        ReplayTrace.Header header = trace.header();
        TerminalScreenModel terminal = new TerminalScreenModel(
                header.initialColumns(), header.initialRows());

        for (ReplayTrace.Event event : trace.events()) {
            if (event instanceof ReplayTrace.WriteEvent write) {
                terminal.feed(outputBytes(write.bytes(), header.lineFeedMode()));
            } else if (event instanceof ReplayTrace.ResizeEvent resize) {
                terminal.resize(resize.columns(), resize.rows());
            }
        }
        return new Result(terminal.scrollback(), terminal.screen(), terminal.historyText());
    }

    private static byte[] outputBytes(byte[] bytes, ReplayTrace.LineFeedMode mode) {
        if (mode == ReplayTrace.LineFeedMode.LF) {
            return bytes;
        }
        int lineFeeds = 0;
        for (byte value : bytes) {
            if (value == '\n') {
                lineFeeds++;
            }
        }
        byte[] translated = new byte[bytes.length + lineFeeds];
        int target = 0;
        for (byte value : bytes) {
            if (value == '\n') {
                translated[target++] = '\r';
            }
            translated[target++] = value;
        }
        return translated;
    }

    /** Exact comparison of Terminal.app truth (expected) and replay history (actual). */
    public static Comparison compareExact(String expectedTruth, String replayedHistory) {
        return compareExact(expectedTruth, replayedHistory, null);
    }

    /**
     * Exact comparison after optionally slicing both values at their first occurrence of {@code anchor}.
     * No whitespace, line-ending, or ANSI normalization is performed.
     */
    public static Comparison compareExact(String expectedTruth,
                                          String replayedHistory,
                                          String anchor) {
        Objects.requireNonNull(expectedTruth, "expectedTruth");
        Objects.requireNonNull(replayedHistory, "replayedHistory");
        String expected = expectedTruth;
        String actual = replayedHistory;
        if (anchor != null) {
            int expectedAnchor = expected.indexOf(anchor);
            if (expectedAnchor < 0) {
                return Comparison.failure("anchor not found in expected truth: " + quote(anchor));
            }
            int actualAnchor = actual.indexOf(anchor);
            if (actualAnchor < 0) {
                return Comparison.failure("anchor not found in replayed history: " + quote(anchor));
            }
            expected = expected.substring(expectedAnchor);
            actual = actual.substring(actualAnchor);
        }

        int expectedIndex = 0;
        int actualIndex = 0;
        while (expectedIndex < expected.length() && actualIndex < actual.length()) {
            int expectedPoint = expected.codePointAt(expectedIndex);
            int actualPoint = actual.codePointAt(actualIndex);
            if (expectedPoint != actualPoint) {
                return mismatch(expected, expectedIndex, expectedPoint, actualPoint);
            }
            expectedIndex += Character.charCount(expectedPoint);
            actualIndex += Character.charCount(actualPoint);
        }
        if (expectedIndex < expected.length()) {
            return mismatch(expected, expectedIndex, expected.codePointAt(expectedIndex), -1);
        }
        if (actualIndex < actual.length()) {
            return mismatch(expected, expectedIndex, -1, actual.codePointAt(actualIndex));
        }
        return new Comparison(true, -1, -1, -1, "history matches exactly");
    }

    private static Comparison mismatch(String expected,
                                       int expectedIndex,
                                       int expectedPoint,
                                       int actualPoint) {
        String prefix = expected.substring(0, expectedIndex);
        int byteOffset = prefix.getBytes(StandardCharsets.UTF_8).length;
        int line = 1;
        int column = 1;
        for (int i = 0; i < prefix.length();) {
            int point = prefix.codePointAt(i);
            if (point == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            i += Character.charCount(point);
        }
        String diagnostic = "history differs at UTF-8 byte " + byteOffset
                + " (line " + line + ", column " + column + "): expected "
                + display(expectedPoint) + ", actual " + display(actualPoint);
        return new Comparison(false, byteOffset, line, column, diagnostic);
    }

    private static String display(int codePoint) {
        if (codePoint < 0) {
            return "<end-of-text>";
        }
        return switch (codePoint) {
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> Character.isISOControl(codePoint)
                    ? String.format("U+%04X", codePoint)
                    : quote(Character.toString(codePoint));
        };
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "\\'") + "'";
    }

    /** Result of a byte-for-byte UTF-8 text comparison. Positions are one-based; byte offset is zero-based. */
    public record Comparison(boolean matches,
                             int firstMismatchByte,
                             int line,
                             int column,
                             String diagnostic) {
        private static Comparison failure(String diagnostic) {
            return new Comparison(false, -1, -1, -1, diagnostic);
        }
    }

    /** Replay output with both physical-grid diagnostics and Terminal.app's logical history view. */
    public static final class Result {
        private final List<String> scrollback;
        private final List<String> screen;
        private final String text;

        private Result(List<String> scrollback, List<String> screen, String text) {
            this.scrollback = List.copyOf(scrollback);
            this.screen = List.copyOf(screen);
            this.text = text;
        }

        public List<String> scrollback() {
            return scrollback;
        }

        public List<String> screen() {
            return screen;
        }

        public List<String> document() {
            List<String> document = new ArrayList<>(scrollback.size() + screen.size());
            document.addAll(scrollback);
            document.addAll(screen);
            return List.copyOf(document);
        }

        /**
         * Returns Terminal.app's logical history serialization exactly as modeled.
         *
         * <p>Soft-wrapped continuation rows are rejoined. No hard rows are trimmed or dropped, and
         * Terminal.app terminates the final non-wrapped row whether it is blank or non-blank.</p>
         */
        public String text() {
            return text;
        }
    }
}
