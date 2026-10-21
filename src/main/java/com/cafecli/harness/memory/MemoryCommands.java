package com.cafecli.harness.memory;

import com.cafecli.memory.LongTermStore;
import com.cafecli.memory.LongTermMemory;
import com.cafecli.memory.MemoryEntry;
import java.nio.file.Path;
import java.io.File;
import java.util.List;

/** Current memory command parsing and output, independent of the UI. */
final class MemoryCommands {
    private final LongTermStore store;
    private final String project;
    MemoryCommands(LongTermStore store, String project) { this.store = store; this.project = project; }

    String save(String raw) {
        String fact = raw.trim();
        String scope = "project";
        if (fact.equalsIgnoreCase("--global") || fact.regionMatches(true, 0, "--global ", 0, 9)) {
            scope = "global"; fact = fact.substring(8).trim();
        } else if (fact.equalsIgnoreCase("--project") || fact.regionMatches(true, 0, "--project ", 0, 10)) {
            fact = fact.substring(9).trim();
        }
        if (fact.isEmpty()) return "❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答\n\n";
        store.save(fact, scope);
        return "💾 已保存到长期记忆(" + scope + "): " + fact + "\n\n";
    }

    String memory(String raw) {
        String[] parts = raw.trim().split("\\s+", 2);
        String argument = parts.length == 1 ? "" : parts[1];
        return switch (parts[0].toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> format("📋 长期记忆列表", store.list()) + "\n\n";
            case "search" -> argument.isBlank() ? "❌ 请提供搜索关键词，例如 /memory search Chrome 登录态\n\n"
                    : format("🔎 长期记忆搜索: " + argument, store.search(argument)) + "\n\n";
            case "delete" -> argument.isBlank() ? "❌ 请提供要删除的记忆 id，例如 /memory delete fact-abcd1234\n\n"
                    : (store.delete(argument) ? "🗑️ 已删除长期记忆: " : "📭 未找到长期记忆: ") + argument + "\n\n";
            case "clear" -> { store.clear(); yield "🧹 长期记忆已清空\n\n\n"; }
            default -> "📋 记忆系统状态：\n" + store.status() + "\n   当前项目作用域: " + project
                    + "\n   /memory list - 查看长期记忆\n   /memory search <关键词> - 搜索当前项目可见长期记忆"
                    + "\n   /memory delete <id> - 删除单条长期记忆\n   /memory clear - 清空长期记忆"
                    + "\n   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆\n\n";
        };
    }

    private static String format(String title, List<MemoryEntry> entries) {
        StringBuilder text = new StringBuilder(title).append("：\n");
        if (entries.isEmpty()) return text.append("📭 没有匹配的长期记忆。").toString();
        for (var entry : entries) {
            String scope = LongTermMemory.scopeOf(entry);
            String project = entry.getMetadata().get("project");
            text.append("- ").append(entry.getId()).append(" [").append(scope).append("]");
            if ("project".equals(scope) && project != null && !project.isBlank()) text.append(" ").append(shorten(project));
            text.append(" · ").append(entry.getTimestamp()).append("\n  ").append(entry.getContent()).append("\n");
        }
        return text.toString().trim();
    }
    private static String shorten(String project) {
        try {
            Path path = Path.of(project);
            return path.getNameCount() <= 3 ? project : "..." + File.separator + path.subpath(path.getNameCount() - 3, path.getNameCount());
        } catch (Exception ignored) { return project; }
    }
}
