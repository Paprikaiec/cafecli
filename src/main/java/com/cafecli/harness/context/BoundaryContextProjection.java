package com.cafecli.harness.context;

import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.hooks.HookContext;
import com.cafecli.harness.lsp.LspDiagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Preserve diagnostic → hook → steering presentation within each custom-entry cohort, without changing
 * provenance or unrelated slots. Real user/assistant/result entries and normalized summaries delimit cohorts.
 */
public final class BoundaryContextProjection {
    private BoundaryContextProjection() {}

    public static List<LogEntry> apply(List<LogEntry> source) {
        var view = new ArrayList<>(source);
        for (int start = 0; start < view.size();) {
            if (!cohort(view.get(start))) { start++; continue; }
            int end = start + 1;
            while (end < view.size() && cohort(view.get(end))) end++;
            var ordered = view.subList(start,end).stream().filter(entry -> order(entry)>=0)
                    .sorted(Comparator.comparingInt(BoundaryContextProjection::order)).toList().iterator();
            // Unrelated custom entries retain their exact slots, even when they split the selected kinds.
            for (int index=start;index<end;index++) if (order(view.get(index))>=0) view.set(index,ordered.next());
            start = end;
        }
        return List.copyOf(view);
    }

    private static boolean cohort(LogEntry entry) {
        return entry instanceof CustomEntry && !(entry instanceof Summary);
    }

    private static int order(LogEntry entry) {
        if (entry instanceof LspDiagnostics) return 0;
        if (entry instanceof HookContext) return 1;
        if (entry instanceof Steering) return 2;
        return -1;
    }
}
