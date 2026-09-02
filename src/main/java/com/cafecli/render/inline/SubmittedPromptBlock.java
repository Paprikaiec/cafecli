package com.cafecli.render.inline;

import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户[[提交块]]的渲染：提交后写回[[冻结回卷]]的那一块「我说的话」。
 *
 * <p>形态：只有首行带一个暗色 {@code ›} 标记，续行缩进两列对齐正文；块自己按终端宽度换行，
 * 不依赖终端自动折行。
 */
public final class SubmittedPromptBlock {

    private static final String MARKER = "› ";
    private static final String INDENT = "  ";
    /** 正文起始列：标记与续行缩进同宽，续行才能对齐正文。按列宽算，不用 {@code String.length()}。 */
    private static final int BODY_COLUMN = new AttributedString(MARKER).columnLength();
    /** 256 色：暗色底纹铺满整行。 */
    private static final AttributedStyle WASH = AttributedStyle.DEFAULT.background(236);
    /** 只有 16 色的终端没有可用的暗色底纹，退化为前景色区分（仍可读）。 */
    private static final AttributedStyle WASH_16 = AttributedStyle.DEFAULT.foreground(6);
    /** 首行 {@code ›} 标记的紫色，压在底纹之上；续行缩进不带标记色。 */
    private static final AttributedStyle MARKER_STYLE = WASH.foreground(141);
    private static final AttributedStyle MARKER_STYLE_16 =
            WASH_16.foreground(AttributedStyle.MAGENTA);

    private SubmittedPromptBlock() {
    }

    /**
     * 渲染成可直接写入[[冻结回卷]]的整块文本（每行以 {@code \n} 结尾）；空白输入返回空串，不产出空块。
     *
     * <p>ANSI 被禁用（{@code NO_COLOR} / dumb 终端）时输出为可读纯文本，不留残余转义序列。
     */
    public static String render(String input, int width, Terminal terminal) {
        StringBuilder block = new StringBuilder();
        for (AttributedString row : rows(input, width, terminal)) {
            block.append(AnsiStyle.isEnabled() ? row.toAnsi(terminal) : row.toString().stripTrailing())
                    .append('\n');
        }
        return block.toString();
    }

    /** 渲染成整块的行；空白输入返回空列表（不产出空块）。 */
    static List<AttributedString> rows(String input, int width, Terminal terminal) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return List.of();
        }
        int columns = Math.max(BODY_COLUMN + 1, width);
        int bodyWidth = columns - BODY_COLUMN;
        boolean wash = TerminalCapabilities.supports256Colors(terminal);
        List<AttributedString> rendered = new ArrayList<>();
        for (String logicalLine : visible.split("\\R", -1)) {
            for (AttributedString chunk : wrap(logicalLine, bodyWidth, terminal)) {
                boolean first = rendered.isEmpty();
                rendered.add(styled(first ? MARKER : INDENT, first, chunk.toString(), columns, wash));
            }
        }
        return List.copyOf(rendered);
    }

    /**
     * 铺整行样式。256 色下补齐到终端宽度，使底纹在换行处连续不断裂；
     * 16 色下只有前景色，补齐没有视觉意义，故不补（与 {@code DiffBlock} 同例）。
     */
    private static AttributedString styled(
            String prefix, boolean marked, String body, int columns, boolean wash) {
        AttributedStringBuilder row = new AttributedStringBuilder();
        AttributedStyle bodyStyle = wash ? WASH : WASH_16;
        row.style(marked ? (wash ? MARKER_STYLE : MARKER_STYLE_16) : bodyStyle).append(prefix);
        row.style(bodyStyle).append(body);
        if (wash) {
            row.append(" ".repeat(Math.max(0, columns - row.columnLength())));
        }
        return row.toAttributedString();
    }

    /** 按列宽（wcwidth）切分，而非字符数——中日韩字符占两列。 */
    private static List<AttributedString> wrap(String text, int bodyWidth, Terminal terminal) {
        if (text.isEmpty()) {
            return List.of(AttributedString.EMPTY);
        }
        return new AttributedString(text).columnSplitLength(bodyWidth, true, true, terminal);
    }
}
