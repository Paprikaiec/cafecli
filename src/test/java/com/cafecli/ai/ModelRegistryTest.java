package com.cafecli.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {
    @Test
    void bundledDefaultsMatchTheExistingClientsAndConfiguredRecordsOverrideOrAddModels() {
        var defaults = new ModelRegistry();
        assertEquals(List.of("glm-5.1", "deepseek-v4-flash", "step-3.5-flash", "kimi-k2.6", "auto"),
                List.of("glm", "deepseek", "step", "kimi", "freellmapi").stream()
                        .map(p -> defaults.defaultModel(p).id()).toList());
        assertEquals(List.of(200_000, 1_000_000, 256_000, 256_000, 128_000),
                List.of("glm", "deepseek", "step", "kimi", "freellmapi").stream()
                        .map(p -> defaults.defaultModel(p).contextWindow()).toList());
        assertEquals(8192, defaults.defaultModel("deepseek").maxOutputTokens());
        assertTrue(defaults.defaultModel("deepseek").supports(Model.Feature.PROMPT_CACHING));
        assertFalse(defaults.defaultModel("freellmapi").supports(Model.Feature.PROMPT_CACHING));
        Model override = new Model("deepseek-v4-flash", "deepseek", "openai-completions", 42_000, 1024,
                Set.of(Model.Feature.REASONING));
        Model added = new Model("private-model", "deepseek", "openai-completions", 64_000, 2048, Set.of());
        var registry = new ModelRegistry(List.of(override, added));
        assertEquals(override, registry.defaultModel("deepseek"));
        assertEquals(added, registry.resolve("deepseek", "private-model"));
        assertEquals(42_000, registry.resolve("deepseek", "unlisted-model").contextWindow());
        assertEquals("unlisted-model", registry.resolve("deepseek", "unlisted-model").id());
        assertEquals(1_000_000, new ModelRegistry().defaultModel("deepseek").contextWindow());
    }
}
