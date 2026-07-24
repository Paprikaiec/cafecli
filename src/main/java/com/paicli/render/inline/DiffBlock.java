package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed, retained, foldable file diff source block rendered in Claude-Code "Update" style.
 *
 * <p>The layout is a plain "Update &lt;path&gt;  +A -D" summary header, a right-aligned line-number
 * gutter, and per-row {@code +}/{@code -}/context markers with red/green backgrounds. There are no
 * {@code @@} unified-diff hunk headers and no {@code ▎} accent bar; non-contiguous hunks are split
 * by a single dim {@code ⋮} gap.
 */
final class DiffBlock extends LiveFoldableBlock {

    private static final int MAX_INLINE_SOURCE_LINES = 100;
    private static final AttributedStyle CONTEXT = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle SEPARATOR = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle ADD_BACKGROUND = AttributedStyle.DEFAULT.background(22);
    private static final AttributedStyle DELETE_BACKGROUND = AttributedStyle.DEFAULT.background(52);
    private static final AttributedStyle ADD_FOREGROUND = AttributedStyle.DEFAULT.foreground(2);
    private static final AttributedStyle DELETE_FOREGROUND = AttributedStyle.DEFAULT.foreground(1);

    private final Terminal terminal;
    private final String filePath;
    private final DiffModel model;
    private final Map<RenderKey, List<AttributedString>> rendered = new HashMap<>();
    private boolean renderedLive;
    private boolean printedAbove;

    DiffBlock(Terminal terminal, String filePath, String before, String after) {
        super(terminal, List.of(), List.of(), false);
        this.terminal = terminal;
        this.filePath = displayPath(filePath);
        this.model = model(before, after);
    }

    @Override
    public List<AttributedString> render(int width) {
        if (isFrozen()) {
            return List.of();
        }
        renderedLive = true;
        return render(width, foldState());
    }

    @Override
    public List<AttributedString> render(int width, FoldState foldState) {
        int columns = Math.max(1, width);
        RenderKey key = new RenderKey(columns, foldState);
        return rendered.computeIfAbsent(key, ignored -> foldState == FoldState.COLLAPSED
                ? collapsed(columns) : expanded(columns));
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return render(width, foldState());
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        if (minimumRows <= 0 || isFrozen()) {
            return List.of();
        }
        List<AttributedString> rows = render(width, foldState());
        super.freezeTopRows(minimumRows, width);
        return rows;
    }

    @Override
    public String drainPrintAbove(Terminal outputTerminal, int width) {
        if (renderedLive || printedAbove || isFrozen()) {
            return "";
        }
        StringBuilder batch = new StringBuilder();
        for (AttributedString line : render(width, foldState())) {
            batch.append(line.toAnsi(outputTerminal)).append('\n');
        }
        printedAbove = true;
        super.freezeTopRows(1, width);
        return batch.toString();
    }

    @Override
    public int renderCacheEntries() {
        return rendered.size();
    }

    @Override
    public int releaseRenderCache() {
        int count = rendered.size();
        rendered.clear();
        return count;
    }

    private List<AttributedString> collapsed(int width) {
        RenderedDiff inline = renderDiff(width, MAX_INLINE_SOURCE_LINES);
        AttributedString header = BlockHeader.label("Update " + filePath
                + "  +" + model.added() + " -" + model.deleted());
        List<AttributedString> result = new ArrayList<>(
                header.columnSplitLength(width, true, true, terminal));
        result.addAll(inline.rows());
        if (inline.omittedSourceLines() > 0) {
            result.add(new AttributedString("… +" + inline.omittedSourceLines()
                    + " 行 (ctrl+o 查看全部)"));
        }
        return List.copyOf(result);
    }

    private List<AttributedString> expanded(int width) {
        return renderDiff(width, Integer.MAX_VALUE).rows();
    }

