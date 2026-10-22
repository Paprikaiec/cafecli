package com.cafecli.harness.config;

/** Explicit hard token budget and repeated-tool window; context-window estimates are not a hard run budget. */
public record BudgetConfig(int tokenLimit, int stagnationWindow) {
    public static final BudgetConfig DEFAULT = new BudgetConfig(Integer.MAX_VALUE, 3);
    public BudgetConfig {
        if (tokenLimit <= 0) throw new IllegalArgumentException("tokenLimit must be positive");
        if (stagnationWindow < 2) throw new IllegalArgumentException("stagnationWindow must be >= 2");
    }
}
