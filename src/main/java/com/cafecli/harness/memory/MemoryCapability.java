package com.cafecli.harness.memory;

import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionCommand;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.config.MemoryConfig;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.memory.ExplicitMemoryHints;
import com.cafecli.memory.LongTermStore;
import com.cafecli.tool.MemoryTools;

import java.util.List;
import java.util.stream.Collectors;

/** Explicit long-term memory and a run-start recall writer; never extracts ordinary conversation facts. */
public final class MemoryCapability implements Capability {
    private volatile LongTermStore store;
    public MemoryCapability(MemoryConfig config) {
        store = new LongTermStore(config.directory(), config.projectKey());
    }
    public String status() { return store.status(); }

    @Override public void attachSession(SessionRegistrar session) {
        session.onProjectChange(project -> store=store.forProject(MemoryConfig.forProject(java.nio.file.Path.of("."),project).projectKey()));
        session.command(new SessionCommand("/save", "保存长期记忆", (context, args) -> new MemoryCommands(store,store.project()).save(args)));
        session.command(new SessionCommand("/memory", "管理长期记忆", (context, args) -> new MemoryCommands(store,store.project()).memory(args)));
        session.command(new SessionCommand("/mem", "管理长期记忆", (context, args) -> new MemoryCommands(store,store.project()).memory(args)));
    }
    @Override public void attachAgent(AgentScope agent) {
        LongTermStore captured=store;
        java.util.function.Supplier<LongTermStore> current=com.cafecli.harness.AgentSession.MAIN_AGENT_ID.equals(agent.agentId())
                ? () -> store : () -> captured;
        agent.messageKind(MemoryRecall.KIND);
        agent.tool(new LeafToolAdapter(MemoryTools.saveMemory((fact,scope) -> current.get().save(fact,scope))));
        agent.runStartWriter((input, cancellation) -> {
            LongTermStore store=current.get();
            String query = text(input.content());
            List<String> recent = agent.messages().stream().map(message -> switch (message) {
                case UserMessage user -> text(user.content());
                case AssistantMessage assistant -> assistant.text();
                case ToolResultMessage tool -> text(tool.content());
            }).filter(value -> value != null && !value.isBlank()).toList();
            String fact = ExplicitMemoryHints.browserLoginFact(query, recent);
            if (fact != null && !fact.isBlank()) store.save(fact, "global");
            String recall = store.recall(query, Math.max(500, Math.min(5000, agent.model().contextWindow() / 200)));
            return recall.isEmpty() ? List.of() : List.of(new MemoryRecall(recall));
        });
    }
    private static String text(List<UserContent> content) {
        return content.stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::text).collect(Collectors.joining("\n"));
    }
}
