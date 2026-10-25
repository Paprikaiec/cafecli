package com.cafecli.hitl;

import com.cafecli.tool.ToolApprovalPolicy;
import java.util.Set;

/** Legacy adapter to the shared tool approval metadata and classification. */
public final class ApprovalPolicy {
    private ApprovalPolicy() {}
    public static boolean requiresApproval(String tool) { return ToolApprovalPolicy.requiresApproval(tool); }
    public static String getDangerLevel(String tool) { return ToolApprovalPolicy.getDangerLevel(tool); }
    public static String getRiskDescription(String tool) { return ToolApprovalPolicy.getRiskDescription(tool); }
    public static Set<String> getDangerousTools() { return ToolApprovalPolicy.getDangerousTools(); }
    public static boolean isMcpTool(String tool) { return ToolApprovalPolicy.isMcpTool(tool); }
    public static String mcpServerName(String tool) { return ToolApprovalPolicy.mcpServerName(tool); }
}
