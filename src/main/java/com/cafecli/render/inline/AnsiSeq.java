package com.cafecli.render.inline;

/**
 * 原生 ANSI 控制序列常量与工具方法。
 *
 * <p>区别于 {@link com.cafecli.util.AnsiStyle}（只做颜色与文字样式），
 * 这里覆盖光标控制、行清理、滚动区域等结构化操作，inline 流式 TUI 的所有
 * 局部重绘 / 底部状态栏都依赖这些原语。
 *
 * <p>所有常量保持 ESC（）开头，方便直接 {@code System.out.print}。
 */
public final class AnsiSeq {

    public static final String ESC = "";

    // === 光标 ===
    /** 保存光标位置（DECSC）。 */
    public static final String SAVE_CURSOR = ESC + "7";
    /** 恢复光标位置（DECRC）。 */
    public static final String RESTORE_CURSOR = ESC + "8";
    /** 隐藏光标。 */
    public static final String HIDE_CURSOR = ESC + "[?25l";
    /** 显示光标。 */
    public static final String SHOW_CURSOR = ESC + "[?25h";
    /** Begin synchronized terminal output (DEC private mode 2026). */
    public static final String BEGIN_SYNCHRONIZED_UPDATE = ESC + "[?2026h";
    /** End synchronized terminal output (DEC private mode 2026). */
    public static final String END_SYNCHRONIZED_UPDATE = ESC + "[?2026l";

    // === 清除 ===
    /** 清除当前行。 */
    public static final String CLEAR_LINE = ESC + "[2K";
    /** 清除从光标到行尾。 */
    public static final String CLEAR_TO_EOL = ESC + "[K";
    /** 清除从光标到屏幕末尾。 */
    public static final String CLEAR_TO_EOS = ESC + "[J";
    /**
     * The whole erase half of a [[destructive resize reset]]: the visible screen (CSI 2 J), then the
     * terminal's saved scrollback (CSI 3 J), then the cursor home.
     *
     * <p><b>The order is the contract, and it is not the intuitive one.</b> Terminal.app does not
     * simply blank the visible rows on CSI 2 J — it <b>pushes them into the saved scrollback first</b>,
     * which is why {@code clear(1)} leaves the pre-clear screen scrollable. Erasing the scrollback
     * first therefore wiped it and then immediately deposited the screen back into it: every reset
     * left exactly one screenful behind, and when that screenful happened to be a repaint rather than
     * the blank the gate normally holds, the [[活区]] came with it — the reported "one stale spinner
     * and dock, one replay window above the real one" (measured 2026-08-21: 8 × {@code heightdrag 6},
     * every run deposited one screenful, one of eight deposited a live region).
     *
     * <p>Scrollback-erase-last destroys whatever the screen erase pushed. Cursor-home-last means the
     * final position does not depend on how a terminal chooses to implement ED(2) — a scroll of the
     * whole screen moves the cursor on some, not on others.
     *
     * <p>Sent unconditionally — no capability probe, no terminal allowlist (ADR 0010 amendment A15).
     * Erasing the visible screen is universal, so a terminal that ignores the scrollback erase still
     * gets a correct visible screen and degrades to duplication confined to scrollback. CSI 3 J is
     * the exact sequence measured against Terminal.app by the {@code erase-saved-scrollback}
     * conformance case, reproduced twice with byte-identical results.
     *
     * <p>It is geometry-independent, which is what lets it be written eagerly on every observed
     * dimension change while the geometry-sensitive redraw waits for the geometry to settle.
     */
    public static final String ERASE_SCREEN_AND_SAVED_SCROLLBACK = ESC + "[2J" + ESC + "[3J" + ESC + "[H";

    /**
     * The visible screen only (CSI 2 J), then the cursor home — <b>no</b> saved-scrollback erase.
     *
     * <p>For a modal repainting its own alternate screen after a resize (ADR 0010 amendment A12).
     * The alternate screen has no scrollback of its own, so there is nothing there to erase; the
     * scrollback the terminal is holding belongs to the primary screen underneath, and destroying it
     * from inside a modal would cost the user history no window change of theirs asked to lose.
     */
    public static final String ERASE_SCREEN = ESC + "[2J" + ESC + "[H";

    // === 滚动区域（DECSTBM） ===
    /** 重置滚动区域为整个屏幕。 */
    public static final String RESET_SCROLL_REGION = ESC + "[r";

    // === 文本样式 ===
    public static final String REVERSE_ON = ESC + "[7m";
    public static final String REVERSE_OFF = ESC + "[27m";
    public static final String RESET = ESC + "[0m";
    public static final String BOLD = ESC + "[1m";
    public static final String DIM = ESC + "[2m";

    private AnsiSeq() {
    }

    /** 设置滚动区域为第 {@code top}..{@code bottom} 行（1-based，闭区间）。 */
    public static String setScrollRegion(int top, int bottom) {
        return ESC + "[" + top + ";" + bottom + "r";
    }

    /** 移动光标到 ({@code row}, {@code col})（1-based）。 */
    public static String moveCursor(int row, int col) {
        return ESC + "[" + row + ";" + col + "H";
    }

    /** 上移 {@code n} 行。 */
    public static String moveUp(int n) {
        return ESC + "[" + n + "A";
    }

    /** 下移 {@code n} 行。 */
    public static String moveDown(int n) {
        return ESC + "[" + n + "B";
    }
}
