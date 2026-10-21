package com.cafecli.harness;

/** Session built-ins use the same command registration as product capabilities. */
public final class SessionCapability implements Capability {
    @Override public void attachSession(SessionRegistrar session) {
        session.command(new SessionCommand("/clear", "清空当前对话", (context, arguments) -> {
            context.clear();
            return "🗑️ 当前对话历史已清空，长期记忆保持不变\n\n";
        }));
    }
}
