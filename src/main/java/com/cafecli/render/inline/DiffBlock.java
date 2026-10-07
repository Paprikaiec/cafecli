package com.cafecli.render.inline;

import com.cafecli.render.BlockOverflow;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

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
    private final String sourcePath;
    private final String filePath;
    private final DiffModel model;

    DiffBlock(Terminal terminal, String filePath, String before, String after) {
        super(terminal, List.of(), List.of(), false);
        this.terminal = terminal;
        this.sourcePath = filePath;
        this.filePath = displayPath(filePath);
        this.model = model(before, after);
    }

    /**
     * The path exactly as the write observer reported it, before {@link #displayPath} substitutes a
     * placeholder for a blank one. It is the key that ties this diff back to the call that produced
     * it (ADR 0017 D4): {@code FileTools.persistFileContent} is handed the same {@code path}
     * argument the model sent, so the two match by string without any normalisation.
     */
    String sourcePath() {
        return sourcePath;
    }

    @Override
    public List<AttributedString> render(int width) {
        if (isFrozen()) {
            return List.of();
        }
        return render(width, foldState());
    }

    @Override
    public List<AttributedString> render(int width, FoldState foldState) {
        int columns = Math.max(1, width);
        // No memoization here: the rows belong to whichever view asked for them (ADR 0016 D1).
        return foldState == FoldState.COLLAPSED ? collapsed(columns) : expanded(columns);
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

    private List<AttributedString> collapsed(int width) {
        RenderedDiff inline = renderDiff(width, MAX_INLINE_SOURCE_LINES);
        AttributedString header = BlockHeader.label("Update " + filePath
                + "  +" + model.added() + " -" + model.deleted());
        List<AttributedString> result = new ArrayList<>(
                header.columnSplitLength(width, true, true, terminal));
        result.addAll(inline.rows());
        if (inline.omittedSourceLines() > 0) {
            result.add(BlockHeader.footnote(BlockOverflow.line(inline.omittedSourceLines())));
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
}
