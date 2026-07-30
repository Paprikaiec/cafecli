package com.cafecli.render.inline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Incrementally normalized, source-age-bounded reasoning preview. */
final class ReasoningPreview {

    private final int maxSourceChars;
    private final Deque<CachedLine> completedLines = new ArrayDeque<>();
    private CachedLine currentLine = new CachedLine();
    private long sourcePosition;
    private long pendingSpacePosition = -1;
    private boolean previousWasCarriageReturn;

    ReasoningPreview(int maxSourceChars) {
        this.maxSourceChars = Math.max(1, maxSourceChars);
    }

    int append(String sourceDelta) {
        if (sourceDelta == null || sourceDelta.isEmpty()) {
            return 0;
        }
        int inspected = 0;
        for (int i = 0; i < sourceDelta.length(); i++) {
            char ch = sourceDelta.charAt(i);
            long position = ++sourcePosition;
            inspected++;

            if (previousWasCarriageReturn) {
                previousWasCarriageReturn = false;
                if (ch == '\n') {
                    evictExpired();
                    continue;
                }
            }

            if (ch == '\r') {
                finishLine();
                previousWasCarriageReturn = true;
            } else if (isLineBreak(ch)) {
                finishLine();
            } else if (isCollapsibleWhitespace(ch)) {
                if (currentLine.hasText()) {
                    pendingSpacePosition = position;
                }
            } else {
                if (pendingSpacePosition >= 0 && currentLine.hasText()) {
                    currentLine.append(' ', pendingSpacePosition);
                }
                pendingSpacePosition = -1;
                currentLine.append(ch, position);
            }
            evictExpired();
        }
        return inspected;
    }

    List<String> lines() {
        List<String> snapshot = new ArrayList<>(completedLines.size() + 1);
        for (CachedLine line : completedLines) {
            if (line.hasText()) {
                snapshot.add(line.text());
            }
        }
        if (currentLine.hasText()) {
            snapshot.add(currentLine.text());
        }
        return List.copyOf(snapshot);
    }

    void clear() {
        completedLines.clear();
        currentLine = new CachedLine();
        sourcePosition = 0;
        pendingSpacePosition = -1;
        previousWasCarriageReturn = false;
    }

    private void finishLine() {
        pendingSpacePosition = -1;
        if (currentLine.hasText()) {
            completedLines.addLast(currentLine);
            currentLine = new CachedLine();
        }
    }

    private void evictExpired() {
        long cutoff = sourcePosition - maxSourceChars;
        if (cutoff <= 0) {
            return;
        }
        while (!completedLines.isEmpty()) {
            CachedLine first = completedLines.peekFirst();
            first.evictThrough(cutoff);
            if (first.hasText()) {
                break;
            }
            completedLines.removeFirst();
        }
        if (completedLines.isEmpty()) {
            currentLine.evictThrough(cutoff);
            if (!currentLine.hasText()) {
                pendingSpacePosition = -1;
            }
        }
    }

    private static boolean isLineBreak(char ch) {
        return ch == '\n' || ch == '\u000B' || ch == '\f'
                || ch == '\u0085' || ch == '\u2028' || ch == '\u2029';
    }

    private static boolean isCollapsibleWhitespace(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static final class CachedLine {
        private final StringBuilder value = new StringBuilder();
        private final List<Long> sourcePositions = new ArrayList<>();
        private int start;

        void append(char ch, long sourcePosition) {
            compactIfNeeded();
            value.append(ch);
            sourcePositions.add(sourcePosition);
        }

        void evictThrough(long cutoff) {
            while (start < sourcePositions.size() && sourcePositions.get(start) <= cutoff) {
                start++;
            }
            while (start < value.length() && value.charAt(start) == ' ') {
                start++;
            }
            compactIfNeeded();
        }

        boolean hasText() {
            return start < value.length();
        }

        String text() {
            return value.substring(start);
        }

        private void compactIfNeeded() {
            if (start < 1024 && start * 2 < value.length()) {
                return;
            }
            if (start > 0) {
                value.delete(0, start);
                sourcePositions.subList(0, start).clear();
                start = 0;
            }
        }
    }
}
