package com.cafecli.render.inline;

import com.cafecli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * 把[[输入框]]的裸输入行装饰成一个「框」：两条横线夹住带底纹的输入行（issue 09，ADR 0016 D6）。
 *
 * <p><b>它只装饰已经存在的行，从不重排它们。</b>输入行是 {@code LineReaderImpl} 按<b>终端自己的</b>
 * 宽度排好、经 {@link CaptureDisplay} 交过来的。给它加左右竖线就得让行编辑器按一个不是终端宽度的
 * 宽度排版——那是 ADR 0014 六个 JLine 耦合点之外的第七个，ADR 0016 D6 已明确否决（连同圆角
 * {@code ╭╮╰╯} 与经提示符做左竖线两个替代）。所以这里做的全部是：在上下各加一行横线，给输入行
 * 铺一层底纹并<b>补齐</b>到框宽（{@link #boxWidth}）。补齐只加列、绝不删列或移动列。
 *
 * <p><b>读起来像框的是底纹，不是横线。</b>空的、被两条横线夹住的提示符看上去仍然只是「两条分隔线
 * 之间的一个提示符」；空的、带底纹的一行看上去是「一个在等着的输入区」。而且底纹与[[提交块]]用的
 * 是同一片，所以提交读起来像这个框把内容原地冻上去，而不是文字消失、另一个块出现。
 *
 * <p><b>框宽默认 {@code width - 1}，与状态坞同一条规则</b>：活区里凡是被补齐到整行的东西都少占一列，
 * 光标就不会落在最后一列（延迟折行的终端上那是个歧义位置）。但行编辑器自己排到整行宽的那一行不受这条
 * 约束——它不是我们补出来的，也不该被我们裁掉——所以横线改为跟着可见行的最大列宽走（{@link #boxWidth}），
 * 否则满行那一帧横线会比底纹短一列。光标那一半由 {@code LiveRegionStrip.composedCursorPos} 单独守住。
 */
final class InputBoxChrome {

    /**
     * 横线用的字符；wcwidth 为 1 列，所以「几个字符」与「几列」在这里同值。
     *
     * <p><b>它不会以 UTF-8 原样落到终端上。</b>JLine 的 {@code AttributedCharSequence.toAnsi(Terminal)}
     * 对制表字符一律做转换：终端报了 {@code enter/exit_alt_charset_mode} 就发 DEC Special Graphics
     * （{@code ESC(0} + {@code q} + {@code ESC(B}，Terminal.app 走的是这条，画出来是一条实心横线），
     * 没报就退化成 ASCII {@code -}。两条都是横线，所以形态不变；离线屏幕模型
     * （{@code TerminalScreenModel.mappedGraphic}）把 {@code q} 映射回 {@code U+2500}，因此屏幕真相
     * 断言读到的仍然是 {@code ─}。
     */
    private static final String RULE = "─";

    /**
     * ANSI 关掉那一档的横线：直接用 ASCII，与 JLine 自己对无制表能力终端的退化一致。
     *
     * <p>理由是「一个转义字节都不产生」这条判据要真的成立：{@code ─} 在有制表能力的终端上会带出
     * 字符集切换序列（见 {@link #RULE}），那虽然不是颜色、也确实是转义序列。
     */
    private static final String PLAIN_RULE = "-";

    /** 底纹：与 {@link SubmittedPromptBlock} 同一个 256 色底色，二者必须是同一片。 */
    private static final AttributedStyle WASH = AttributedStyle.DEFAULT.background(236);

    /**
     * 短终端降级的两级门槛。
     *
     * <p><b>这两个数是拍的，不是量的。</b>issue 09 与本 ADR 都明确记下了这一点：判据应该是
     * 「[[活区尾]]还剩几行才算有用」——它同时被状态坞、输入框与[[bounded replay window]]挤压——而
     * 那个判据只能在真实终端上按 80×24 / 80×20 / 80×12 三档看出来，<b>至今没有做过这次测量</b>。
     * 在做之前请不要把它们当作实测值引用，也不要据此推导别的常数。
     *
     * <p>形态：{@code >= 20} 行给整框（上横线 + 输入行 + 下横线，常驻 3 行，加状态坞 2 行共 5 行）；
     * {@code >= 12} 行只留上横线（常驻 2 行）；再矮就只剩输入行本身。同样的内容、更少的装饰——
     * 「任何时刻都在」这个承诺在小终端上仍然成立，代价只是装饰。
     */
    static final int BOTH_RULES_MIN_ROWS = 20;

    /** 见 {@link #BOTH_RULES_MIN_ROWS}：同样是拍的，不是量的。 */
    static final int TOP_RULE_MIN_ROWS = 12;

    private InputBoxChrome() {
    }

    /** 装饰后的输入框：要合成的行，以及输入行第 0 行相对框顶的行偏移（含框内滚动）。 */
    record Box(List<AttributedString> rows, int cursorRowShift) {

        static final Box NONE = new Box(List.of(), 0);

        Box {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * 色彩分支，与 {@link DiffBlock} / {@link SubmittedPromptBlock} 用的是同一套探测。
     *
     * <p>{@link #PLAIN} 是「ANSI 关掉了」那一档（{@code NO_COLOR} / dumb 终端），此时整个框一个转义
     * 字节都不产生：横线是裸字符，输入行原样交出去、不铺也不补。
     */
    enum Palette {
        COLOR_256,
        COLOR_16,
        PLAIN;

        static Palette of(Terminal terminal) {
            return AnsiStyle.isEnabled() ? fromCapabilities(terminal) : PLAIN;
        }

        /**
         * 只看**终端能力**的那一半，与进程级的颜色总开关无关。
         *
         * <p>拆出来是为了让它可被确定性地断言。{@link AnsiStyle#isEnabled()} 背后是一个
         * {@code static final}、每个 JVM 只算一次的快照，而 {@code TerminalMarkdownRendererTest}
         * 在自己的静态初始化里设 {@code paicli.render.color=false}——于是「哪个测试类先把
         * {@code AnsiStyle} 加载起来」决定了整个 JVM 的档位。直接断言 {@link #of} 的结果因此是一条
         * 只在全量顺序下红的 flake（本仓库已有的那条 `TerminalMarkdownRendererTest` flake 是同一个
         * 成因的另一个方向）。能力这一半没有这个依赖，可以照常断言。
         */
        static Palette fromCapabilities(Terminal terminal) {
            if (!TerminalCapabilities.supportsAnsi(terminal)) {
                return PLAIN;
            }
            return TerminalCapabilities.supports256Colors(terminal) ? COLOR_256 : COLOR_16;
        }
    }

    /**
     * @param inputRows   捕获帧交来的输入行；<b>空列表产出空框</b>——ADR 0016 D4 说「宽度不符时合成
     *                    零行」，那就连横线与底纹一起不画，不能留一个只有两条横线的空壳
     * @param cursorRow   光标落在 {@code inputRows} 的第几行（{@code -1} 表示未知）
     * @param width       本帧的终端宽度
     * @param terminalRows 本帧的终端高度，决定降级档位
     * @param maxBoxRows  整个框（含横线）最多能占几行；超出时框内滚动，横线保持在首尾
     */
    static Box decorate(List<AttributedString> inputRows,
                        int cursorRow,
                        int width,
                        int terminalRows,
                        int maxBoxRows,
                        Palette palette) {
        if (inputRows == null || inputRows.isEmpty()) {
            return Box.NONE;
        }
        int cap = Math.max(1, maxBoxRows);
        int rules = rulesFor(terminalRows);
        // 装饰永远让位给内容：框里没地方放输入行时先扔下横线，而不是扔下输入行。
        while (rules > 0 && cap - rules < 1) {
            rules--;
        }
        int visible = Math.min(inputRows.size(), Math.max(1, cap - rules));
        int start = firstVisibleRow(inputRows.size(), visible, cursorRow);
        int boxWidth = boxWidth(inputRows, start, visible, width);

        List<AttributedString> rendered = new ArrayList<>(visible + rules);
        if (rules >= 1) {
            rendered.add(rule(boxWidth, palette));
        }
        for (int i = start; i < start + visible; i++) {
            rendered.add(washed(inputRows.get(i), boxWidth, palette));
        }
        if (rules >= 2) {
            rendered.add(rule(boxWidth, palette));
        }
        return new Box(rendered, (rules >= 1 ? 1 : 0) - start);
    }

    /**
     * 这一框有多宽：{@code width - 1}，除非行编辑器自己排出了更宽的行。
     *
     * <p><b>横线与底纹必须同宽，否则框读不出来是一个框。</b>默认那一列的让位与状态坞同因（见类注释），
     * 但输入行不是我们排的——{@code LineReaderImpl} 按<b>终端自己的</b>宽度折行，所以一行填满时它<b>就是</b>
     * {@code width} 列。此时按 {@code width - 1} 画的横线比底纹短一列，右端露出一块（真机手工验收报的
     * 就是这个；离线量得横线 39 列、满行 40 列）。裁掉那一列是不行的——装饰只加列，不删列，也不重排
     * （ADR 0016 D6）——所以让横线跟着长。
     *
     * <p>取的是<b>本框可见行</b>的最大列宽，不是终端宽度：空框（只有 {@code > }）仍然是 {@code width - 1}，
     * 满行那一帧才涨到 {@code width}。上界就是终端宽度，因为行编辑器不会排得比终端还宽。
     */
    private static int boxWidth(List<AttributedString> inputRows, int start, int visible, int width) {
        int widest = Math.max(0, width - 1);
        for (int i = start; i < start + visible; i++) {
            widest = Math.max(widest, inputRows.get(i).columnLength());
        }
        return Math.min(widest, Math.max(0, width));
    }

    /**
     * 左提示符：{@code >} 带[[提交块]]的紫色（256 色 141，16 色退化 magenta），后跟一个不着色的空格。
     *
     * <p>它交给 {@code LineReader.readLine} 当提示符，由行编辑器自己排版并算进宽度（JLine 用
     * {@code AttributedString.fromAnsi} 解析），所以着色不占列。**编辑态标记 {@code * } 已退役**
     * （ADR 0014）：编辑与提交都用 {@code >} 家族，靠位置（框内 / [[冻结回卷]]）区分，而同一族的
     * 紫色正是让「提交读起来像这一行原地冻上去」成立的东西。
     */
    static String prompt(Palette palette) {
        return switch (palette) {
            case COLOR_256 -> new AttributedString(">",
                    AttributedStyle.DEFAULT.foreground(141)).toAnsi() + " ";
            case COLOR_16 -> new AttributedString(">",
                    AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA)).toAnsi() + " ";
            case PLAIN -> "> ";
        };
    }

    /** 本档位画几条横线：2（整框）/ 1（只上横线）/ 0（只剩输入行）。 */
    private static int rulesFor(int terminalRows) {
        if (terminalRows >= BOTH_RULES_MIN_ROWS) {
            return 2;
        }
        return terminalRows >= TOP_RULE_MIN_ROWS ? 1 : 0;
    }

    /**
     * 框内滚动的起点：让光标那一行永远看得见，其余尽量贴着末尾（正在打的那一行在下面）。
     */
    private static int firstVisibleRow(int total, int visible, int cursorRow) {
        if (total <= visible) {
            return 0;
        }
        int anchor = cursorRow < 0 ? total - 1 : Math.min(cursorRow, total - 1);
        return Math.max(0, Math.min(anchor - visible + 1, total - visible));
    }

    private static AttributedString rule(int columns, Palette palette) {
        int n = Math.max(0, columns);
        return palette == Palette.PLAIN
                ? new AttributedString(PLAIN_RULE.repeat(n))
                : new AttributedString(RULE.repeat(n), AttributedStyle.DEFAULT.faint());
    }

    /**
     * 给一行铺底纹并补齐到 {@code columns}（框宽，见 {@link #boxWidth}）。
     *
     * <p>底纹压在<b>每个字符自己的样式之下</b>，所以行编辑器的高亮与紫色 {@code >} 标记都活着——
     * 这正是「活区里的标记与回卷里的 {@code ›} 是同一族」所要求的。16 色终端没有可用的暗色底色，
     * 与 {@code DiffBlock} / {@link SubmittedPromptBlock} 同例退化为「不铺、也不补」：补齐一片没有
     * 底色的空白没有任何视觉意义。
     */
    private static AttributedString washed(AttributedString row, int columns, Palette palette) {
        if (palette != Palette.COLOR_256) {
            return row;
        }
        AttributedStringBuilder out = new AttributedStringBuilder();
        for (int i = 0; i < row.length(); i++) {
            out.style(row.styleAt(i).background(236));
            out.append(row.charAt(i));
        }
        int padding = Math.max(0, columns - out.columnLength());
        if (padding > 0) {
            out.style(WASH).append(" ".repeat(padding));
        }
        return out.toAttributedString();
    }
}
