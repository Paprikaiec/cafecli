package com.cafecli.hitl;

/**
 * 审批请求 - 描述一次待确认的工具调用
 *
 * 包含工具调用的完整信息，用于向用户展示"即将执行什么操作"。
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion,
        String callerContext,
        String sensitiveNotice
) {
    public static ApprovalRequest of(String toolName, String arguments, String suggestion) {
        return of(toolName, arguments, suggestion, null);
    }

    public static ApprovalRequest of(String toolName, String arguments, String suggestion, String callerContext) {
        return of(toolName, arguments, suggestion, callerContext, null);
    }

    public static ApprovalRequest of(String toolName, String arguments, String suggestion, String callerContext,
                                     String sensitiveNotice) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName),
                suggestion,
                callerContext,
                sensitiveNotice
        );
    }

    public com.cafecli.render.ApprovalRequest renderView() {
        return new com.cafecli.render.ApprovalRequest(toolName, arguments, dangerLevel, riskDescription,
                suggestion, callerContext, sensitiveNotice);
    }
    public String toDisplayText() { return renderView().toDisplayText(); }
}
