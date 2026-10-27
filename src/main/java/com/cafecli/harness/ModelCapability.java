package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.ModelRegistry;
import com.cafecli.harness.config.ProviderSelection;
import com.cafecli.harness.config.ProviderSettings;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Model selection retains history; explicit configuration updates install append-only transport generations. */
public final class ModelCapability implements Capability {
    private final ProviderSelection config;
    private final ModelRegistry registry;
    private final Map<String, String> selectedModels = new HashMap<>();
    private final Consumer<String> warning;
    private final Map<String,ProviderSettings> providers=new HashMap<>();
    private final Map<String,String> routes=new HashMap<>();
    private final java.util.Set<String> changed=new java.util.HashSet<>();
    private final java.util.function.BiFunction<String,ProviderSettings,String> installTransport;
    private final java.util.function.IntSupplier enabledSkills;
    private final java.util.function.Supplier<String> renderer;
    private volatile String defaultProvider;

    public ModelCapability(ProviderSelection config, Consumer<String> warning) {
        this(config,warning,(provider,settings) -> provider,() -> 0,() -> "(none)");
    }
    public ModelCapability(ProviderSelection config,Consumer<String> warning,
            java.util.function.BiFunction<String,ProviderSettings,String> installTransport,
            java.util.function.IntSupplier enabledSkills,java.util.function.Supplier<String> renderer) {
        this.config = config;
        this.registry = new ModelRegistry(config.modelOverrides());
        this.warning = warning;
        this.installTransport=installTransport; this.enabledSkills=enabledSkills; this.renderer=renderer;
        providers.putAll(config.providers());
        this.defaultProvider=config.preferences().map(com.cafecli.harness.config.ModelPreferences::defaultProvider).orElse(config.name());
        config.providers().forEach((name, settings) -> {
            if (settings.model() != null && !settings.model().isBlank()) selectedModels.put(name, settings.model());
        });
        if (config.providers().isEmpty() && config.model() != null && !config.model().isBlank())
            selectedModels.put(config.name(), config.model());
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.command(new SessionCommand("/model", "查看或切换模型", this::select));
        session.command(new SessionCommand("/config","查看或修改配置",(context,args) -> configure(args))
                .withPresentation((context,args) -> args.isBlank() ? new AgentSession.CommandPresentation(
                        AgentSession.CommandEffect.PALETTE,"配置 / config",java.util.List.of(
                            "模型: " + context.model().id() + " / " + context.model().provider(),
                            "默认 Provider: " + defaultProvider,
                            "HITL: " + (context.approvalEnabled() ? "ON" : "OFF"),
                            "Skill 启用数: " + enabledSkills.getAsInt(),
                            "渲染器: " + renderer.get(),
                            "配置文件: ~/.cafecli/config.json (只读视图，编辑请用编辑器)"),java.util.List.of(
                            "💡 GLM: /model glm-5.1 / /model glm-5v-turbo；其它: /model deepseek|step|kimi|freellmapi 读取配置模型",
                            "💡 GLM: /model glm-5.1 / /model glm-5v-turbo；其它: /model deepseek|step|kimi|freellmapi 读取配置模型",
                            "💡 切换 HITL: /hitl on / /hitl off",
                            "💡 管理 Skill: /skill list / /skill on <name> / /skill off <name>",
                            "💡 切换渲染器（重启后生效）: CAFECLI_RENDERER=inline|plain",
                            "💡 编辑 config.json 请在编辑器里改完重启")) : AgentSession.CommandPresentation.normal()));
    }

