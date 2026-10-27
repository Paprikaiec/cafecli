package com.cafecli.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandGrammarTest {

    @Test
    void parsesTranscriptOverlayCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/transcript");

        assertEquals(SlashCommandGrammar.CommandType.TRANSCRIPT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesRetiredPlanCommandAsTombstoneNotUnknownCommand() {
        SlashCommandGrammar.ParsedCommand bare = SlashCommandGrammar.parse("/plan");
        assertEquals(SlashCommandGrammar.CommandType.PLAN_RETIRED, bare.type());

        SlashCommandGrammar.ParsedCommand withPayload = SlashCommandGrammar.parse("/plan 创建一个 demo 项目");
        assertEquals(SlashCommandGrammar.CommandType.PLAN_RETIRED, withPayload.type());
    }



    @Test
    void parsesConcreteModelNameAsSwitchModelPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/model step-custom-model");

        assertEquals(SlashCommandGrammar.CommandType.SWITCH_MODEL, command.type());
        assertEquals("step-custom-model", command.payload());
    }



    @Test
    void parsesConfigProviderPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse(
                "/config provider freellmapi --base-url http://localhost:5173/v1 --model auto");

        assertEquals(SlashCommandGrammar.CommandType.CONFIG, command.type());
        assertEquals("provider freellmapi --base-url http://localhost:5173/v1 --model auto", command.payload());
    }





    @Test
    void parsesClearSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/clear");

        assertEquals(SlashCommandGrammar.CommandType.CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesHistoryClearSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/history clear");

        assertEquals(SlashCommandGrammar.CommandType.HISTORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesExitSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/exit");

        assertEquals(SlashCommandGrammar.CommandType.EXIT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemorySlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/memory");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemoryClearSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/memory clear");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemoryListSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/memory list");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_LIST, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemorySearchSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/memory search Chrome 登录态");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_SEARCH, command.type());
        assertEquals("Chrome 登录态", command.payload());
    }

    @Test
    void parsesMemoryDeleteSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/memory delete fact-abcd1234");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_DELETE, command.type());
        assertEquals("fact-abcd1234", command.payload());
    }

    @Test
    void parsesSaveSlashCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/save 记住这个事实");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_SAVE, command.type());
        assertEquals("记住这个事实", command.payload());
    }

    @Test
    void parsesSaveWithoutPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/save");

        assertEquals(SlashCommandGrammar.CommandType.MEMORY_SAVE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesSearchWithoutPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/search");

        assertEquals(SlashCommandGrammar.CommandType.SEARCH_CODE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesGraphWithoutPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/graph");

        assertEquals(SlashCommandGrammar.CommandType.GRAPH_QUERY, command.type());
        assertNull(command.payload());
    }

    @Test
    void keepsNormalInputAsNone() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("帮我读取 pom.xml");

        assertEquals(SlashCommandGrammar.CommandType.NONE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesUnknownSlashCommandAsUnknownCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/unknown");

        assertEquals(SlashCommandGrammar.CommandType.UNKNOWN_COMMAND, command.type());
        assertEquals("/unknown", command.payload());
    }

    @Test
    void teamSlashCommandIsRetiredAndUnrecognized() {
        // /team（确定性 Multi-Agent 编排）已退役（ADR 0004）：CLI 层不再识别，视为未知命令。
        assertEquals(SlashCommandGrammar.CommandType.UNKNOWN_COMMAND,
                SlashCommandGrammar.parse("/team").type());
        assertEquals(SlashCommandGrammar.CommandType.UNKNOWN_COMMAND,
                SlashCommandGrammar.parse("/team 创建并验证一个 Java 项目").type());
    }

    @Test
    void parsesHitlOnCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/hitl on");

        assertEquals(SlashCommandGrammar.CommandType.SWITCH_HITL, command.type());
        assertEquals("on", command.payload());
    }

    @Test
    void parsesHitlOffCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/hitl off");

        assertEquals(SlashCommandGrammar.CommandType.SWITCH_HITL, command.type());
        assertEquals("off", command.payload());
    }

    @Test
    void parsesHitlStatusCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/hitl");

        assertEquals(SlashCommandGrammar.CommandType.SWITCH_HITL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesPolicyStatusCommand() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/policy");

        assertEquals(SlashCommandGrammar.CommandType.POLICY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithoutPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/audit");

        assertEquals(SlashCommandGrammar.CommandType.AUDIT_TAIL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithPayload() {
        SlashCommandGrammar.ParsedCommand command = SlashCommandGrammar.parse("/audit 20");

        assertEquals(SlashCommandGrammar.CommandType.AUDIT_TAIL, command.type());
        assertEquals("20", command.payload());
    }

    @Test
    void parsesSnapshotCommands() {
        assertEquals(SlashCommandGrammar.CommandType.SNAPSHOT, SlashCommandGrammar.parse("/snapshot").type());
        assertEquals("list", SlashCommandGrammar.parse("/snapshot").payload());
        assertEquals(SlashCommandGrammar.CommandType.SNAPSHOT, SlashCommandGrammar.parse("/snapshot status").type());
        assertEquals("status", SlashCommandGrammar.parse("/snapshot status").payload());
        assertEquals(SlashCommandGrammar.CommandType.RESTORE_SNAPSHOT, SlashCommandGrammar.parse("/restore 2").type());
        assertEquals("2", SlashCommandGrammar.parse("/restore 2").payload());
    }

    @Test
    void parsesHooksCommand() {
        assertEquals(SlashCommandGrammar.CommandType.HOOKS, SlashCommandGrammar.parse("/hooks").type());
        assertEquals(SlashCommandGrammar.CommandType.HOOKS, SlashCommandGrammar.parse("/hooks list").type());
        assertEquals("list", SlashCommandGrammar.parse("/hooks").payload());
        // 未知子命令走 CLI 层未知命令，不回退给 Agent
        assertEquals(SlashCommandGrammar.CommandType.UNKNOWN_COMMAND, SlashCommandGrammar.parse("/hooks bogus").type());
    }

    @Test
    void parsesMcpCommands() {
        assertEquals(SlashCommandGrammar.CommandType.MCP_LIST, SlashCommandGrammar.parse("/mcp").type());
        assertEquals(SlashCommandGrammar.CommandType.MCP_RESTART, SlashCommandGrammar.parse("/mcp restart filesystem").type());
        assertEquals("filesystem", SlashCommandGrammar.parse("/mcp restart filesystem").payload());
        assertEquals(SlashCommandGrammar.CommandType.MCP_LOGS, SlashCommandGrammar.parse("/mcp logs filesystem").type());
        assertEquals(SlashCommandGrammar.CommandType.MCP_DISABLE, SlashCommandGrammar.parse("/mcp disable filesystem").type());
        assertEquals(SlashCommandGrammar.CommandType.MCP_ENABLE, SlashCommandGrammar.parse("/mcp enable filesystem").type());
        assertEquals(SlashCommandGrammar.CommandType.MCP_RESOURCES, SlashCommandGrammar.parse("/mcp resources filesystem").type());
        assertEquals("filesystem", SlashCommandGrammar.parse("/mcp resources filesystem").payload());
        assertEquals(SlashCommandGrammar.CommandType.MCP_PROMPTS, SlashCommandGrammar.parse("/mcp prompts filesystem").type());
        assertEquals("filesystem", SlashCommandGrammar.parse("/mcp prompts filesystem").payload());
    }

    @Test
    void parsesBrowserCommands() {
        assertEquals(SlashCommandGrammar.CommandType.BROWSER, SlashCommandGrammar.parse("/browser").type());
        assertEquals("status", SlashCommandGrammar.parse("/browser").payload());
        assertEquals(SlashCommandGrammar.CommandType.BROWSER, SlashCommandGrammar.parse("/browser status").type());
        assertEquals("status", SlashCommandGrammar.parse("/browser status").payload());
        assertEquals("connect", SlashCommandGrammar.parse("/browser connect").payload());
        assertEquals("connect 9333", SlashCommandGrammar.parse("/browser connect 9333").payload());
        assertEquals("disconnect", SlashCommandGrammar.parse("/browser disconnect").payload());
        assertEquals("tabs", SlashCommandGrammar.parse("/browser tabs").payload());
    }

    @Test
    void parsesTaskCommands() {
        assertEquals(SlashCommandGrammar.CommandType.TASK, SlashCommandGrammar.parse("/task").type());
        assertEquals("list", SlashCommandGrammar.parse("/task").payload());
        assertEquals("add 重构模块", SlashCommandGrammar.parse("/task add 重构模块").payload());
        assertEquals("cancel task_123", SlashCommandGrammar.parse("/task cancel task_123").payload());
        assertEquals("log task_123", SlashCommandGrammar.parse("/task log task_123").payload());
    }

    @Test
    void parsesCancelCommand() {
        assertEquals(SlashCommandGrammar.CommandType.CANCEL, SlashCommandGrammar.parse("/cancel").type());
        assertEquals(SlashCommandGrammar.CommandType.CANCEL, SlashCommandGrammar.parse("cancel").type());
    }

    @Test
    void parsesSkillListCommand() {
        assertEquals(SlashCommandGrammar.CommandType.SKILL_LIST, SlashCommandGrammar.parse("/skill").type());
        assertEquals(SlashCommandGrammar.CommandType.SKILL_LIST, SlashCommandGrammar.parse("/skill list").type());
    }

    @Test
    void parsesSkillReloadCommand() {
        assertEquals(SlashCommandGrammar.CommandType.SKILL_RELOAD, SlashCommandGrammar.parse("/skill reload").type());
    }

    @Test
    void parsesSkillShowCommand() {
        SlashCommandGrammar.ParsedCommand cmd = SlashCommandGrammar.parse("/skill show web-access");
        assertEquals(SlashCommandGrammar.CommandType.SKILL_SHOW, cmd.type());
        assertEquals("web-access", cmd.payload());
    }

    @Test
    void parsesSkillOnOffCommands() {
        SlashCommandGrammar.ParsedCommand on = SlashCommandGrammar.parse("/skill on web-access");
        assertEquals(SlashCommandGrammar.CommandType.SKILL_ON, on.type());
        assertEquals("web-access", on.payload());

        SlashCommandGrammar.ParsedCommand off = SlashCommandGrammar.parse("/skill off verbose-debug");
        assertEquals(SlashCommandGrammar.CommandType.SKILL_OFF, off.type());
        assertEquals("verbose-debug", off.payload());
    }
}
