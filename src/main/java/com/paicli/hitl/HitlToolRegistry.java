package com.paicli.hitl;

import com.paicli.browser.BrowserCheckResult;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    // 审批请求的「请求方」标注（ADR 0005）：主 Agent / task_N，渲染在审批 UI 的「来源」字段。
    private volatile String requesterLabel = "主 Agent";

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    /**
     * 子任务的 HITL 工具表：共享派生者的昂贵服务（经 {@link ToolRegistry#ToolRegistry(ToolRegistry)}，ADR 0007），
     * 并复用<b>同一个</b> HITL handler（共享审批泵）——于是子任务的危险工具照常上浮审批、且权限 ≤ 派生者
     * （不凭空提权）。
     */
    public HitlToolRegistry(ToolRegistry parent, HitlHandler hitlHandler) {
        super(parent);
        this.hitlHandler = hitlHandler;
    }

    /** 派生子任务的 HITL 工具表（共享服务 + 同一审批泵），使子任务危险工具也走 HITL（不再无审批裸跑）。 */
    @Override
    public HitlToolRegistry createChildRegistry() {
        return new HitlToolRegistry(this, hitlHandler);
    }

    /** 设置审批请求的请求方标注（主 Agent / task_N）。 */
    public void setRequesterLabel(String requesterLabel) {
        this.requesterLabel = requesterLabel == null || requesterLabel.isBlank() ? "主 Agent" : requesterLabel;
    }

    public String getRequesterLabel() {
        return requesterLabel;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, browserCheck.sensitiveNotice());
        }
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, null);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, requesterLabel, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
