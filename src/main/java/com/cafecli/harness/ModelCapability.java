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

/** Session model selection changes data only; routing adapters are created once by composition. */
public final class ModelCapability implements Capability {
    private final ProviderSelection config;
    private final ModelRegistry registry;
    private final Map<String, String> selectedModels = new HashMap<>();
    private final Consumer<String> warning;

    public ModelCapability(ProviderSelection config, Consumer<String> warning) {
        this.config = config;
        this.registry = new ModelRegistry(config.modelOverrides());
        this.warning = warning;
        config.providers().forEach((name, settings) -> {
            if (settings.model() != null && !settings.model().isBlank()) selectedModels.put(name, settings.model());
        });
        if (config.providers().isEmpty() && config.model() != null && !config.model().isBlank())
            selectedModels.put(config.name(), config.model());
    }

    @Override public void attachSession(SessionRegistrar session) {
        session.command(new SessionCommand("/model", "查看或切换模型", this::select));
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
        ProviderSettings settings = config.providers().get(target.provider());
        boolean available = settings != null ? settings.hasKey()
                : target.provider().equals(config.name()) && config.apiKey() != null && !config.apiKey().isBlank();
        if (!available || !java.util.Set.of("glm", "deepseek", "step", "kimi", "freellmapi").contains(target.provider()))
            return "❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n\n";
        Model model = registry.resolve(target.provider(), selectedModels.get(target.provider()));
        session.switchModel(model);
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
