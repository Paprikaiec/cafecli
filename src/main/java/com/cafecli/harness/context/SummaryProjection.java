package com.cafecli.harness.context;

import com.cafecli.core.log.LogEntry;
import java.util.ArrayList;
import java.util.List;

/** Pure range replacement: later summaries supersede earlier summaries of their covered prefix. */
public final class SummaryProjection {
    private SummaryProjection() { }
    public static List<LogEntry> apply(List<LogEntry> log) {
        List<Summary> summaries = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i) instanceof Summary summary) {
                if (summary.toExclusive() > i) throw new IllegalArgumentException("summary must cover earlier entries");
                summaries.removeIf(old -> old.fromInclusive() >= summary.fromInclusive()
                        && old.toExclusive() <= summary.toExclusive());
                if (summaries.stream().anyMatch(old -> old.fromInclusive() < summary.toExclusive()
                        && old.toExclusive() > summary.fromInclusive())) {
                    throw new IllegalArgumentException("summary ranges must be disjoint or superseded");
                }
                summaries.add(summary);
            }
        }
        List<LogEntry> view = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            boolean covered = false;
            for (Summary summary : summaries) {
                if (summary.fromInclusive() == i) view.add(summary);
                if (i >= summary.fromInclusive() && i < summary.toExclusive()) covered = true;
            }
            if (!covered && !(log.get(i) instanceof Summary)) view.add(log.get(i));
        }
        return List.copyOf(view);
    }
}