    private String select(AgentSession session, String arguments) {
        if (arguments.isBlank()) return "🤖 当前模型: " + session.model().id() + " (" + session.model().provider() + ")\n"
                + "   GLM 明确模型：\n"
                + "   /model glm-5.1       - 切换到 GLM-5.1\n"
                + "   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态\n"
                + "   其它 provider 使用你配置里的具体模型：\n"
                + "   /model deepseek      - 切换到 DeepSeek（读取配置模型）\n"
                + "   /model step          - 切换到 StepFun（读取配置模型）\n"
                + "   /model kimi          - 切换到 Kimi（读取配置模型）\n"
                + "   /model freellmapi    - 切换到本地 FreeLLMAPI（读取配置模型）\n\n";
        Selection target = resolve(arguments);
        // v1 retains an explicit choice in memory even when its missing key prevents the switch.
        if (target.model() != null) {
            selectedModels.put(target.provider(), target.model());
            config.preferences().ifPresent(preferences -> preferences.explicitModel(target.provider(), target.model()));
        }
        ProviderSettings settings = providers.get(target.provider());
        boolean available = settings != null ? settings.hasKey()
                : target.provider().equals(config.name()) && config.apiKey() != null && !config.apiKey().isBlank();
        if (!available || !java.util.Set.of("glm", "deepseek", "step", "kimi", "freellmapi").contains(target.provider()))
            return "❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n\n";
        Model model = registry.resolve(target.provider(), selectedModels.get(target.provider()));
        if (changed.contains(target.provider())) {
            String route=installTransport.apply(target.provider(),settings);
            routes.put(target.provider(),route);
            changed.remove(target.provider());
        }
        String route=routes.get(target.provider());
        if (route!=null) model=new Model(model.id(),model.provider(),model.api(),model.contextWindow(),model.maxOutputTokens(),model.features(),route);
        session.switchModel(model);
        defaultProvider=target.provider();
        config.preferences().ifPresent(preferences -> {
            try { preferences.save(target.provider()); }
            catch (IOException e) { warning.accept("⚠️ 配置保存失败: " + e.getMessage()); }
        });
        var profile = com.cafecli.context.ContextProfile.forExactWindow(model.contextWindow(),
                model.supports(Model.Feature.PROMPT_CACHING), cacheMode(model));
        return "✅ 已切换到: " + model.id() + " (" + model.provider() + ")\n"
                + "   上下文策略: " + profile.summaryWithoutShortTermMemory()
                + "\n   对话上下文已保留，使用 /clear 可清空\n\n";
    }

    private String configure(String arguments) {
        var update=com.cafecli.config.ProviderConfigCommand.parse(arguments);
        if (update.error()!=null) return "❌ " + update.error() + "\n" + com.cafecli.config.ProviderConfigCommand.usage() + "\n";
        var before=providers.getOrDefault(update.provider(),new ProviderSettings(null,null,null));
        var raw=config.preferences().map(preferences -> preferences.raw(update.provider())).orElse(before);
        ProviderSettings effective;
        if (config.preferences().isPresent()) {
            var preferences=config.preferences().orElseThrow();
            preferences.update(update);
            raw=preferences.raw(update.provider()); effective=preferences.effective(update.provider());
            try { preferences.saveCurrent(); }
            catch (IOException failure) { warning.accept("⚠️ 配置保存失败: " + failure.getMessage()); }
        } else {
            raw=new ProviderSettings(update.apiKey()==null ? raw.apiKey() : update.apiKey(),
                    update.model()==null ? raw.model() : update.model(),update.baseUrl()==null ? raw.baseUrl() : update.baseUrl());
            effective=raw;
        }
        providers.put(update.provider(),effective);
        if (!before.equals(effective)) changed.add(update.provider());
        if (update.model()!=null) {
            if (effective.model()==null || effective.model().isBlank()) selectedModels.remove(update.provider());
            else selectedModels.put(update.provider(),effective.model());
        }
        if (update.setDefault()) defaultProvider=update.provider();
        return "✅ 已保存 provider 配置: " + update.provider() + "\n   model: "
                + (raw.model()==null || raw.model().isBlank() ? "(默认)" : raw.model())
                + "\n   baseUrl: " + (raw.baseUrl()==null || raw.baseUrl().isBlank() ? "(默认)" : raw.baseUrl())
                + "\n   apiKey: " + com.cafecli.config.ProviderConfigCommand.maskSecret(raw.apiKey()) + "\n"
                + (update.setDefault() ? "   默认 provider 已设为 " + update.provider() + "\n" : "")
                + "   立即切换: /model " + update.provider() + "\n";
    }

    private static String cacheMode(Model model) {
        if (!model.supports(Model.Feature.PROMPT_CACHING)) return "none";
        return switch (model.provider()) {
            case "glm" -> "glm-prompt-cache";
            case "deepseek" -> "automatic-prefix-cache";
            case "step" -> "step-prefix-cache";
            case "kimi" -> "moonshot-context-cache";
            default -> "none";
        };
    }

    private record Selection(String provider, String model) { }
    private static Selection resolve(String input) {
        String value = input.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm" -> new Selection("glm", "glm-5.1");
            case "deepseek" -> new Selection("deepseek", null);
            case "step", "stepfun", "step-fun" -> new Selection("step", null);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new Selection("kimi", null);
            case "freellmapi", "free-llm-api", "free_llm_api", "freellm", "free-llm" -> new Selection("freellmapi", null);
            default -> {
                if (normalized.startsWith("glm-")) yield new Selection("glm", value);
                if (normalized.startsWith("deepseek")) yield new Selection("deepseek", value);
                if (normalized.startsWith("step")) yield new Selection("step", value);
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) yield new Selection("kimi", value);
                yield new Selection(normalized, null);
            }
        };
    }
}
