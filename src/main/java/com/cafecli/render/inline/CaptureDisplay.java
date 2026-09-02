package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 截获式 {@link Display}：把「要显示的行 + 光标位置」交出去，自己**不写终端**（ADR 0014 决策 1）。
 *
 * <p>这是行编辑器交出画笔的落点。JLine 4.0.0 里 {@code Display} 的全部终端写入都汇聚到
 * {@code update(List, int, boolean)}——另两个 {@code update} 重载与 {@code updateAnsi} 都委派到它，
 * {@code clear()} / {@code reset()} 只改字段（{@code clear_screen} 的发射发生在 {@code update} 顶部）。
 * 因此只覆写这一个方法就足以让一个 {@code Display} 实例什么也不画。该假设由
 * {@code CaptureDisplayInterceptionGuardTest} 静态 + 实测双向钉住。
 *
 * <p>捕获的帧由输入线程写入、渲染线程读取，故只保留**最新一帧**（volatile 发布），不累积历史——
 * 常驻输入框每次按键都会产生一帧，累积会是内存泄漏。需要逐帧观察的测试自行经
 * {@link #setFrameListener(Consumer)} 收集。
 */
public class CaptureDisplay extends Display {

    /**
     * 一帧输入框内容：行、光标位置（{@code row * (columns + 1) + col}）与该帧的列宽。
     *
     * <p><b>一行就是一行</b>——{@link #lines} 里任何一个元素都不含换行。这是构造期保证的，不是调用方
     * 的义务，因为它要挡的是 JLine 的一条行为而不是我们自己的疏忽（见 {@link #rows}）。
     */
    public record Frame(List<AttributedString> lines, int cursorPos, int columns, boolean flush) {

        public Frame {
            lines = rows(lines);
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** 去样式的纯文本行，便于断言。 */
        public List<String> plain() {
            List<String> out = new ArrayList<>(lines.size());
            for (AttributedString line : lines) {
                out.add(line.toString());
            }
            return out;
        }
    }

    /**
     * 捕获帧的行，**逐行去掉换行**（第七个 JLine 耦合点，ADR 0014）。
     *
     * <p>{@code LineReaderImpl.redisplay} 用
     * {@code full.columnSplitLength(columns, true, delayLineWrap, terminal)} 切它要显示的行，第二个
     * 参数是 <b>includeNewlines</b>：<b>按换行切出来的那一行会把 {@code '\n'} 留在行尾</b>。补全菜单
     * （{@code post}）是一整串带内嵌换行的文本，所以菜单一展开，捕获帧里大多数行——连同输入行本身——
     * 都以 {@code '\n'} 结尾。
     *
     * <p>不去掉它，这条会一路穿透[[活区]]的两个假设：{@code InputBoxChrome.washed} 逐字符拷贝、于是把
     * {@code '\n'} 一起拷进去，而 {@code columnLength()} 把它算作 <b>−1</b> 列
     * （{@code WCWidth.wcwidth('\n') == -1}），所以底纹补齐既多补一列、又补在了换行<b>之后</b>；随后
     * {@code LiveRegionStrip.hardTerminated()} 再补一个 {@code '\n'}，而 {@code Display.update} 只剥掉
     * 末尾那一个，内嵌的那个原样打给终端。于是一个「行」占了<b>两个</b>物理行，而活区所有的行数——
     * [[活区尾]]的预算、{@code lastRegionRows} 的回退距离——都按一行算，擦除因此永远擦不干净：菜单收起
     * 后候选行留在屏上，并把[[状态坞]]压掉半行（真机手工验收报的第三个缺陷）。
     *
     * <p>在这里去而不是在活区里去，是因为这是 JLine 的表示变成我们的表示的那一道边界：活区里其余的行
     * 全是我们自己排的、按构造满足「一行一个物理行」，唯一的外来行从这里进来。去掉之后
     * {@code columnLength()} 也重新等于可见列数，底纹补齐那处不必再单独设防。
     */
    private static List<AttributedString> rows(List<AttributedString> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<AttributedString> rows = new ArrayList<>(lines.size());
        for (AttributedString line : lines) {
            rows.add(withoutLineBreaks(line));
        }
        return List.copyOf(rows);
    }

    /** 去掉一行里的换行，保留每个字符自己的样式；没有换行时原样返回（常态，不分配）。 */
    private static AttributedString withoutLineBreaks(AttributedString line) {
        if (line == null) {
            return AttributedString.EMPTY;
        }
        int length = line.length();
        int first = -1;
        for (int i = 0; i < length; i++) {
            if (isLineBreak(line.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return line;
        }
        AttributedStringBuilder out = new AttributedStringBuilder();
        for (int i = 0; i < length; i++) {
            char c = line.charAt(i);
            if (isLineBreak(c)) {
                continue;
            }
            out.style(line.styleAt(i));
            out.append(c);
        }
        return out.toAttributedString();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static final Frame EMPTY = new Frame(List.of(), -1, 0, false);

    private final AtomicInteger updates = new AtomicInteger();
    private volatile Frame last = EMPTY;
    private volatile Consumer<Frame> frameListener;
    private volatile int columns;

    public CaptureDisplay(Terminal terminal) {
        super(terminal, false);
    }

    @Override
    public void resize(int rows, int columns) {
        this.columns = columns;
        super.resize(rows, columns);
    }

    @Override
    public void update(List<AttributedString> newLines, int targetCursorPos, boolean flush) {
        publish(new Frame(newLines, targetCursorPos, columns, flush));
        // 有意不调用 super：不写终端。
    }

    /** 输入行离开活区（{@code readLine} 收尾）时投一帧空的，让渲染线程把那几行擦掉。 */
    public void clearFrame() {
        if (!last.isEmpty()) {
            publish(EMPTY);
        }
    }

    private void publish(Frame frame) {
        last = frame;
        updates.incrementAndGet();
        Consumer<Frame> listener = frameListener;
        if (listener != null) {
            listener.accept(frame);
        }
    }

    /** 最新一帧；从不为 null。 */
    public Frame lastFrame() {
        return last;
    }

    /** 累计捕获帧数，供守卫测试与诊断。 */
    public int updateCount() {
        return updates.get();
    }

    /** 每次捕获后的回调（生产路径用来向渲染循环投一个事件）。 */
    public void setFrameListener(Consumer<Frame> listener) {
        this.frameListener = listener;
    }
}
