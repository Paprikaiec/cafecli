package com.cafecli.tool;

import java.util.Set;

public final class MutatingTools {
    /**
     * 有副作用的内置工具：审计（{@code 工具执行适配器}）与 HITL 审批（{@code ToolApprovalPolicy}）共用这一份清单。
     * MCP 工具不在此列，按命名空间前缀（{@link com.cafecli.mcp.protocol.McpToolDescriptor#isNamespaced}）另行纳入。
     */
    public static final Set<String> NAMES = Set.of(
            "write_file", "edit_file", "execute_command", "create_project", "revert_turn");

    private MutatingTools() {
    }
}
