package com.cafecli.harness.skills;

import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionCommand;
import com.cafecli.harness.SessionRegistrar;
import com.cafecli.harness.config.SkillsConfig;
import com.cafecli.harness.workspace.LeafToolAdapter;
import com.cafecli.prompt.PromptContext;
import com.cafecli.skill.Skill;
import com.cafecli.skill.SkillBuiltinExtractor;
import com.cafecli.skill.SkillCommands;
import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.skill.SkillIndexFormatter;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.skill.SkillStateStore;
import com.cafecli.tool.SkillTools;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Shared skill discovery; each attached agent owns its own delayed one-shot body buffer. */
public final class SkillsCapability implements Capability {
    private final SkillRegistry registry;
    private final List<AgentIndex> indices = new CopyOnWriteArrayList<>();

    private static final class AgentIndex {
        private final Runnable invalidate;
        private volatile List<Skill> indexed = List.of();
        AgentIndex(Runnable invalidate) { this.invalidate = invalidate; }
        String render(List<Skill> enabled) {
            String text = SkillIndexFormatter.format(enabled);
            indexed = List.copyOf(enabled);
            return text;
        }
        void changed(List<Skill> enabled) {
            if (!indexed.equals(enabled)) invalidate.run();
        }
    }

    public SkillsCapability(SkillsConfig config) { this(config, ignored -> {}); }

    public SkillsCapability(SkillsConfig config, Consumer<String> status) {
        try { new SkillBuiltinExtractor(config.builtinCache()).extractAll(); }
        catch (Exception e) { status.accept("内置 skill 解压失败: " + e.getMessage()); }
        registry = new SkillRegistry(config.builtinCache(), config.userSkills(), config.projectSkills(),
                new SkillStateStore(config.stateFile()));
        registry.reload();
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.command(new SessionCommand("/skill", "管理 Skills", (context, args) -> command(args)));
    }

    private String command(String arguments) {
        String[] parts = arguments.trim().split("\\s+", 2);
        String name = parts.length > 1 ? parts[1].trim() : "";
        String subcommand = parts[0].toLowerCase(Locale.ROOT);
        boolean hasName = !name.isEmpty();
        if (((subcommand.equals("list") || subcommand.equals("reload")) && hasName)
                || ((subcommand.equals("show") || subcommand.equals("on") || subcommand.equals("off")) && !hasName)) {
            return "未知命令: /skill " + arguments.trim();
        }
        String result = switch (subcommand) {
            case "", "list" -> SkillCommands.list(registry);
            case "show" -> SkillCommands.show(registry, name);
            case "on" -> SkillCommands.enable(registry, registry.stateStore(), name);
            case "off" -> SkillCommands.disable(registry, registry.stateStore(), name);
            case "reload" -> {
                registry.reload();
                yield "🔄 已重新扫描 skill 目录\n" + SkillCommands.startupSummary(registry) + "\n✅ 下一轮 LLM 调用生效";
            }
            default -> "未知命令: /skill " + arguments.trim();
        };
        var enabled = registry.enabledSkills();
        indices.forEach(index -> index.changed(enabled));
        return result;
    }

    @Override public void attachAgent(AgentScope agent) {
        var index = new AgentIndex(agent.systemPromptInvalidator());
        indices.add(index);
        agent.onClose(() -> indices.remove(index));
        agent.promptContext(context -> new PromptContext(context.approvalMode(), context.memoryContext(),
                context.externalContext(), index.render(registry.enabledSkills()), context.variables()));
        SkillContextBuffer buffer = new SkillContextBuffer();
        agent.messageKind(SkillBody.KIND);
        agent.tool(new LeafToolAdapter(SkillTools.loadSkill(registry, buffer)));
        agent.onClose(buffer::clear);
        agent.runStartWriter((input, cancellation) -> {
            String bodies = buffer.drain();
            return bodies.isEmpty() ? List.of() : List.of(new SkillBody(bodies + "\n用户输入：\n"));
        });
    }
}
