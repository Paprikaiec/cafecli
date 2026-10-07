package com.cafecli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema
) {
    /** MCP 动态工具的命名空间前缀：工具名形如 {@code mcp__{server}__{tool}}。 */
    public static final String NAMESPACE_PREFIX = "mcp__";

    /** 某个 server 下全部工具名的公共前缀：{@code mcp__{server}__}。 */
    public static String serverPrefix(String serverName) {
        return NAMESPACE_PREFIX + serverName + "__";
    }

    public static String namespaced(String serverName, String toolName) {
        return serverPrefix(serverName) + toolName;
    }

    /** 是否落在 MCP 命名空间内（只看前缀，不校验 server 是否存在）。 */
    public static boolean isNamespaced(String toolName) {
        return toolName != null && toolName.startsWith(NAMESPACE_PREFIX);
    }
}
