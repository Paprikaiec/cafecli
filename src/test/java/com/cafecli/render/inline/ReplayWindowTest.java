package com.cafecli.render.inline;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The [[bounded replay window]] as a pure function of items, width and row budget (ADR 0010
 * amendment A9): no terminal, no renderer, no I/O. This is where the bound's arithmetic — including
 * the block-quantization overshoot that the measurement surfaced — is pinned exactly, so the
 * screen-truth regressions can assert the user-visible contract without also encoding the formula.
 */
class ReplayWindowTest {

    /** A committed item of a known height, so budget arithmetic is readable in the assertions. */
    private static final class Item implements StripEntry {
        private final String label;
        private final int rowsPerScreenful;
        private final boolean sealed;

        Item(String label, int rows) {
            this(label, rows, true);
        }

        Item(String label, int rows, boolean sealed) {
            this.label = label;
            this.rowsPerScreenful = rows;
            this.sealed = sealed;
        }

        @Override
        public List<AttributedString> render(int width) {
            return List.of();
        }

        @Override
        public List<AttributedString> renderRetained(int width) {
            List<AttributedString> rows = new ArrayList<>();
            for (int i = 0; i < rowsPerScreenful; i++) {
                rows.add(new AttributedString(label + "/" + i + "@" + width));
            }
            return rows;
        }

        @Override
        public List<AttributedString> freezeTopRows(int minimumRows, int width) {
            return List.of();
        }

        @Override
        public boolean isReplaySealed() {
            return sealed;
        }
    }

    private static List<String> texts(List<AttributedString> rows) {
        return rows.stream().map(AttributedString::toString).toList();
    }

    @Test
    void anEmptyLogReplaysNothing() {
        ReplayWindow.View view = ReplayWindow.select(List.of(), 80, 36);

        assertTrue(view.items().isEmpty());
        assertTrue(view.rows().isEmpty());
    }

    @Test
    void aLogSmallerThanTheBudgetIsReplayedWhole() {
        List<StripEntry> log = List.of(new Item("a", 1), new Item("b", 1));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 36);

        assertEquals(List.of("a/0@80", "", "b/0@80", ""), texts(view.rows()));
    }

    @Test
    void eachSealedItemCarriesItsSingleSeparatorBlank() {
        List<StripEntry> log = List.of(new Item("a", 2));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 36);

        assertEquals(List.of("a/0@80", "a/1@80", ""), texts(view.rows()));
    }

    @Test
    void anUnsealedItemDoesNotEarnASeparatorYet() {
        // A still-streaming answer has not claimed its trailing blank, so the reset must not invent
        // one: the live tail continues the same block directly below.
        List<StripEntry> log = List.of(new Item("a", 1, false));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 36);

        assertEquals(List.of("a/0@80"), texts(view.rows()));
    }

    @Test
    void theWindowFillsBackwardsFromTheNewestUntilTheBudgetIsMet() {
        List<StripEntry> log = List.of(
                new Item("oldest", 1), new Item("older", 1), new Item("newer", 1),
                new Item("newest", 1));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 4);

        assertEquals(List.of("newer/0@80", "", "newest/0@80", ""), texts(view.rows()));
        assertEquals(2, view.items().size(), "older items are outside the window, and are lost");
    }

    @Test
    void itemsAreRenderedAtTheRequestedWidthNotTheOneTheyWereCommittedAt() {
        List<StripEntry> log = List.of(new Item("a", 1));

        assertEquals(List.of("a/0@42", ""), texts(ReplayWindow.select(log, 42, 36).rows()));
    }

    @Test
    void theItemThatCrossesTheBudgetIsStillRenderedWhole() {
        // Measured on the real retained model: a 44-row budget rendered 104 rows and a 132-row
        // budget rendered 207, because whole blocks render. The overshoot is accepted rather than
        // cutting a block in half — a half-rendered diff or table is worse than a taller repaint,
        // and block granularity, not the multiplier, decides the real repaint size.
        List<StripEntry> log = List.of(new Item("old", 4), new Item("huge", 60));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 10);

        assertEquals(1, view.items().size());
        assertEquals(61, view.rows().size(), "the newest item overshoots the budget rather than being cut");
    }

    @Test
    void theNewestItemIsAlwaysReplayedEvenWhenItAloneExceedsTheBudget() {
        List<StripEntry> log = List.of(new Item("huge", 200));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 10);

        assertEquals(1, view.items().size(), "a reset must never repaint an empty screen");
        assertEquals(201, view.rows().size());
    }

    @Test
    void itemsThatHaveCommittedNothingContributeNoRowsAndDoNotConsumeTheBudget() {
        List<StripEntry> log = List.of(new Item("a", 1), new Item("empty", 0), new Item("b", 1));

        ReplayWindow.View view = ReplayWindow.select(log, 80, 36);

        assertEquals(List.of("a/0@80", "", "b/0@80", ""), texts(view.rows()));
        assertEquals(2, view.items().size());
    }

    @Test
    void theDefaultBudgetIsThreeScreenfulsOfTheCurrentHeight() {
        assertEquals(132, ReplayWindow.budgetRows(44));
        assertEquals(36, ReplayWindow.budgetRows(12));
    }
}
