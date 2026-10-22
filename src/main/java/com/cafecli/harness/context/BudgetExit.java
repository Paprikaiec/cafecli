package com.cafecli.harness.context;

import com.cafecli.core.BudgetReason;

/** Typed reasons for harness run-budget exits. */
public sealed interface BudgetExit extends BudgetReason {
    record Tokens(int limit, long used) implements BudgetExit { }
    record Stagnation(int window) implements BudgetExit { }
}
