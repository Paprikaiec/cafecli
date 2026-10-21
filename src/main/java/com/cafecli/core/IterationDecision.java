package com.cafecli.core;

import com.cafecli.core.log.CustomEntry;

import java.util.List;
import java.util.Objects;

/**
 * What {@link AgentCallbacks#onIterationEnd} decides: whether the run goes on after this iteration, and the entries to
 * append either way.
 */
public sealed interface IterationDecision {

    /** Entries to append after this iteration's tool results, whatever the decision. */
    List<CustomEntry> entries();

    /** Go on to the next iteration (unless the hard cap or cancellation stops the run). */
    static IterationDecision proceed(CustomEntry... entries) {
        return new Proceed(List.of(entries));
    }

    /** End the run: it completes with this iteration's answer. */
    static IterationDecision end(CustomEntry... entries) {
        return new End(List.of(entries));
    }

    /**
     * End the run because a harness budget ran out (token budget, stagnation): it ends with
     * {@link RunOutcome.BudgetExhausted} carrying {@code reason}.
     */
    static IterationDecision exhausted(BudgetReason reason, CustomEntry... entries) {
        return new Exhausted(reason, List.of(entries));
    }

    record Proceed(List<CustomEntry> entries) implements IterationDecision {
        public Proceed {
            entries = List.copyOf(entries);
        }
    }

    record End(List<CustomEntry> entries) implements IterationDecision {
        public End {
            entries = List.copyOf(entries);
        }
    }

    record Exhausted(BudgetReason reason, List<CustomEntry> entries) implements IterationDecision {
        public Exhausted {
            Objects.requireNonNull(reason, "reason");
            entries = List.copyOf(entries);
        }
    }
}
