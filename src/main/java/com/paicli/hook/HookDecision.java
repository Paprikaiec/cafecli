package com.paicli.hook;

/**
 * 单个 [[Hook 租户]] 对一个生命周期点给出的**模型反馈级**决定。
 *
 * 四 outcome（ADR 0012，locked）：
 * <ul>
 *   <li>{@code CONTINUE} —— 无意见，放行。</li>
 *   <li>{@code BLOCK(reason)} —— 阻断并把 reason 回灌模型续跑；不是安全拒绝。</li>
 *   <li>{@code ALLOW} —— 仅免除 HITL 审批，永不豁免 [[策略守卫]]。</li>
 *   <li>{@code ASK} —— 强制 HITL 审批。</li>
 * </ul>
 * 单一 BLOCK verb——不搬 Claude Code 的 legacy 双通道。多租户组合取最严：
 * 任一 BLOCK 短路、ASK 压过 ALLOW（见 {@link Outcome} 的序）。
 *
 * {@code additionalContext} / {@code statusMessage} 是非阻断的附带信息；组合时 context 聚合。
 *
 * {@code updatedInput} / {@code updatedToolOutput} / {@code async} 是 wave 2 预留字段：
 * schema 现在就带上，dispatcher 检测到有值时 warn-and-ignore（见 {@link HookDispatcher}），
 * 让 wave 2 纯增量。
 */
public record HookDecision(
        Outcome outcome,
        String reason,
        String additionalContext,
        String statusMessage,
        // ---- wave 2 预留，dispatcher 警告并忽略 ----
        String updatedInput,
        String updatedToolOutput,
        boolean async) {

    /** outcome 严重度按 ordinal 升序：CONTINUE < ALLOW < ASK < BLOCK。组合时取序最大者，BLOCK 直接短路。 */
    public enum Outcome {
        CONTINUE,
        ALLOW,
        ASK,
        BLOCK
    }

    public static HookDecision cont() {
        return new HookDecision(Outcome.CONTINUE, null, null, null, null, null, false);
    }

    public static HookDecision block(String reason) {
        return new HookDecision(Outcome.BLOCK, reason, null, null, null, null, false);
    }

    public static HookDecision allow() {
        return new HookDecision(Outcome.ALLOW, null, null, null, null, null, false);
    }

    public static HookDecision ask() {
        return new HookDecision(Outcome.ASK, null, null, null, null, null, false);
    }

    public HookDecision withAdditionalContext(String context) {
        return new HookDecision(outcome, reason, context, statusMessage, updatedInput, updatedToolOutput, async);
    }

    public HookDecision withStatusMessage(String message) {
        return new HookDecision(outcome, reason, additionalContext, message, updatedInput, updatedToolOutput, async);
    }

    public boolean isBlock() {
        return outcome == Outcome.BLOCK;
    }

    /** wave 2 预留字段是否被填过——dispatcher 据此 warn-and-ignore。 */
    public boolean hasReservedFields() {
        return updatedInput != null || updatedToolOutput != null || async;
    }
}
