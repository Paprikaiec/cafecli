package com.cafecli.hook;

import com.cafecli.policy.AuditLog;

/**
 * UserPromptSubmit 事件门：在 CLI 主路径上、提交展开后、跑 Agent 前对用户提交求值。
 *
 * BLOCK → 提交不进 Agent，原因给**用户**（不是模型）——例如拦下误粘的密钥；
 * additionalContext → 追加到实际交给 Agent 的 prompt——例如注入团队约定。
 * wave 1 无内建租户，只服务 shell 租户；由 {@code Agent.onUserPromptSubmit} 委派、Main / headless 调用。
 * 从零成本：分发器空 / 无 UPS 租户 / 总开关关时原样放行。
 */
public final class UserPromptSubmitGate {

    private final HookDispatcher dispatcher;
    private final String projectRoot;
    private final AuditLog auditLog;

    public UserPromptSubmitGate(HookDispatcher dispatcher, String projectRoot, AuditLog auditLog) {
        this.dispatcher = dispatcher;
        this.projectRoot = projectRoot;
        this.auditLog = auditLog;
    }

    /** UserPromptSubmit 决定：放行（带最终 prompt）或阻断（带给用户看的原因）。 */
    public record Decision(boolean blocked, String reason, String effectivePrompt) {
        public static Decision proceed(String prompt) {
            return new Decision(false, null, prompt);
        }

        public static Decision block(String reason) {
            return new Decision(true, reason, null);
        }
    }

    public Decision decide(String prompt) {
        if (dispatcher == null
                || !dispatcher.hasTenants(HookEvent.USER_PROMPT_SUBMIT)
                || !HookProperties.flag(HookProperties.USER_PROMPT_SUBMIT_ENABLED)) {
            return Decision.proceed(prompt);
        }
        HookPayload payload = HookPayload.forUserPromptSubmit(null, null, projectRoot, prompt);
        HookDecision decision = dispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT, payload);
        if (decision.isBlock()) {
            recordAudit(decision.reason());
            return Decision.block(decision.reason());
        }
        String context = decision.additionalContext();
        if (context != null && !context.isBlank()) {
            return Decision.proceed(prompt + "\n\n[Hook 上下文 / UserPromptSubmit]\n" + context.trim());
        }
        return Decision.proceed(prompt);
    }

    private void recordAudit(String reason) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(AuditLog.AuditEntry.hookBlock("UserPromptSubmit", reason, 0));
        } catch (Exception e) {
            // 审计失败不影响拦截本身
        }
    }
}
