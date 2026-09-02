package com.cafecli.render.inline;

import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The [[bounded replay window]]: the newest few screenfuls of committed output, rendered at a given
 * width (ADR 0010 amendment A9).
 *
 * <p>A pure function of items, width and budget — no terminal, no clock, no renderer state. That is
 * deliberate: the bound is the one part of a [[destructive resize reset]] whose arithmetic can be
 * wrong in a way the screen cannot show, so it is pinned directly instead of inferred from a
 * repaint.
 *
 * <p>Two properties are worth stating because they are choices, not consequences:
 *
 * <ul>
 *   <li><b>Rendered backwards from the newest.</b> Items beyond the budget are never laid out at the
 *       new width at all, which matters because the render cache is keyed on width and a resize
 *       misses every entry by construction.</li>
 *   <li><b>The budget is advisory, because whole items render.</b> The item that crosses the budget
 *       is emitted whole; measured on the real retained model, a 44-row budget produced 104 rows and
 *       a 132-row budget produced 207. Cutting a block in half would repaint a truncated diff or
 *       table, which is worse than a taller repaint — and the repaint is ≤12 KB either way.</li>
 * </ul>
 *
 * <p>Which items survive is therefore width-dependent, since narrowing makes the same content
 * occupy more rows. That is correct for a bound whose purpose is to cap cost.
 *
 * <p>What it selects over are the log's <b>positions</b> ({@link CommittedSpan}), not whole entries
 * (ADR 0016 D5), so an answer interrupted by a [[转向消息]] arrives here as two of them with the echo
 * between. Nothing here needs to know that: a position renders the range it covers, and it owns the
 * trailing separator blank only if it is its source's last one — which is what keeps an interrupted
 * answer reading as a single block instead of sprouting a blank where the correction landed.
 */
final class ReplayWindow {

    /** How many screenfuls of the current terminal height a reset repaints. */
    static final int DEFAULT_SCREENFULS = 3;

    private ReplayWindow() {
    }

    /** The row budget for a terminal of {@code terminalRows} rows. */
    static int budgetRows(int terminalRows) {
        return Math.max(1, terminalRows) * DEFAULT_SCREENFULS;
    }

    /** The selected items, oldest first, and the rows a reset writes for them. */
    record View(List<StripEntry> items, List<AttributedString> rows) {
        View {
            items = items == null ? List.of() : List.copyOf(items);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    static View select(List<StripEntry> items, int width, int budgetRows) {
        if (items == null || items.isEmpty()) {
            return new View(List.of(), List.of());
        }
        int columns = Math.max(1, width);
        int budget = Math.max(1, budgetRows);
        List<StripEntry> selected = new ArrayList<>();
        List<List<AttributedString>> selectedRows = new ArrayList<>();
        int accumulated = 0;
        for (int i = items.size() - 1; i >= 0 && accumulated < budget; i--) {
            StripEntry item = items.get(i);
            List<AttributedString> rows = rowsOf(item, columns);
            if (rows.isEmpty()) {
                continue;
            }
            selected.add(item);
            selectedRows.add(rows);
            accumulated += rows.size();
        }
        Collections.reverse(selected);
        Collections.reverse(selectedRows);
        List<AttributedString> rows = new ArrayList<>(accumulated);
        selectedRows.forEach(rows::addAll);
        return new View(selected, rows);
    }

    private static List<AttributedString> rowsOf(StripEntry item, int columns) {
        List<AttributedString> replayed = item.replayRows(columns);
        if (replayed == null || replayed.isEmpty()) {
            return List.of();
        }
        if (!item.isReplaySealed()) {
            return replayed;
        }
        List<AttributedString> rows = new ArrayList<>(replayed.size() + 1);
        rows.addAll(replayed);
        rows.add(AttributedString.EMPTY);
        return rows;
    }
}
