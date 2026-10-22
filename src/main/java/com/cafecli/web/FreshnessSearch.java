package com.cafecli.web;

import java.util.Locale;

/** Shared trigger, query and exact v1 freshness entry text. */
public final class FreshnessSearch {
    private FreshnessSearch() { }
    public static boolean shouldRun(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("不要联网") || normalized.contains("不用联网")
                || normalized.contains("不要搜索") || normalized.contains("不用搜索")
                || normalized.contains("别搜索") || normalized.contains("offline")) {
            return false;
        }
        return normalized.contains("最新")
                || normalized.contains("当前")
                || normalized.contains("今天")
                || normalized.contains("今年")
                || normalized.contains("现在")
                || normalized.contains("新闻")
                || normalized.contains("趋势")
                || normalized.contains("版本")
                || normalized.contains("2026")
                || normalized.contains("latest")
                || normalized.contains("current")
                || normalized.contains("recent");
    }

    public static String query(String userInput) {
        String cleaned = userInput == null ? "" : userInput
                .replaceAll("@image:(<[^>]+>|\\S+)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("ai agent") && (cleaned.contains("2026") || cleaned.contains("技术栈") || cleaned.contains("趋势"))) {
            return "2026 AI Agent 技术栈 趋势 MCP A2A Agents SDK LangGraph CrewAI AutoGen";
        }
        if (cleaned.length() > 160) {
            return cleaned.substring(0, 160);
        }
        return cleaned;
    }

    public static String success(String query, String result) {
        return """
                    ## 联网预检结果

                    用户问题包含最新/当前/2026/趋势等时效性需求。以下是本轮进入模型回答前自动执行 `web_search` 得到的结果；回答必须优先基于这些联网结果和用户提供的图片，不要声称无法实时搜索。

                    查询：%s

                    %s
                    """.formatted(query, result == null ? "" : result.trim());
    }
    public static String failure(String reason) {
        return """
                    ## 联网预检结果

                    本轮问题需要最新信息，系统已尝试自动联网搜索，但搜索执行失败：%s
                    回答时必须说明这是联网失败后的降级结果，不要声称工具不可用。
                    """.formatted(reason);
    }
}
