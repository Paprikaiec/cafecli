package com.paicli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Typed, retained, foldable file diff source block. */
final class DiffBlock extends LiveFoldableBlock {

    private static final AttributedStyle HUNK = AttributedStyle.DEFAULT.foreground(6);
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
        AttributedString header = new AttributedString("± " + filePath
                + " (+" + model.added() + "/-" + model.deleted() + ")");
        return List.copyOf(header.columnSplitLength(width, true, true, terminal));
    }

    private List<AttributedString> expanded(int width) {
        boolean backgrounds = TerminalCapabilities.supports256Colors(terminal);
        List<AttributedString> result = new ArrayList<>();
        for (DiffHunks.Hunk hunk : model.hunks()) {
            result.add(styled(header(model, hunk), HUNK));
            for (DiffHunks.DiffOp op : hunk.ops()) {
                String prefix = switch (op.type()) {
                    case ADD -> "+";
                    case DELETE -> "-";
                    case EQUAL -> " ";
                };
                AttributedStyle style = switch (op.type()) {
                    case ADD -> backgrounds ? ADD_BACKGROUND : ADD_FOREGROUND;
                    case DELETE -> backgrounds ? DELETE_BACKGROUND : DELETE_FOREGROUND;
                    case EQUAL -> AttributedStyle.DEFAULT;
                };
                result.addAll(styledRows(prefix + op.text(), style, width,
                        backgrounds && op.type() != DiffHunks.OpType.EQUAL));
            }
        }
        return List.copyOf(result);
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

    private static String header(DiffModel model, DiffHunks.Hunk hunk) {
        if (model.beforeLineCount() == 0) {
            return "@@ -0,0 +1," + model.afterLineCount() + " @@";
        }
        if (model.afterLineCount() == 0) {
            return "@@ -1," + model.beforeLineCount() + " +0,0 @@";
        }
        return hunk.header();
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

    private record RenderKey(int width, FoldState foldState) {
    }
}
