package com.cafecli.render.inline.replay;

import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately independent VT100/xterm text-grid model for deterministic replay.
 *
 * <p>The implementation imports no renderer layout, erase, or resize arithmetic. That separation is
 * load-bearing: the model must be capable of contradicting the live-region implementation it is used
 * to test. It retains a continuation bit on every physical row so soft-wrapped runs can be reflowed
 * and serialized the way Terminal.app exposes {@code history of tab}.</p>
 */
public final class TerminalScreenModel {

    /** A null cell is the trailing column of a multi-column glyph. */
    private static final String CONTINUATION = null;
    private static final int REPLACEMENT_CHARACTER = 0xfffd;

    private enum ParserState {
        TEXT,
        ESCAPE,
        CHARSET,
        CSI,
        OSC,
        OSC_ESCAPE,
        STRING,
        STRING_ESCAPE
    }

    private static final class Row {
        private final List<String> cells = new ArrayList<>();
        private boolean wrapped;

        private String text() {
            StringBuilder result = new StringBuilder();
            for (String cell : cells) {
                if (cell != CONTINUATION) {
                    result.append(cell);
                }
            }
            return result.toString();
        }
    }

    private record CursorPosition(int row, int column) {
    }

    private record ReflowedLine(List<Row> rows, CursorPosition cursor) {
    }

    private final List<Row> screen = new ArrayList<>();
    private final List<Row> scrollback = new ArrayList<>();
    private final StringBuilder controlSequence = new StringBuilder();

    private int width;
    private int height;
    private int row;
    private int column;
    private boolean pendingWrap;
    private int scrollTop;
    private int scrollBottom;
    private int savedRow;
    private int savedColumn;
    private ParserState parserState = ParserState.TEXT;
    /** G0-G3 designation bytes; ASCII ({@code B}) is the terminal reset default. */
    private final int[] designatedCharsets = {'B', 'B', 'B', 'B'};
    private int charsetBeingDesignated;
    /** GL normally invokes G0; SO/SI switch between G1 and G0. */
    private int invokedCharset;

    private int utf8Remaining;
    private int utf8CodePoint;
    private int utf8Minimum;

    public TerminalScreenModel(int width, int height) {
        requireDimensions(width, height);
        this.width = width;
        this.height = height;
        for (int i = 0; i < height; i++) {
            screen.add(new Row());
        }
        scrollTop = 0;
        scrollBottom = height - 1;
    }

    /** Zero-based cursor row within the current screen; diagnostics only. */
    int cursorRow() {
        return row;
    }

    /** Zero-based cursor column; may equal {@link #columns()} while a wrap is deferred. */
    int cursorColumn() {
        return column;
    }

    int columns() {
        return width;
    }

    int screenRows() {
        return height;
    }

    /** Feed exact terminal-boundary UTF-8 bytes. Decoder and escape state survive call boundaries. */
    public void feed(byte[] bytes) {
        for (byte value : bytes) {
            acceptByte(value & 0xff);
        }
    }

    /** Convenience entry point used by screen-level renderer regressions. */
    public void feed(String text) {
        text.codePoints().forEach(this::acceptCodePoint);
    }