    private RenderedDiff renderDiff(int width, int maxSourceLines) {
        boolean backgrounds = TerminalCapabilities.supports256Colors(terminal);
        int gutterWidth = gutterWidth();
        List<AttributedString> result = new ArrayList<>();
        int totalSourceLines = model.hunks().stream().mapToInt(hunk -> hunk.ops().size()).sum();
        int renderedSourceLines = 0;
        boolean firstHunk = true;
        for (DiffHunks.Hunk hunk : model.hunks()) {
            if (renderedSourceLines >= maxSourceLines) {
                break;
            }
            if (!firstHunk) {
                result.add(styled(" ".repeat(gutterWidth - 1) + "⋮", SEPARATOR));
            }
            firstHunk = false;
            for (DiffHunks.DiffOp op : hunk.ops()) {
                if (renderedSourceLines >= maxSourceLines) {
                    break;
                }
                result.addAll(renderOp(op, gutterWidth, width, backgrounds));
                renderedSourceLines++;
            }
        }
        return new RenderedDiff(List.copyOf(result), totalSourceLines - renderedSourceLines);
    }

    private List<AttributedString> renderOp(DiffHunks.DiffOp op, int gutterWidth,
                                            int width, boolean backgrounds) {
        char marker = switch (op.type()) {
            case ADD -> '+';
            case DELETE -> '-';
            case EQUAL -> ' ';
        };
        int lineNumber = op.type() == DiffHunks.OpType.DELETE
                ? op.beforeIndex() + 1 : op.afterIndex() + 1;
        String text = String.format("%" + gutterWidth + "d", lineNumber)
                + "  " + marker + " " + op.text();
        AttributedStyle style = switch (op.type()) {
            case ADD -> backgrounds ? ADD_BACKGROUND : ADD_FOREGROUND;
            case DELETE -> backgrounds ? DELETE_BACKGROUND : DELETE_FOREGROUND;
            case EQUAL -> CONTEXT;
        };
        return styledRows(text, style, width, backgrounds && op.type() != DiffHunks.OpType.EQUAL);
    }

    private int gutterWidth() {
        int maxLineNumber = Math.max(1,
                Math.max(model.beforeLineCount(), model.afterLineCount()));
        return Integer.toString(maxLineNumber).length();
    }

    private List<AttributedString> styledRows(String text, AttributedStyle style,
                                               int width, boolean pad) {
        AttributedString source = styled(text, style);
        List<AttributedString> rows = source.columnSplitLength(width, true, true, terminal);
        if (!pad) {
            return rows;
        }
        List<AttributedString> padded = new ArrayList<>();
        for (AttributedString row : rows) {
            int spaces = Math.max(0, width - row.columnLength());
            padded.add(new AttributedStringBuilder()
                    .style(style).append(row).append(" ".repeat(spaces))
                    .toAttributedString());
        }
        return padded;
    }

    private static AttributedString styled(String text, AttributedStyle style) {
        return new AttributedStringBuilder().style(style).append(text).toAttributedString();
    }

    private static DiffModel model(String before, String after) {
        String[] beforeLines = logicalLines(before);
        String[] afterLines = logicalLines(after);
        List<DiffHunks.DiffOp> ops = DiffHunks.compute(beforeLines, afterLines);
        List<DiffHunks.Hunk> hunks = DiffHunks.group(ops);
        int added = (int) ops.stream().filter(op -> op.type() == DiffHunks.OpType.ADD).count();
        int deleted = (int) ops.stream().filter(op -> op.type() == DiffHunks.OpType.DELETE).count();
        return new DiffModel(hunks, added, deleted, beforeLines.length, afterLines.length);
    }

    private static String[] logicalLines(String source) {
        if (source == null || source.isEmpty()) {
            return new String[0];
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] split = normalized.split("\n", -1);
        return normalized.endsWith("\n")
                ? java.util.Arrays.copyOf(split, split.length - 1)
                : split;
    }

    private static String displayPath(String path) {
        return path == null || path.isBlank() ? "(unnamed)" : path;
    }

    private record DiffModel(List<DiffHunks.Hunk> hunks, int added, int deleted,
                             int beforeLineCount, int afterLineCount) {
    }

    private record RenderedDiff(List<AttributedString> rows, int omittedSourceLines) {
    }

    private record RenderKey(int width, FoldState foldState) {
    }
}
