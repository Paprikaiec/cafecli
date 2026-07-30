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
}
