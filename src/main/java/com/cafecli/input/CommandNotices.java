package com.cafecli.input;

/** Shared CLI notices whose bytes are a compatibility contract. */
public final class CommandNotices {
    private CommandNotices() {}
    public static String planTombstone() {
        return """
                📋 Plan-and-Execute（`/plan`）已移除 —— 见 ADR 0015（docs/adr/0015-retire-plan-and-execute-and-lanterna-tui.md）。
                   它与已退役的 `/team` 是同一物种：模型之外的确定性编排脚手架。
                   直接把任务描述给默认的 ReAct 即可；需要拆分并行推进时，主 Agent 会自己用 spawn_agent 派生子任务。
                """;
    }

}
