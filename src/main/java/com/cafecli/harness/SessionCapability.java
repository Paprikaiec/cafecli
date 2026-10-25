package com.cafecli.harness;

/** Session built-ins use the same command registration as product capabilities. */
public final class SessionCapability implements Capability {
    private final java.util.function.Supplier<String> memoryStatus;
    public SessionCapability() { this(() -> ""); }
    public SessionCapability(java.util.function.Supplier<String> memoryStatus) { this.memoryStatus=memoryStatus; }
    @Override public void attachSession(SessionRegistrar session) {
        session.command(new SessionCommand("/clear", "清空当前对话", (context, arguments) -> {
            context.clear();
            return "🗑️ 当前对话历史已清空，长期记忆保持不变\n\n";
        }).withPresentation((context,args) -> AgentSession.CommandPresentation.effect(AgentSession.CommandEffect.CLEAR_VIEW)));
        for (String name : java.util.List.of("/exit","/quit"))
            session.command(SessionCommand.control(name,"退出 Cafecli",(context,args) -> "\n👋 再见!\n")
                    .withPresentation((context,args) -> AgentSession.CommandPresentation.effect(AgentSession.CommandEffect.EXIT)));
        session.command(SessionCommand.control("/cancel","查看取消命令状态",(context,args) -> "当前没有正在运行的任务。\n\n"));
        session.command(SessionCommand.control("/transcript","浏览会话转写",(context,args) -> "")
                .withPresentation((context,args) -> AgentSession.CommandPresentation.effect(AgentSession.CommandEffect.TRANSCRIPT)));
        session.command(SessionCommand.control("/history","清空本机输入历史",(context,args) -> "🧹 输入历史已清空\n\n")
                .withPresentation((context,args) -> AgentSession.CommandPresentation.effect(AgentSession.CommandEffect.HISTORY_CLEAR)));
        session.command(SessionCommand.control("/plan","Plan-and-Execute 退役说明",(context,args) -> com.cafecli.input.CommandNotices.planTombstone()+"\n"));
        session.command(SessionCommand.control("/help","查看可用命令",(context,args) -> context.commandHelp()));
        for (String name : java.util.List.of("/context","/ctx"))
            session.command(SessionCommand.control(name,"查看上下文状态",(context,args) ->
                    "📋 上下文状态：\n" + context.describeContext() + "\n" + memoryStatus.get() + "\n\n"));
    }
}
