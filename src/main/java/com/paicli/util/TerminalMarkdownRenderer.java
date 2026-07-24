package com.paicli.util;

import org.jline.utils.AttributedString;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * 轻量终端 Markdown 渲染器。
 *
 * 目标不是完整支持所有 Markdown 语法，而是把常见的标题、列表、表格、引用和代码块
 * 渲染成更适合 CLI 终端阅读的纯文本布局。
 */
public final class TerminalMarkdownRenderer {
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s{0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final int COMPACT_TABLE_MAX_CELL_LENGTH = 24;
    private static final int COMPACT_TABLE_MAX_TOTAL_WIDTH = 80;
    private static final int DEFAULT_TERMINAL_COLUMNS = 120;
    private static final int MIN_TABLE_CELL_WIDTH = 4;

    private final PrintStream out;
    private final IntSupplier terminalColumnsSupplier;
    private final List<AttributedString> outputLines = new ArrayList<>();
    private final StringBuilder pending = new StringBuilder();
    private final List<String> pendingTable = new ArrayList<>();
    private boolean inCodeBlock;
    private boolean needsLineBreakBeforeNextBlock;
    private boolean lastOutputBlank;
    private boolean pendingListBlank;
    private BlockType lastBlockType = BlockType.NONE;

    public TerminalMarkdownRenderer(PrintStream out) {
        this(out, TerminalMarkdownRenderer::resolveTerminalColumns);
    }

    public TerminalMarkdownRenderer(PrintStream out, int terminalColumns) {
        this(out, () -> terminalColumns);
    }

    /** Stateful styled-line renderer for live-region composition. */
    public TerminalMarkdownRenderer(int terminalColumns) {
        this(null, () -> terminalColumns);
    }

    public TerminalMarkdownRenderer(PrintStream out, IntSupplier terminalColumnsSupplier) {
        this.out = out;
        this.terminalColumnsSupplier = terminalColumnsSupplier == null
                ? TerminalMarkdownRenderer::resolveTerminalColumns
                : terminalColumnsSupplier;
    }

    private TerminalMarkdownRenderer(TerminalMarkdownRenderer source) {
        this.out = null;
        this.terminalColumnsSupplier = source.terminalColumnsSupplier;
        this.pending.append(source.pending);
        this.pendingTable.addAll(source.pendingTable);
        this.inCodeBlock = source.inCodeBlock;
        this.needsLineBreakBeforeNextBlock = source.needsLineBreakBeforeNextBlock;
        this.lastOutputBlank = source.lastOutputBlank;
        this.pendingListBlank = source.pendingListBlank;
        this.lastBlockType = source.lastBlockType;
    }

    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        pending.append(chunk);
        flushCompleteLines();
    }

    public void finish() {
        if (pending.length() > 0) {
            processLine(pending.toString());
            pending.setLength(0);
        }
        flushPendingTable();
        flushPendingListBlank();
    }

    /** Styled lines emitted so far, preserving the parser state used by incremental append calls. */
    public List<AttributedString> styledLines() {
        return List.copyOf(outputLines);
    }

    public int styledLineCount() {
        return outputLines.size();
    }

    public AttributedString styledLine(int index) {
        return outputLines.get(index);
    }

    /** Render a non-mutating snapshot of the current parser state plus an incomplete source tail. */
    public List<AttributedString> previewStyledLines(String sourceTail) {
        TerminalMarkdownRenderer snapshot = new TerminalMarkdownRenderer(this);
        snapshot.append(sourceTail == null ? "" : sourceTail);
        snapshot.finish();
        return snapshot.styledLines();
    }

    public static String render(String markdown) {
        return render(markdown, resolveTerminalColumns());
    }

