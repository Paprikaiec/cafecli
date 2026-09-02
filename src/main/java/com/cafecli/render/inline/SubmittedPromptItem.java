package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.List;

/**
 * The user's [[提交块]] as a committed item of the ordered commit log, held in <b>source</b> form.
 *
 * <p>It keeps the submitted text, not the rows it was laid out into, because a
 * [[destructive resize reset]] repaints from this log at whatever width the terminal now has (ADR 0010
 * amendment A8/A11). The block pads its dim wash out to the full terminal width, so rows baked at the
 * width it was submitted at leave a ragged stub of wash on a second row when repainted narrower — an
 * accepted cost while [[冻结回卷]] was never reflowed, and not one now that a reset re-renders it.
 *
 * <p>The same source produces both renderings: {@link #ansiText(int)} for the write that commits it,
 * which is the one that must honour {@code NO_COLOR} exactly as the plain renderer does, and
 * {@link #replayRows(int)} for a reset. Neither is derived from the other.
 */
final class SubmittedPromptItem implements StripEntry {

    private final Terminal terminal;
    private final String source;

    private SubmittedPromptItem(Terminal terminal, String source) {
        this.terminal = terminal;
        this.source = source;
    }

    /** The item for {@code prompt}, or {@code null} when it would render nothing at all. */
    static SubmittedPromptItem of(Terminal terminal, String prompt) {
        String visible = prompt == null ? "" : prompt.strip();
        return visible.isEmpty() ? null : new SubmittedPromptItem(terminal, visible);
    }

    /**
     * The block laid out one column narrower than the terminal, matching the dock: a row that fills
     * its last column leaves the cursor in an ambiguous place on terminals that delay the wrap, and
     * the padding is dim filler either way.
     */
    private static int bodyWidth(int width) {
        return Math.max(20, Math.max(1, width) - 1);
    }

    /** The committed text, ready to write; blank when ANSI is disabled degrades to plain text. */
    String ansiText(int width) {
        return SubmittedPromptBlock.render(source, bodyWidth(width), terminal);
    }

    @Override
    public List<AttributedString> render(int width) {
        // Never live: the echo is committed the moment the submission happens.
        return List.of();
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return SubmittedPromptBlock.rows(source, bodyWidth(width), terminal);
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        return List.of();
    }

    @Override
    public boolean isReplaySealed() {
        // The block carries its own trailing newline; the strip never adds a separator after it.
        return false;
    }
}
