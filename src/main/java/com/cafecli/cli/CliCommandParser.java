package com.cafecli.cli;

public final class CliCommandParser {

    public enum CommandType {
        NONE,
        UNKNOWN_COMMAND,
        CANCEL,
        EXIT,
        TRANSCRIPT,
        CLEAR,
        HISTORY_CLEAR,
        SWITCH_MODEL,
        /** `/plan` 墓碑：Plan-and-Execute 已退役（ADR 0015），只打印说明，不落「未知命令」也不回退给 Agent。 */
        PLAN_RETIRED,
        SWITCH_HITL,
        MEMORY_STATUS,
        MEMORY_CLEAR,
        MEMORY_LIST,
        MEMORY_DELETE,
        MEMORY_SEARCH,
        MEMORY_SAVE,
        INDEX_CODE,
        SEARCH_CODE,
        GRAPH_QUERY,
        CONTEXT_STATUS,
        POLICY_STATUS,
        AUDIT_TAIL,
        SNAPSHOT,
        RESTORE_SNAPSHOT,
        MCP_LIST,
        MCP_RESTART,
        MCP_LOGS,
        MCP_DISABLE,
        MCP_ENABLE,
        MCP_RESOURCES,
        MCP_PROMPTS,
        BROWSER,
        TASK,
        SKILL_LIST,
        SKILL_SHOW,
        SKILL_ON,
        SKILL_OFF,
        SKILL_RELOAD,
        HOOKS,
        CONFIG
    }

    public record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        var parsed = com.cafecli.input.SlashCommandGrammar.parse(input);
        return new ParsedCommand(CommandType.valueOf(parsed.type().name()), parsed.payload());
    }
}
