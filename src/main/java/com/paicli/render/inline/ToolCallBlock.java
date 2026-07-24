package com.paicli.render.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed foldable tool-call group preserving compact text labels and key parameters. */
final class ToolCallBlock extends LiveFoldableBlock {

    private static final ObjectMapper JSON = new ObjectMapper();

    ToolCallBlock(Terminal terminal, List<LlmClient.ToolCall> toolCalls) {
        this(terminal, group(toolCalls));
    }

    private ToolCallBlock(Terminal terminal, Map<String, List<LlmClient.ToolCall>> grouped) {
        super(terminal, List.of(BlockHeader.label(collapsedHeader(grouped))),
                expanded(grouped), false);
    }

    private static List<AttributedString> expanded(Map<String, List<LlmClient.ToolCall>> grouped) {
        List<AttributedString> lines = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            lines.add(AttributedString.fromAnsi(AnsiStyle.subtle(
                    "  " + toolLabel(group.getKey(), group.getValue().size()))));
            for (LlmClient.ToolCall call : group.getValue()) {
                String detail = extractKeyParam(group.getKey(), call.function().arguments());
                if (!detail.isEmpty()) {
                    lines.add(AttributedString.fromAnsi(AnsiStyle.subtle("    └ " + detail)));
                }
            }
        }
        return List.copyOf(lines);
    }

    private static Map<String, List<LlmClient.ToolCall>> group(List<LlmClient.ToolCall> calls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall call : calls) {
            grouped.computeIfAbsent(call.function().name(), ignored -> new ArrayList<>()).add(call);
        }
        return grouped;
    }

    private static String collapsedHeader(Map<String, List<LlmClient.ToolCall>> grouped) {
        if (grouped.size() == 1) {
            var entry = grouped.entrySet().iterator().next();
            return collapsedLabel(entry.getKey(), entry.getValue());
        }
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return grouped.size() + " 组工具调用 / " + total + " 次";
    }

    private static String collapsedLabel(String name, List<LlmClient.ToolCall> calls) {
        int count = calls.size();
        String label = toolLabel(name, count);
        if (count != 1) {
            return label;
        }
        String detail = extractKeyParam(name, calls.get(0).function().arguments());
        if (detail.isBlank()) {
            return label;
        }
        return switch (name) {
            case "web_search" -> "WebSearch(\"" + detail + "\")";
            case "web_fetch" -> "WebFetch(" + compactUrl(detail) + ")";
            case "search_code" -> "SearchCode(\"" + detail + "\")";
            case "read_file" -> "ReadFile(" + detail + ")";
            case "list_dir" -> "ListDir(" + detail + ")";
            case "execute_command" -> "Shell(" + detail + ")";
            default -> label + " · " + detail;
        };
    }

    private static String toolLabel(String name, int count) {
        return switch (name) {
            case "read_file" -> "读取 " + count + " 个文件";
            case "write_file" -> "写入 " + count + " 个文件";
            case "list_dir" -> "列出 " + count + " 个目录";
            case "execute_command" -> "执行 " + count + " 条命令";
            case "create_project" -> "创建 " + count + " 个项目";
            case "search_code" -> "搜索代码 " + count + " 次";
            case "web_search" -> "联网搜索 " + count + " 次";
            case "web_fetch" -> "抓取 " + count + " 个网页";
            case "save_memory" -> "保存长期记忆 " + count + " 条";
            default -> name != null && name.startsWith("mcp__")
                    ? mcpLabel(name, count) : name + " × " + count;
        };
    }

    private static String mcpLabel(String name, int count) {
        String[] parts = name.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : name;
        return count == 1 ? "调用 MCP 工具 " + display
                : "调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String name, String json) {
        try {
            JsonNode node = JSON.readTree(json);
            String key = switch (name) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            String value = key == null ? (json == null ? "" : json) : node.path(key).asText("");
            return value.length() > 80 ? value.substring(0, 77) + "..." : value;
        } catch (Exception ignored) {
            if (json == null) {
                return "";
            }
            return json.length() > 80 ? json.substring(0, 77) + "..." : json;
        }
    }

    private static String compactUrl(String url) {
        String value = url == null ? "" : url.trim()
                .replaceFirst("^https?://", "").replaceFirst("/+$", "");
        return value.length() > 80 ? value.substring(0, 77) + "..." : value;
    }
}
