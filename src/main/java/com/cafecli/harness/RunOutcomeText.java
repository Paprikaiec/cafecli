package com.cafecli.harness;

import com.cafecli.core.BudgetReason;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.context.BudgetExit;
import java.util.Locale;

/** Shared v1-compatible final-answer presentation for headless output and foreground task results. */
public final class RunOutcomeText {
    private RunOutcomeText() { }

    public static String format(RunOutcome outcome) {
        return switch (outcome) {
            case RunOutcome.Completed completed -> completed.message().text();
            case RunOutcome.Cancelled ignored -> "⏹️ 已取消当前任务。";
            case RunOutcome.BudgetExhausted exhausted -> "❌ " + describe(exhausted.reason());
            case RunOutcome.Failed failed -> "❌ 调用 LLM 失败: " + failed.detail();
        };
    }

    /** v1's {@code AgentBudget.describeExit} text for the reasons v2 has so far. */
    private static String describe(BudgetReason reason) {
        if (reason instanceof BudgetReason.IterationCap cap) {
            return String.format(Locale.ROOT, "达到硬轮数上限（%d），已强制收尾", cap.maxIterations());
        }
        if (reason instanceof BudgetExit.Tokens tokens) {
            return String.format(Locale.ROOT, "Token 预算已用尽（%d / %d），任务被强制收尾", tokens.used(), tokens.limit());
        }
        if (reason instanceof BudgetExit.Stagnation stagnation) {
            return String.format(Locale.ROOT, "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾", stagnation.window());
        }
        return "预算已用尽（" + reason + "），已强制收尾";
    }

}
