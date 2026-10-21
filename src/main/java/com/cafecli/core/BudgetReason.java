package com.cafecli.core;

/**
 * Which budget ended a run ({@link RunOutcome.BudgetExhausted}). Open: core's only budget is the hard iteration cap;
 * the harness's budgets (token budget, stagnation — ticket 12) bring their own reasons, through
 * {@link IterationDecision#exhausted}.
 */
public interface BudgetReason {

    /** The run made {@code maxIterations} iterations and the model still wanted to go on. */
    record IterationCap(int maxIterations) implements BudgetReason {
    }
}
