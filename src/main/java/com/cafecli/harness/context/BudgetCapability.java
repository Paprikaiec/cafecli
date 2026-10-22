package com.cafecli.harness.context;

import com.cafecli.core.IterationDecision;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.config.BudgetConfig;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Run-local token accumulation and source-order raw-argument stagnation detection, after tools and Stop. */
public final class BudgetCapability implements Capability {
    private final BudgetConfig config;
    public BudgetCapability(BudgetConfig config) { this.config = Objects.requireNonNull(config, "config"); }

    @Override public void attachAgent(AgentScope scope) {
        State state = new State();
        scope.runStartWriter((input, token) -> {
            state.used = 0;
            state.recent.clear();
            return List.of();
        });
        scope.iterationBudget((message, results) -> {
            state.used += Math.max(0, message.usage().inputTokens()) + (long)Math.max(0, message.usage().outputTokens());
            var calls = message.toolCalls();
            if (!calls.isEmpty()) {
                state.recent.addLast(calls.stream().map(call -> new Invocation(call.name(), call.arguments())).toList());
                if (state.recent.size() > config.stagnationWindow()) state.recent.removeFirst();
                if (state.recent.size() == config.stagnationWindow()
                        && state.recent.stream().allMatch(state.recent.getFirst()::equals)) {
                    return IterationDecision.exhausted(new BudgetExit.Stagnation(config.stagnationWindow()));
                }
            }
            return state.used >= config.tokenLimit()
                    ? IterationDecision.exhausted(new BudgetExit.Tokens(config.tokenLimit(), state.used))
                    : IterationDecision.proceed();
        });
    }
    private record Invocation(String name, String arguments) { }
    private static final class State {
        long used;
        final ArrayDeque<List<Invocation>> recent = new ArrayDeque<>();
    }
}