    /** Rejoin soft-wrapped runs and cut every logical screen line at the new terminal width. */
    public void resize(int newWidth, int newHeight) {
        requireDimensions(newWidth, newHeight);
        boolean fullScreenScrollRegion = scrollTop == 0 && scrollBottom == height - 1;
        boolean cursorHadDeferredWrap = pendingWrap;

        List<List<String>> logicalLines = new ArrayList<>();
        List<String> run = new ArrayList<>();
        int cursorLine = -1;
        int cursorColumn = 0;
        for (int i = 0; i < screen.size(); i++) {
            Row current = screen.get(i);
            int base = run.size();
            if (i == row) {
                cursorLine = logicalLines.size();
                cursorColumn = base + Math.min(column, width);
            }
            run.addAll(current.cells);
            if (!current.wrapped) {
                logicalLines.add(run);
                run = new ArrayList<>();
            }
        }
        if (!run.isEmpty()) {
            logicalLines.add(run);
        }

        List<Row> reflowed = new ArrayList<>();
        int newRow = -1;
        int newColumn = 0;
        for (int i = 0; i < logicalLines.size(); i++) {
            List<String> cells = logicalLines.get(i);
            int first = reflowed.size();
            ReflowedLine line = reflowLogicalLine(
                    cells,
                    newWidth,
                    i == cursorLine ? cursorColumn : null,
                    cursorHadDeferredWrap);
            reflowed.addAll(line.rows());
            if (line.cursor() != null) {
                newRow = first + line.cursor().row();
                newColumn = line.cursor().column();
            }
        }
        while (newRow >= reflowed.size()) {
            reflowed.add(new Row());
        }

        screen.clear();
        screen.addAll(reflowed);
        width = newWidth;
        row = newRow < 0 ? screen.size() - 1 : newRow;
        while (screen.size() > newHeight) {
            scrollback.add(screen.remove(0));
            row--;
        }
        while (screen.size() < newHeight) {
            Row restored = scrollback.isEmpty()
                    ? new Row()
                    : scrollback.remove(scrollback.size() - 1);
            screen.add(0, restored);
            row++;
        }
        height = newHeight;
        row = Math.max(0, Math.min(row, height - 1));
        column = Math.max(0, Math.min(newColumn, width));
        pendingWrap = column >= width;
        if (fullScreenScrollRegion) {
            scrollTop = 0;
            scrollBottom = height - 1;
        } else {
            scrollTop = Math.max(0, Math.min(scrollTop, height - 1));
            scrollBottom = Math.max(scrollTop, Math.min(scrollBottom, height - 1));
        }
    }

    /**
     * Reflow one logical line without ever cutting a multi-column glyph between physical rows.
     * A wide glyph that cannot fit in the remaining column starts on the next row; the unused cell
     * is a visual terminal blank, not a literal space in the logical history.
     */
    private static ReflowedLine reflowLogicalLine(List<String> cells,
                                                  int newWidth,
                                                  Integer cursorColumn,
                                                  boolean cursorHadDeferredWrap) {
        List<Row> rows = new ArrayList<>();
        Row current = new Row();
        rows.add(current);
        int sourceColumn = 0;
        int targetColumn = 0;
        CursorPosition mappedCursor = cursorColumn != null && cursorColumn == 0
                ? new CursorPosition(0, 0)
                : null;

        while (sourceColumn < cells.size()) {
            String glyph = cells.get(sourceColumn);
            int sourceWidth = sourceGlyphWidth(cells, sourceColumn);
            int glyphWidth = glyph == CONTINUATION
                    ? 1
                    : Math.max(sourceWidth, Math.max(1, WCWidth.wcwidth(glyph.codePointAt(0))));

            if (targetColumn > 0 && targetColumn + glyphWidth > newWidth) {
                current.wrapped = true;
                current = new Row();
                rows.add(current);
                targetColumn = 0;
            }
            if (cursorColumn != null && cursorColumn == sourceColumn) {
                mappedCursor = new CursorPosition(rows.size() - 1, targetColumn);
            }

            current.cells.add(glyph == CONTINUATION ? " " : glyph);
            for (int i = 1; i < glyphWidth; i++) {
                current.cells.add(CONTINUATION);
            }
            for (int i = 1; i < sourceWidth; i++) {
                if (cursorColumn != null && cursorColumn == sourceColumn + i) {
                    mappedCursor = new CursorPosition(
                            rows.size() - 1,
                            Math.min(newWidth, targetColumn + i));
                }
            }
            sourceColumn += sourceWidth;
            targetColumn += glyphWidth;
            if (cursorColumn != null && cursorColumn == sourceColumn) {
                mappedCursor = new CursorPosition(rows.size() - 1, targetColumn);
            }

            if (targetColumn >= newWidth && sourceColumn < cells.size()) {
                current.wrapped = true;
                current = new Row();
                rows.add(current);
                targetColumn = 0;
                if (cursorColumn != null && cursorColumn == sourceColumn) {
                    mappedCursor = new CursorPosition(rows.size() - 1, 0);
                }
            }
        }

        if (cursorColumn != null && cursorColumn > sourceColumn) {
            int virtualColumn = targetColumn + cursorColumn - sourceColumn;
            mappedCursor = new CursorPosition(
                    rows.size() - 1 + virtualColumn / newWidth,
                    virtualColumn % newWidth);
        }
        if (cursorColumn != null && cursorColumn >= sourceColumn
                && mappedCursor != null && mappedCursor.column() == newWidth) {
            mappedCursor = cursorHadDeferredWrap
                    ? new CursorPosition(mappedCursor.row() + 1, 0)
                    : new CursorPosition(mappedCursor.row(), newWidth - 1);
        }
        return new ReflowedLine(rows, mappedCursor);
    }

