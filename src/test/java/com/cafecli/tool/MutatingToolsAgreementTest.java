package com.cafecli.tool;

import com.cafecli.mcp.protocol.McpToolDescriptor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计与 HITL 审批共用 {@link MutatingTools#NAMES} 这一份清单：
 * 钉住清单里的名字确实是已注册的工具（防拼写错误 / 改名漂移），
 * 并且 {@link ToolApprovalPolicy} 对内置工具的判定与清单逐一一致、对 MCP 的判定与命名空间前缀一致。
 */
class MutatingToolsAgreementTest {

    private static Set<String> defaultRegisteredToolNames() {
        return java.util.stream.Stream.concat(
                new WorkspaceTools(java.nio.file.Path.of("."), false, 60).tools().stream(),
                java.util.stream.Stream.of(SnapshotTools.revertTurn(() -> null)))
                .map(Tool::name)
                .collect(Collectors.toSet());
    }

    @Test
    void everyMutatingToolIsRegisteredByDefault() {
        Set<String> registered = defaultRegisteredToolNames();
        for (String name : MutatingTools.NAMES) {
            assertTrue(registered.contains(name), "MUTATING_TOOLS 里的 " + name + " 不是已注册的工具");
        }
    }

    @Test
    void approvalPolicyAgreesWithMutatingToolsForEveryRegisteredTool() {
        for (String name : defaultRegisteredToolNames()) {
            assertEquals(MutatingTools.NAMES.contains(name), ToolApprovalPolicy.requiresApproval(name),
                    "ToolApprovalPolicy 对 " + name + " 的审批判定与 MUTATING_TOOLS 不一致");
        }
    }

    @Test
    void approvalPolicyMcpDetectionAgreesWithNamespacePrefix() {
        List<String> samples = Arrays.asList(null, "", "mcp__", "mcp__x__y", "read_file", "xmcp__a");
        for (String name : samples) {
            assertEquals(McpToolDescriptor.isNamespaced(name), ToolApprovalPolicy.isMcpTool(name),
                    "isMcpTool 与 isNamespaced 对 " + name + " 的判定不一致");
        }
        assertTrue(McpToolDescriptor.isNamespaced("mcp__x__y"));
        assertFalse(McpToolDescriptor.isNamespaced("xmcp__a"));
        assertFalse(McpToolDescriptor.isNamespaced(null));
    }
}
