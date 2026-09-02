package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.List;

/**
 * Committed output that reached [[冻结回卷]] as plain styled text rather than as a typed block:
 * whatever the line editor's intercepted {@code printAbove} handed over, which is every generic
 * {@code Renderer.stream()} line — the startup banner, slash-command results, MCP and Skill summaries,
 * background-task completion notices.
 *
 * <p>It is in the [[bounded replay window]] because a [[destructive resize reset]] would otherwise
 * destroy it outright — the case that decides this is a background task's completion notice, which
 * a user may never learn about if a window drag eats it (ADR 0010 amendment A8).
 *
 * <p>These lines are kept as the producer wrote them, not re-folded at the replay width, and that is
 * deliberate rather than a leftover. <b>Measured 2026-08-19</b>
 * ({@code ScreenTruthRegressionTest.committedStreamedOutputSurvivesNarrowingWhenItFitsTheRowBudget}:
 * 8 lines of 79 columns committed at width 100 and repainted at width 60): the terminal
 * <em>soft-wraps</em> an over-long committed row rather than clipping it, so the content survives
 * whole and is rejoined by a copy. Re-folding into hard rows here would split one logical line into
 * two for copy, search and history — worse, not better. What baking does cost is row-budget accuracy,
 * and that budget is advisory anyway because whole items render (A9). The [[提交块]] is the one case
 * where a baked width was visible, because its wash is padded to the full width; it keeps its source
 * instead ({@link SubmittedPromptItem}).
 */
final class FrozenTextItem implements StripEntry {

    private final List<AttributedString> lines;

    private FrozenTextItem(List<AttributedString> lines) {
        this.lines = List.copyOf(lines);
    }

    /** Split committed ANSI text into logical lines, dropping the newline terminating the last one. */
    static FrozenTextItem ofAnsi(String text) {
        List<AttributedString> lines = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            for (String line : body.split("\n", -1)) {
                lines.add(AttributedString.fromAnsi(line));
            }
        }
        return new FrozenTextItem(lines);
    }

    @Override
    public List<AttributedString> render(int width) {
        // Never live: it is committed by the time it becomes an item.
        return List.of();
    }

    @Override
    public List<AttributedString> renderRetained(int width) {
        return lines;
    }

    @Override
    public List<AttributedString> freezeTopRows(int minimumRows, int width) {
        return List.of();
    }

    @Override
    public boolean isReplaySealed() {
        // Streamed text carries its own blank lines; the strip never adds a separator after it.
        return false;
    }
}