    private static int sourceGlyphWidth(List<String> cells, int at) {
        if (cells.get(at) == CONTINUATION) {
            return 1;
        }
        int width = 1;
        while (at + width < cells.size() && cells.get(at + width) == CONTINUATION) {
            width++;
        }
        return width;
    }

    private static void requireDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("terminal dimensions must be positive");
        }
    }

    private void acceptByte(int value) {
        if (utf8Remaining > 0) {
            if ((value & 0xc0) == 0x80) {
                utf8CodePoint = (utf8CodePoint << 6) | (value & 0x3f);
                utf8Remaining--;
                if (utf8Remaining == 0) {
                    int decoded = utf8CodePoint;
                    int minimum = utf8Minimum;
                    resetUtf8();
                    if (decoded >= minimum && decoded <= Character.MAX_CODE_POINT
                            && !(decoded >= Character.MIN_SURROGATE
                            && decoded <= Character.MAX_SURROGATE)) {
                        acceptCodePoint(decoded);
                    } else {
                        acceptCodePoint(REPLACEMENT_CHARACTER);
                    }
                }
                return;
            }
            resetUtf8();
            acceptCodePoint(REPLACEMENT_CHARACTER);
            acceptByte(value);
            return;
        }

        if (value < 0x80) {
            acceptCodePoint(value);
        } else if (value >= 0xc2 && value <= 0xdf) {
            beginUtf8(value & 0x1f, 1, 0x80);
        } else if (value >= 0xe0 && value <= 0xef) {
            beginUtf8(value & 0x0f, 2, 0x800);
        } else if (value >= 0xf0 && value <= 0xf4) {
            beginUtf8(value & 0x07, 3, 0x10000);
        } else {
            acceptCodePoint(REPLACEMENT_CHARACTER);
        }
    }

    private void beginUtf8(int codePoint, int remaining, int minimum) {
        utf8CodePoint = codePoint;
        utf8Remaining = remaining;
        utf8Minimum = minimum;
    }

    private void resetUtf8() {
        utf8CodePoint = 0;
        utf8Remaining = 0;
        utf8Minimum = 0;
    }

    private void acceptCodePoint(int codePoint) {
        switch (parserState) {
            case ESCAPE -> acceptEscape(codePoint);
            case CHARSET -> {
                designatedCharsets[charsetBeingDesignated] = codePoint;
                parserState = ParserState.TEXT;
            }
            case CSI -> acceptCsi(codePoint);
            case OSC -> {
                if (codePoint == 0x07) {
                    parserState = ParserState.TEXT;
                } else if (codePoint == 0x1b) {
                    parserState = ParserState.OSC_ESCAPE;
                }
            }
            case OSC_ESCAPE -> parserState = codePoint == '\\' ? ParserState.TEXT : ParserState.OSC;
            case STRING -> {
                if (codePoint == 0x1b) {
                    parserState = ParserState.STRING_ESCAPE;
                }
            }
            case STRING_ESCAPE -> parserState = codePoint == '\\' ? ParserState.TEXT : ParserState.STRING;
            case TEXT -> acceptText(codePoint);
        }
    }

    private void acceptText(int codePoint) {
        if (codePoint == 0x1b) {
            parserState = ParserState.ESCAPE;
            return;
        }
        switch (codePoint) {
            case '\r' -> carriageReturn();
            case '\n', 0x0b, 0x0c -> lineFeed();
            case '\b' -> {
                column = Math.max(0, column - 1);
                pendingWrap = false;
            }
            case '\t' -> horizontalTab();
            case 0x0e -> invokedCharset = 1;
            case 0x0f -> invokedCharset = 0;
            default -> {
                if (codePoint >= 0x20 && codePoint != 0x7f) {
                    put(mappedGraphic(codePoint));
                }
            }
        }
    }

    private void acceptEscape(int codePoint) {
        switch (codePoint) {
            case '[' -> {
                controlSequence.setLength(0);
                parserState = ParserState.CSI;
            }
            case '(', ')', '*', '+' -> {
                charsetBeingDesignated = switch (codePoint) {
                    case '(' -> 0;
                    case ')' -> 1;
                    case '*' -> 2;
                    default -> 3;
                };
                parserState = ParserState.CHARSET;
            }
            case ']' -> parserState = ParserState.OSC;
            case 'P', '^', '_' -> parserState = ParserState.STRING;
            case '7' -> {
                savedRow = row;
                savedColumn = column;
                parserState = ParserState.TEXT;
            }
            case '8' -> {
                row = Math.max(0, Math.min(height - 1, savedRow));
                column = clampColumn(savedColumn);
                pendingWrap = false;
                parserState = ParserState.TEXT;
            }
            case 'D' -> {
                lineFeed();
                parserState = ParserState.TEXT;
            }
            case 'E' -> {
                carriageReturn();
                lineFeed();
                parserState = ParserState.TEXT;
            }
            case 'M' -> {
                reverseIndex();
                parserState = ParserState.TEXT;
            }
            default -> parserState = ParserState.TEXT;
        }
    }

    /** Map DEC Special Graphics exactly as Terminal.app exposes it through {@code history of tab}. */
    private int mappedGraphic(int codePoint) {
        if (designatedCharsets[invokedCharset] != '0' || codePoint < 0x5f || codePoint > 0x7e) {
            return codePoint;
        }
        return switch (codePoint) {
            case 0x5f -> 0x20;
            case 0x60 -> 0x25c6;
            case 0x61 -> 0x2592;
            case 0x62 -> 0x2409;
            case 0x63 -> 0x240c;
            case 0x64 -> 0x240d;
            case 0x65 -> 0x240a;
            case 0x66 -> 0x00b0;
            case 0x67 -> 0x00b1;
            case 0x68 -> 0x2424;
            case 0x69 -> 0x240b;
            case 0x6a -> 0x2518;
            case 0x6b -> 0x2510;
            case 0x6c -> 0x250c;
            case 0x6d -> 0x2514;
            case 0x6e -> 0x253c;
            case 0x6f -> 0x23ba;
            case 0x70 -> 0x23bb;
            case 0x71 -> 0x2500;
            case 0x72 -> 0x23bc;
            case 0x73 -> 0x23bd;
            case 0x74 -> 0x251c;
            case 0x75 -> 0x2524;
            case 0x76 -> 0x2534;
            case 0x77 -> 0x252c;
            case 0x78 -> 0x2502;
            case 0x79 -> 0x2264;
            case 0x7a -> 0x2265;
            case 0x7b -> 0x03c0;
            case 0x7c -> 0x2260;
            case 0x7d -> 0x00a3;
            case 0x7e -> 0x00b7;
            default -> codePoint;
        };
    }

    private void acceptCsi(int codePoint) {
        if (codePoint >= 0x40 && codePoint <= 0x7e) {
            csi(controlSequence.toString(), (char) codePoint);
            parserState = ParserState.TEXT;
            return;
        }
        if (controlSequence.length() < 128) {
            controlSequence.appendCodePoint(codePoint);
        } else {
            controlSequence.setLength(0);
            parserState = ParserState.TEXT;
        }
    }

    private void csi(String rawParameters, char command) {
        boolean privateMode = rawParameters.startsWith("?");
        String parameters = numericParameters(rawParameters);
        int amount = firstParameter(parameters, 1);
        switch (command) {
            case 'A' -> moveRow(-amount);
            case 'B' -> moveRow(amount);
            case 'C' -> {
                column = clampColumn(column + amount);
                pendingWrap = false;
            }
            case 'D' -> {
                column = Math.max(0, column - amount);
                pendingWrap = false;
            }
            case 'E' -> {
                moveRow(amount);
                carriageReturn();
            }
            case 'F' -> {
                moveRow(-amount);
                carriageReturn();
            }
            case 'G' -> {
                column = clampColumn(amount - 1);
                pendingWrap = false;
            }
            case 'H', 'f' -> cursorPosition(parameters);
            case 'd' -> {
                row = clampRow(amount - 1);
                pendingWrap = false;
            }
            case 'J' -> eraseDisplay(firstParameter(parameters, 0));
            case 'K' -> eraseLine(firstParameter(parameters, 0));
            case 'X' -> {
                blank(row, column, column + amount);
                pendingWrap = false;
            }
            case 'P' -> deleteCharacters(amount);
            case '@' -> insertCharacters(amount);
            case 'L' -> insertLines(amount);
            case 'M' -> deleteLines(amount);
            case 'S' -> scrollUp(amount);
            case 'T' -> scrollDown(amount);
            case 'r' -> {
                if (!privateMode) {
                    setScrollMargins(parameters);
                }
            }
            case 's' -> {
                savedRow = row;
                savedColumn = column;
            }
            case 'u' -> {
                row = clampRow(savedRow);
                column = clampColumn(savedColumn);
                pendingWrap = false;
            }
            default -> {
                // SGR, DEC private modes, synchronized output, DECRQM and window operations have no
                // effect on the text grid represented by this model.
            }
        }
    }

    private static String numericParameters(String raw) {
        int start = 0;
        while (start < raw.length()) {
            char value = raw.charAt(start);
            if (Character.isDigit(value) || value == ';') {
                break;
            }
            start++;
        }
        int end = start;
        while (end < raw.length()) {
            char value = raw.charAt(end);
            if (!Character.isDigit(value) && value != ';') {
                break;
            }
            end++;
        }
        return raw.substring(start, end);
    }

    private static int firstParameter(String parameters, int fallback) {
        int separator = parameters.indexOf(';');
        String first = separator < 0 ? parameters : parameters.substring(0, separator);
        if (first.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(first);
            return value == 0 && fallback == 1 ? 1 : value;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void cursorPosition(String parameters) {
        String[] parts = parameters.split(";", -1);
        int targetRow = parameter(parts, 0, 1);
        int targetColumn = parameter(parts, 1, 1);
        row = clampRow(targetRow - 1);
        column = clampColumn(targetColumn - 1);
        pendingWrap = false;
    }

    private static int parameter(String[] parts, int index, int fallback) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(parts[index]);
            return value == 0 ? fallback : value;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void moveRow(int delta) {
        row = clampRow(row + delta);
        pendingWrap = false;
    }

    private int clampRow(int candidate) {
        return Math.max(0, Math.min(height - 1, candidate));
    }

    /** Explicit cursor movement cannot land in the deferred-wrap column. */
    private int clampColumn(int candidate) {
        return Math.max(0, Math.min(width - 1, candidate));
    }

    private void carriageReturn() {
        column = 0;
        pendingWrap = false;
    }

    private void horizontalTab() {
        int nextStop = Math.min(width - 1, ((column / 8) + 1) * 8);
        while (column < nextStop) {
            put(' ');
        }
    }

    private void put(int codePoint) {
        int cellWidth = Math.max(0, WCWidth.wcwidth(codePoint));
        if (cellWidth == 0) {
            appendCombining(codePoint);
            return;
        }
        if (pendingWrap || column + cellWidth > width) {
            screen.get(row).wrapped = true;
            column = 0;
            lineFeed();
        }

        Row line = screen.get(row);
        for (int i = 0; i < cellWidth; i++) {
            clearGlyphAt(line, column + i);
        }
        pad(line, column + cellWidth);
        line.cells.set(column, Character.toString(codePoint));
        for (int i = 1; i < cellWidth; i++) {
            line.cells.set(column + i, CONTINUATION);
        }
        column += cellWidth;
        if (column >= width) {
            column = width;
            pendingWrap = true;
        }
    }

    private void appendCombining(int codePoint) {
        Row line = screen.get(row);
        for (int i = Math.min(column, line.cells.size()) - 1; i >= 0; i--) {
            String cell = line.cells.get(i);
            if (cell != CONTINUATION) {
                line.cells.set(i, cell + Character.toString(codePoint));
                return;
            }
        }
    }

    private static void clearGlyphAt(Row line, int at) {
        if (at < 0 || at >= line.cells.size()) {
            return;
        }
        int start = at;
        while (start > 0 && line.cells.get(start) == CONTINUATION) {
            start--;
        }
        String glyph = line.cells.get(start);
        int glyphWidth = glyph == CONTINUATION || glyph.isEmpty()
                ? 1
                : Math.max(1, WCWidth.wcwidth(glyph.codePointAt(0)));
        int end = Math.min(line.cells.size(), start + glyphWidth);
        for (int i = start; i < end; i++) {
            line.cells.set(i, " ");
        }
    }

    private void lineFeed() {
        pendingWrap = false;
        if (row == scrollBottom) {
            Row evicted = screen.remove(scrollTop);
            screen.add(scrollBottom, new Row());
            if (scrollTop == 0) {
                scrollback.add(evicted);
            }
            return;
        }
        row = Math.min(height - 1, row + 1);
    }

    private void reverseIndex() {
        if (row == scrollTop) {
            screen.add(scrollTop, new Row());
            screen.remove(scrollBottom + 1);
        } else {
            row = Math.max(0, row - 1);
        }
        pendingWrap = false;
    }

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            truncate(row, column);
            for (int i = row + 1; i < height; i++) {
                clearRow(i);
            }
        } else if (mode == 1) {
            blank(row, 0, column + 1);
            for (int i = 0; i < row; i++) {
                clearRow(i);
            }
        } else if (mode == 3) {
            // ED(3) erases the terminal's *saved* lines and leaves the visible screen alone.
            // Measured 2026-08-21 against Terminal.app: five marker lines written to a blank screen
            // were still in `history of tab` after a bare CSI 3 J, so the screen survives it.
            scrollback.clear();
        } else {
            // ED(2) on the primary screen does not simply blank the visible rows in Terminal.app: it
            // pushes them into the saved scrollback first, exactly as `clear(1)` leaves the pre-clear
            // screen scrollable. Measured 2026-08-21, three ways: a bare CSI 2 J left the previously
            // visible lines in `history of tab`; every clean `heightdrag` storm run deposits exactly
            // one screenful (44) of blank rows ahead of the repaint; and one run in eight deposits a
            // screenful of *content* including the live region, which is the defect this models.
            //
            // Without this, the offline gate is structurally blind to anything the erase leaves
            // behind, which is why four rounds of screen-truth assertions passed while the real
            // terminal kept a copy.
            for (int i = 0; i < height; i++) {
                scrollback.add(screen.get(i));
                screen.set(i, new Row());
            }
        }
        pendingWrap = false;
    }

    private void eraseLine(int mode) {
        if (mode == 0) {
            truncate(row, column);
        } else if (mode == 1) {
            blank(row, 0, column + 1);
        } else {
            clearRow(row);
        }
        pendingWrap = false;
    }

    private void deleteCharacters(int amount) {
        Row line = screen.get(row);
        if (column < line.cells.size()) {
            int end = Math.min(line.cells.size(), column + amount);
            line.cells.subList(column, end).clear();
        }
        pendingWrap = false;
    }

    private void insertCharacters(int amount) {
        Row line = screen.get(row);
        pad(line, column);
        for (int i = 0; i < amount; i++) {
            line.cells.add(column, " ");
        }
        while (line.cells.size() > width) {
            line.cells.remove(line.cells.size() - 1);
        }
        pendingWrap = false;
    }

    private void insertLines(int amount) {
        if (row < scrollTop || row > scrollBottom) {
            return;
        }
        for (int i = 0; i < amount; i++) {
            screen.add(row, new Row());
            screen.remove(scrollBottom + 1);
        }
        pendingWrap = false;
    }

    private void deleteLines(int amount) {
        if (row < scrollTop || row > scrollBottom) {
            return;
        }
        for (int i = 0; i < amount; i++) {
            screen.remove(row);
            screen.add(scrollBottom, new Row());
        }
        pendingWrap = false;
    }

    private void scrollUp(int amount) {
        for (int i = 0; i < amount; i++) {
            Row evicted = screen.remove(scrollTop);
            screen.add(scrollBottom, new Row());
            if (scrollTop == 0) {
                scrollback.add(evicted);
            }
        }
        pendingWrap = false;
    }

    private void scrollDown(int amount) {
        for (int i = 0; i < amount; i++) {
            screen.add(scrollTop, new Row());
            screen.remove(scrollBottom + 1);
        }
        pendingWrap = false;
    }

    private void setScrollMargins(String parameters) {
        String[] parts = parameters.split(";", -1);
        int top = parameter(parts, 0, 1);
        int bottom = parameter(parts, 1, height);
        scrollTop = Math.max(0, Math.min(height - 1, top - 1));
        scrollBottom = Math.max(scrollTop, Math.min(height - 1, bottom - 1));
        row = scrollTop;
        column = 0;
        pendingWrap = false;
    }

    private void truncate(int targetRow, int from) {
        Row line = screen.get(targetRow);
        clearGlyphAt(line, from);
        if (line.cells.size() > from) {
            line.cells.subList(from, line.cells.size()).clear();
        }
        line.wrapped = false;
    }

    private void clearRow(int targetRow) {
        Row line = screen.get(targetRow);
        line.cells.clear();
        line.wrapped = false;
    }

    private void blank(int targetRow, int from, int to) {
        Row line = screen.get(targetRow);
        int boundedFrom = Math.max(0, Math.min(width, from));
        int boundedTo = Math.max(boundedFrom, Math.min(width, to));
        for (int i = boundedFrom; i < boundedTo; i++) {
            clearGlyphAt(line, i);
        }
        pad(line, boundedTo);
        for (int i = boundedFrom; i < boundedTo; i++) {
            line.cells.set(i, " ");
        }
    }

    private static void pad(Row line, int size) {
        while (line.cells.size() < size) {
            line.cells.add(" ");
        }
    }

    /** Physical rows that have permanently scrolled off the screen. */
    public List<String> scrollback() {
        return scrollback.stream().map(Row::text).toList();
    }

    /** Current physical screen rows, including untouched trailing blank rows. */
    public List<String> screen() {
        return screen.stream().map(Row::text).toList();
    }

    /** Physical scrollback followed by physical screen rows, for diagnostics. */
    public List<String> document() {
        List<String> document = new ArrayList<>(scrollback());
        document.addAll(screen());
        return List.copyOf(document);
    }

    /** AppleScript's {@code history of tab}: continuation rows are rejoined into logical lines. */
    public String historyText() {
        List<Row> rows = new ArrayList<>(scrollback.size() + screen.size());
        rows.addAll(scrollback);
        rows.addAll(screen);
        StringBuilder history = new StringBuilder();
        for (Row current : rows) {
            history.append(current.text());
            if (!current.wrapped) {
                history.append('\n');
            }
        }
        return history.toString();
    }

    public static int rowsContaining(List<String> document, String needle) {
        return (int) document.stream().filter(line -> line.contains(needle)).count();
    }

    public static int maxBlankRun(List<String> document) {
        int end = document.size();
        while (end > 0 && document.get(end - 1).isBlank()) {
            end--;
        }
        int maximum = 0;
        int run = 0;
        for (int i = 0; i < end; i++) {
            if (document.get(i).isBlank()) {
                run++;
                maximum = Math.max(maximum, run);
            } else {
                run = 0;
            }
        }
        return maximum;
    }

    public static int blankRowsBetween(List<String> document, String from, String to) {
        int start = -1;
        for (int i = 0; i < document.size(); i++) {
            if (start < 0) {
                if (document.get(i).contains(from)) {
                    start = i;
                }
            } else if (document.get(i).contains(to)) {
                int blanks = 0;
                for (int j = start + 1; j < i; j++) {
                    if (document.get(j).isBlank()) {
                        blanks++;
                    }
                }
                return blanks;
            }
        }
        return -1;
    }

    public static String dump(List<String> document) {
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < document.size(); i++) {
            dump.append(String.format("%3d |%s|%n", i, document.get(i)));
        }
        return dump.toString();
    }

}