    public static String render(String markdown, int terminalColumns) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream, terminalColumns);
        renderer.append(markdown);
        renderer.finish();
        stream.flush();
        return buffer.toString();
    }

    private void flushCompleteLines() {
        int newlineIndex;
        while ((newlineIndex = indexOfNewline(pending)) >= 0) {
            String line = pending.substring(0, newlineIndex);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            processLine(line);
            pending.delete(0, newlineIndex + 1);
        }
    }

    private int indexOfNewline(StringBuilder builder) {
        for (int i = 0; i < builder.length(); i++) {
            if (builder.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void processLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine;

        if (!inCodeBlock && !line.trim().isEmpty()) {
            resolvePendingListBlank(line);
        }

        if (line.trim().startsWith("```")) {
            flushPendingTable();
            toggleCodeBlock();
            return;
        }

        if (inCodeBlock) {
            writeLine(line, BlockType.CODE_BLOCK);
            return;
        }

        if (looksLikeTableLine(line)) {
            pendingTable.add(line);
            return;
        }

        flushPendingTable();

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            if (isListItem(lastBlockType)) {
                pendingListBlank = true;
                return;
            }
            writeBlankLine();
            return;
        }

        if (THEMATIC_BREAK.matcher(line).matches()) {
            writeBlankLine();
            return;
        }

        var headingMatcher = HEADING.matcher(line);
        if (headingMatcher.matches()) {
            renderHeading(sanitizeInline(headingMatcher.group(2).trim()));
            return;
        }

        var orderedMatcher = ORDERED_LIST.matcher(line);
        if (orderedMatcher.matches()) {
            int indentLevel = indentLevel(orderedMatcher.group(1));
            prepareBlock(BlockType.ORDERED_LIST_ITEM, indentLevel);
            writeLine(indent(indentLevel) + orderedMatcher.group(2) + ". " + sanitizeInline(orderedMatcher.group(3).trim()),
                    BlockType.ORDERED_LIST_ITEM);
            return;
        }

        var unorderedMatcher = UNORDERED_LIST.matcher(line);
        if (unorderedMatcher.matches()) {
            int indentLevel = indentLevel(unorderedMatcher.group(1));
            prepareBlock(BlockType.UNORDERED_LIST_ITEM, indentLevel);
            writeLine(indent(indentLevel) + "- " + sanitizeInline(unorderedMatcher.group(2).trim()),
                    BlockType.UNORDERED_LIST_ITEM);
            return;
        }

        if (trimmed.startsWith(">")) {
            prepareBlock(BlockType.QUOTE, 0);
            writeLine(AnsiStyle.quotePrefix("│") + " " + sanitizeInline(trimmed.substring(1).trim()), BlockType.QUOTE);
            return;
        }

        if (isListItem(lastBlockType) && startsWithIndent(line)) {
            writeLine(sanitizeInline(line), lastBlockType);
            return;
        }

        prepareBlock(BlockType.PARAGRAPH, 0);
        writeLine(sanitizeInline(line), BlockType.PARAGRAPH);
    }

    private void toggleCodeBlock() {
        if (!inCodeBlock) {
            prepareBlock(BlockType.CODE_BLOCK, 0);
            inCodeBlock = true;
        } else {
            inCodeBlock = false;
            writeBlankLine();
        }
    }

    private void renderHeading(String content) {
        prepareBlock(BlockType.HEADING, 0);
        writeLine(AnsiStyle.heading(content), BlockType.HEADING);
        writeBlankLine();
    }

    private void flushPendingTable() {
        if (pendingTable.isEmpty()) {
            return;
        }

        List<List<String>> rows = new ArrayList<>();
        for (String line : pendingTable) {
            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }

            List<String> cells = parseTableRow(line);
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        pendingTable.clear();

        if (rows.isEmpty()) {
            return;
        }

        prepareBlock(BlockType.TABLE, 0);
        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (rows.size() >= 2 && columnCount == 2 && shouldRenderAsKeyValue(rows)) {
            renderKeyValueTable(rows);
            writeBlankLine();
            return;
        }

        int[] naturalWidths = new int[columnCount];
        for (List<String> row : rows) {
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                naturalWidths[i] = Math.max(naturalWidths[i], maxColumnLength(cell));
            }
        }
        int[] widths = allocateTableWidths(rows, naturalWidths, columnCount);

        String border = buildTableBorder(widths);
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            List<List<String>> wrappedCells = new ArrayList<>();
            int rowHeight = 1;
            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.size() ? sanitizeInline(row.get(i)) : "";
                List<String> wrapped = wrapCell(cell, widths[i]);
                wrappedCells.add(wrapped);
                rowHeight = Math.max(rowHeight, wrapped.size());
            }
            for (int lineIndex = 0; lineIndex < rowHeight; lineIndex++) {
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < columnCount; i++) {
                    List<String> wrapped = wrappedCells.get(i);
                    String cellLine = lineIndex < wrapped.size() ? wrapped.get(lineIndex) : "";
                    line.append(" ").append(padRightColumns(cellLine, widths[i])).append(" |");
                }
                String renderedLine = rowIndex == 0 ? AnsiStyle.emphasis(line.toString()) : line.toString();
                writeLine(renderedLine, BlockType.TABLE);
            }
            if (rowIndex == 0 && rows.size() > 1) {
                writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
            }
        }
        writeLine(AnsiStyle.subtle(border), BlockType.TABLE);
        writeBlankLine();
    }

    private boolean shouldRenderAsKeyValue(List<List<String>> rows) {
        int maxWidth = 0;
        int totalWidth = 0;

        for (List<String> row : rows) {
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            maxWidth = Math.max(maxWidth, Math.max(columnLength(left), columnLength(right)));
            totalWidth = Math.max(totalWidth, columnLength(left) + columnLength(right));
        }

        return maxWidth > COMPACT_TABLE_MAX_CELL_LENGTH || totalWidth > COMPACT_TABLE_MAX_TOTAL_WIDTH;
    }

    private void renderKeyValueTable(List<List<String>> rows) {
        List<String> header = rows.get(0);
        String leftHeader = sanitizeInline(header.get(0));
        String rightHeader = sanitizeInline(header.get(1));
        writeLine(AnsiStyle.emphasis(leftHeader + " / " + rightHeader), BlockType.TABLE);
        writeLine(AnsiStyle.subtle("-".repeat(Math.max((leftHeader + " / " + rightHeader).length(), 8))), BlockType.TABLE);

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String left = row.size() > 0 ? sanitizeInline(row.get(0)) : "";
            String right = row.size() > 1 ? sanitizeInline(row.get(1)) : "";
            writeLine(AnsiStyle.emphasis("- " + left), BlockType.TABLE);
            if (!right.isBlank()) {
                writeLine("  " + right, BlockType.TABLE);
            }
            if (i < rows.size() - 1) {
                writeBlankLine();
            }
        }
    }

    private List<String> parseTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private String buildTableBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append("+");
        }
        return border.toString();
    }

    private int[] allocateTableWidths(List<List<String>> rows, int[] naturalWidths, int columnCount) {
        int available = Math.max(columnCount * MIN_TABLE_CELL_WIDTH, terminalColumns() - (columnCount * 3 + 1));
        int[] widths = new int[columnCount];
        int base = Math.max(MIN_TABLE_CELL_WIDTH, Math.min(12, available / Math.max(1, columnCount)));
        int used = 0;
        for (int i = 0; i < columnCount; i++) {
            int headerWidth = rows.isEmpty() || rows.get(0).size() <= i
                    ? 0 : columnLength(sanitizeInline(rows.get(0).get(i)));
            int minWidth = Math.max(MIN_TABLE_CELL_WIDTH, Math.min(base, Math.max(headerWidth, MIN_TABLE_CELL_WIDTH)));
            widths[i] = Math.min(Math.max(naturalWidths[i], MIN_TABLE_CELL_WIDTH), minWidth);
            used += widths[i];
        }

        while (used > available) {
            int candidate = widestShrinkableColumn(widths);
            if (candidate < 0) {
                break;
            }
            widths[candidate]--;
            used--;
        }

        int remaining = available - used;
        while (remaining > 0) {
            int candidate = widestUnmetColumn(widths, naturalWidths);
            if (candidate < 0) {
                break;
            }
            widths[candidate]++;
            remaining--;
        }
        return widths;
    }

    private int widestShrinkableColumn(int[] widths) {
        int candidate = -1;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] <= MIN_TABLE_CELL_WIDTH) {
                continue;
            }
            if (candidate < 0 || widths[i] > widths[candidate]) {
                candidate = i;
            }
        }
        return candidate;
    }

    private int widestUnmetColumn(int[] widths, int[] naturalWidths) {
        int candidate = -1;
        int candidateGap = 0;
        for (int i = 0; i < widths.length; i++) {
            int gap = naturalWidths[i] - widths[i];
            if (gap <= 0) {
                continue;
            }
            if (candidate < 0 || gap > candidateGap) {
                candidate = i;
                candidateGap = gap;
            }
        }
        return candidate;
    }

    private boolean looksLikeTableLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (TABLE_SEPARATOR.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.contains("|") && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private void ensureBlockSpacing() {
        if (needsLineBreakBeforeNextBlock) {
            writeBlankLine();
        }
    }

    private void prepareBlock(BlockType nextBlockType, int indentLevel) {
        if (lastBlockType == BlockType.NONE
                || continuesCurrentBlock(nextBlockType, indentLevel)) {
            return;
        }
        ensureBlockSpacing();
    }

    private boolean continuesCurrentBlock(BlockType nextBlockType, int indentLevel) {
        if (isListItem(nextBlockType)) {
            return indentLevel > 0 || isListItem(lastBlockType);
        }
        return nextBlockType == BlockType.PARAGRAPH && lastBlockType == BlockType.PARAGRAPH
                || nextBlockType == BlockType.QUOTE && lastBlockType == BlockType.QUOTE;
    }

    private boolean isListItem(BlockType blockType) {
        return blockType == BlockType.ORDERED_LIST_ITEM || blockType == BlockType.UNORDERED_LIST_ITEM;
    }

    private boolean startsWithIndent(String line) {
        return line != null && !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    private void resolvePendingListBlank(String nextLine) {
        if (!pendingListBlank) {
            return;
        }
        boolean continuesList = ORDERED_LIST.matcher(nextLine).matches()
                || UNORDERED_LIST.matcher(nextLine).matches()
                || startsWithIndent(nextLine);
        pendingListBlank = false;
        if (!continuesList) {
            writeBlankLine();
        }
    }

    private void flushPendingListBlank() {
        if (!pendingListBlank) {
            return;
        }
        pendingListBlank = false;
        writeBlankLine();
    }

    private void writeLine(String line) {
        writeLine(line, BlockType.PARAGRAPH);
    }

    private void writeLine(String line, BlockType blockType) {
        AttributedString styled = AttributedString.fromAnsi(line == null ? "" : line);
        outputLines.add(styled);
        if (out != null) {
            out.println(line);
        }
        lastOutputBlank = line.isBlank();
        needsLineBreakBeforeNextBlock = !line.isBlank();
        if (!line.isBlank()) {
            lastBlockType = blockType;
        }
    }

    private void writeBlankLine() {
        if (!lastOutputBlank) {
            outputLines.add(AttributedString.EMPTY);
            if (out != null) {
                out.println();
            }
            lastOutputBlank = true;
        }
        needsLineBreakBeforeNextBlock = false;
    }

    private String sanitizeInline(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String sanitized = value
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("~~", "")
                .replace("*", "")
                .replace("_", "");

        sanitized = sanitized.replaceAll("\\[(.+?)]\\((.+?)\\)", "$1");
        return sanitized.stripTrailing();
    }

    private String padRightColumns(String value, int width) {
        int columns = columnLength(value);
        if (columns >= width) {
            return value;
        }
        return value + " ".repeat(width - columns);
    }

    private List<String> wrapCell(String value, int width) {
        List<String> lines = new ArrayList<>();
        int targetWidth = Math.max(MIN_TABLE_CELL_WIDTH, width);
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }

        for (AttributedString part : new AttributedString(text)
                .columnSplitLength(targetWidth, true, true)) {
            String rendered = part.toString().stripTrailing();
            if (!rendered.isEmpty() || lines.isEmpty()) {
                lines.add(rendered);
            }
        }
        return lines;
    }

    private int maxColumnLength(String value) {
        int max = 0;
        String text = value == null ? "" : value;
        for (String part : text.split("\\R", -1)) {
            max = Math.max(max, columnLength(part));
        }
        return max;
    }

    private int columnLength(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return new AttributedString(value).columnLength();
    }

    private int terminalColumns() {
        try {
            return Math.max(40, terminalColumnsSupplier.getAsInt());
        } catch (Exception e) {
            return DEFAULT_TERMINAL_COLUMNS;
        }
    }

    private static int resolveTerminalColumns() {
        String sysValue = System.getProperty("paicli.render.columns");
        if (sysValue != null && !sysValue.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(sysValue.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv("COLUMNS");
        if (envValue != null && !envValue.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(envValue.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TERMINAL_COLUMNS;
    }

    private int indentLevel(String leadingWhitespace) {
        if (leadingWhitespace == null || leadingWhitespace.isEmpty()) {
            return 0;
        }
        int spaces = 0;
        for (char ch : leadingWhitespace.toCharArray()) {
            spaces += ch == '\t' ? 4 : 1;
        }
        return Math.max(0, spaces / 2);
    }

    private String indent(int level) {
        return "  ".repeat(Math.max(level, 0));
    }

    private enum BlockType {
        NONE,
        HEADING,
        PARAGRAPH,
        ORDERED_LIST_ITEM,
        UNORDERED_LIST_ITEM,
        QUOTE,
        TABLE,
        CODE_BLOCK
    }
}
