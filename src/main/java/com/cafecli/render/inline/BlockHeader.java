package com.cafecli.render.inline;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * Plain dim/gray label for a collapsed block header (thinking / tool / code / diff).
 *
 * <p>The colored {@code ▎ } gutter bar was retired: block type is now read from the label wording
 * ("思考 N 行" / "ReadFile(...)" / "code: java" / "Update &lt;path&gt;  +A -D"). Every collapsed
 * header renders as the same subtly styled bright-black line so stacked blocks stay legible without
 * shouting the label in color or leaning on a per-type accent bar.
 */
final class BlockHeader {

    private static final AttributedStyle LABEL = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT);

    private BlockHeader() {
    }

    /** A collapsed header rendered as a single dim/gray label line. */
    static AttributedString label(String text) {
        return new AttributedString(text == null ? "" : text, LABEL);
    }

    /**
     * A header that names an action the user asked for — the [[工具块]]'s call header.
     *
     * <p>Ordinary foreground, not {@link #label}'s gray. The brightness ordering on a tool block's
     * rows should follow what the reader is looking for: <em>which command ran</em> is the thing they
     * scan for, its output is context, and "there is more of it" is a footnote. Reported on the first
     * real-terminal pass over ADR 0017 (issue 04) as exactly inverted — the header was gray while the
     * {@code … +N 行 (ctrl+o 查看全部)} line, carrying no style at all, came out at the terminal's
     * default bright foreground and dominated the block.
     *
     * <p>Thinking keeps {@link #label}: it is ambient, and it is the one kind of 过程 the user did not
     * ask for by name.
     */
    static AttributedString identity(String text) {
        return new AttributedString(text == null ? "" : text);
    }

    /**
     * The {@code … +N 行 (ctrl+o 查看全部)} footnote, dim.
     *
     * <p>The wording still lives in exactly one place ({@code BlockOverflow}, ADR 0017 D7); this is
     * only how the three inline blocks that overflow paint it. Styling it here rather than at each
     * call site is what stops the three from drifting apart — they were identical precisely because
     * none of them styled it at all, which is how it ended up brighter than the header.
     */
    static AttributedString footnote(String text) {
        return new AttributedString(text == null ? "" : text, AttributedStyle.DEFAULT.faint());
    }
}
