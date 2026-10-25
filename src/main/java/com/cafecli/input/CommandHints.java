package com.cafecli.input;

import java.util.List;

/** Existing CLI command descriptions shared by both entry points. */
public final class CommandHints {
    private CommandHints() {}
    public record Hint(String insertText, String display, String description) {}
    public static List<Hint> all() {
        return List.of(
                new Hint("/model", "/model", "查看当前模型"),
                new Hint("/model glm-5.1", "/model glm-5.1", "切换到 GLM-5.1"),
                new Hint("/model glm-5v-turbo", "/model glm-5v-turbo", "切换到 GLM-5V-Turbo 多模态"),
                new Hint("/model deepseek", "/model deepseek", "切换到 DeepSeek（读取配置模型）"),
                new Hint("/model step", "/model step", "切换到 StepFun（读取配置模型）"),
                new Hint("/model kimi", "/model kimi", "切换到 Kimi（读取配置模型）"),
                new Hint("/model freellmapi", "/model freellmapi", "切换到本地 FreeLLMAPI（读取配置模型）"),
                new Hint("/config provider freellmapi ", "/config provider freellmapi <选项>", "配置本地 FreeLLMAPI provider"),
                new Hint("/hitl", "/hitl", "查看 HITL 状态"),
                new Hint("/hitl on", "/hitl on", "启用危险操作人工审批"),
                new Hint("/hitl off", "/hitl off", "关闭 HITL 审批"),
                new Hint("/browser", "/browser", "查看浏览器会话状态"),
                new Hint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new Hint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new Hint("/browser status", "/browser status", "查看浏览器会话状态"),
                new Hint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new Hint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new Hint("/task", "/task", "查看会话内任务及状态"),
                new Hint("/task cancel ", "/task cancel <task_id>", "取消运行中的任务"),
                new Hint("/transcript", "/transcript", "浏览最近 5 个回合并切换折叠块"),
                new Hint("/hooks", "/hooks", "查看已加载的生命周期 Hook（来源/事件/matcher/开关/计数）"),
                new Hint("/mcp", "/mcp", "查看 MCP server 状态"),
                new Hint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new Hint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new Hint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new Hint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new Hint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new Hint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new Hint("/policy", "/policy", "查看安全策略状态"),
                new Hint("/config", "/config", "打开配置 palette（只读视图 + 切换提示）"),
                new Hint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new Hint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new Hint("/snapshot", "/snapshot", "查看最近 Side-Git 快照"),
                new Hint("/snapshot status", "/snapshot status", "查看 Side-Git 快照状态"),
                new Hint("/snapshot clean", "/snapshot clean", "清理当前项目 Side-Git 快照"),
                new Hint("/restore ", "/restore <N>", "恢复到最近第 N 个 pre-turn 快照"),
                new Hint("/index", "/index", "索引当前代码库"),
                new Hint("/index ", "/index [路径]", "索引指定路径代码库"),
                new Hint("/search ", "/search <查询>", "语义检索代码（RAG 辅助）"),
                new Hint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new Hint("/clear", "/clear", "清空当前对话历史"),
                new Hint("/history clear", "/history clear", "清空本机输入历史"),
                new Hint("/context", "/context", "查看上下文和记忆状态"),
                new Hint("/memory", "/memory", "查看记忆状态"),
                new Hint("/memory list", "/memory list", "查看长期记忆列表"),
                new Hint("/memory search ", "/memory search <关键词>", "搜索当前项目可见长期记忆"),
                new Hint("/memory delete ", "/memory delete <id>", "删除单条长期记忆"),
                new Hint("/memory clear", "/memory clear", "清空长期记忆"),
                new Hint("/save ", "/save [--global] <事实内容>", "手动保存项目级或全局长期记忆"),
                new Hint("/skill", "/skill", "查看 skill 列表"),
                new Hint("/skill list", "/skill list", "查看 skill 列表"),
                new Hint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new Hint("/skill on ", "/skill on <name>", "启用 skill"),
                new Hint("/skill off ", "/skill off <name>", "禁用 skill"),
                new Hint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new Hint("/exit", "/exit", "退出 Cafecli"),
                new Hint("/quit", "/quit", "退出 Cafecli")
        );
    }


    public static List<Hint> forCommand(String name, String description) {
        var hints = all().stream().filter(hint -> hint.insertText().equals(name)
                || hint.insertText().startsWith(name + " ")).toList();
        return hints.isEmpty() ? List.of(new Hint(name,name,description)) : hints;
    }
}
