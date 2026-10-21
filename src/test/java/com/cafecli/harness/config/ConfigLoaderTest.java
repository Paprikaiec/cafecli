package com.cafecli.harness.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The v2 config loader against plain maps and temporary directories — never the JVM's own properties, environment or
 * home. Each expected value is v1's behavior for the same inputs, read from where v1 reads it: the hard cap from
 * {@code agent/AgentBudget} (system properties only, bridged by {@code ConfigHome.installPropertyAliases}), the
 * provider from {@code config/CafeCliConfig} ({@code defaultProvider} in the user {@code config.json}, default
 * {@code glm}), the config homes from {@code ConfigHome.configDirUnder}, the project root from the working directory.
 */
class ConfigLoaderTest {

    @TempDir
    Path root;

    private Path home;
    private Path project;

    @BeforeEach
    void dirs() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        project = Files.createDirectories(root.resolve("project"));
    }

    @Test
    void withNothingConfiguredTheDefaultsAreV1s() {
        HarnessConfig config = load(Map.of(), Map.of());

        assertEquals(new ProviderSelection("glm", Optional.empty()), config.provider());
        assertEquals(50, config.maxIterations());
        assertEquals(project, config.projectRoot());
        assertEquals(new ConfigHomes(home.resolve(".cafecli"), project.resolve(".cafecli")), config.homes());
        assertEquals(List.of(), config.warnings());
    }

    @Test
    void theHardCapIsTheReactHardMaxIterationsPropertyUnderEitherPrefixWithTheCafecliOneWinning() {
        assertEquals(7, load(Map.of("cafecli.react.hard.max.iterations", "7"), Map.of()).maxIterations());
        assertEquals(8, load(Map.of("paicli.react.hard.max.iterations", "8"), Map.of()).maxIterations());
        assertEquals(7, load(Map.of("cafecli.react.hard.max.iterations", "7",
                "paicli.react.hard.max.iterations", "8"), Map.of()).maxIterations());
    }

    @Test
    void aBlankUnparsableOrNonPositiveCapFallsBackTo50() {
        for (String raw : new String[]{"", "  ", "many", "0", "-3", "2.5"}) {
            assertEquals(50, load(Map.of("cafecli.react.hard.max.iterations", raw), Map.of()).maxIterations(),
                    "raw value \"" + raw + "\"");
        }
        assertEquals(12, load(Map.of("cafecli.react.hard.max.iterations", " 12 "), Map.of()).maxIterations());
    }

    @Test
    void aBlankCafecliCapStillShadowsThePaicliOneAsV1sPropertyBridgeDoes() {
        // installPropertyAliases copies every cafecli.* value over its paicli.* twin, blank or not.
        assertEquals(50, load(Map.of("cafecli.react.hard.max.iterations", "",
                "paicli.react.hard.max.iterations", "8"), Map.of()).maxIterations());
    }

    @Test
    void theCapHasNoEnvironmentVariable() {
        assertEquals(50, load(Map.of(), Map.of("CAFECLI_REACT_HARD_MAX_ITERATIONS", "7",
                "PAICLI_REACT_HARD_MAX_ITERATIONS", "7")).maxIterations());
    }

    @Test
    void theProviderIsDefaultProviderInTheUserConfigJson() throws IOException {
        userConfig(".cafecli", "{\"defaultProvider\": \" DeepSeek \", \"providers\": {}}");

        assertEquals("deepseek", load(Map.of(), Map.of()).provider().name());
    }

    @Test
    void theUserConfigJsonIsReadFromTheLegacyHomeOnlyWhenTheNewOneDoesNotExist() throws IOException {
        userConfig(".paicli", "{\"defaultProvider\": \"kimi\"}");
        assertEquals("kimi", load(Map.of(), Map.of()).provider().name());
        assertEquals(home.resolve(".paicli"), load(Map.of(), Map.of()).homes().user());

        Files.createDirectories(home.resolve(".cafecli"));
        assertEquals("glm", load(Map.of(), Map.of()).provider().name());
    }

    @Test
    void theProjectConfigHomeFallsBackToTheLegacyOneLikeTheUserHome() throws IOException {
        Files.createDirectories(project.resolve(".paicli"));

        assertEquals(project.resolve(".paicli"), load(Map.of(), Map.of()).homes().project());
    }

    @Test
    void aConfigJsonWithoutADefaultProviderFallsBackToAProviderWithAKey() throws IOException {
        userConfig(".cafecli", "{\"providers\": {\"deepseek\": {\"apiKey\": \"x\"}}}");

        assertEquals("deepseek", load(Map.of(), Map.of()).provider().name());
    }

    @Test
    void anUnreadableConfigJsonIsAWarningAndTheDefaultsAreUsed() throws IOException {
        Path file = userConfig(".cafecli", "{\"defaultProvider\": ");

        HarnessConfig config = load(Map.of(), Map.of());

        assertEquals("glm", config.provider().name());
        assertEquals(1, config.warnings().size());
        assertEquals(file, config.warnings().get(0).file());
    }

    // ---- knobs v1 does not have, named and ordered like v1's snapshot / audit knobs: property > environment ----

    @Test
    void theProviderPropertyThenTheProviderVariableOverrideTheConfigJson() throws IOException {
        userConfig(".cafecli", "{\"defaultProvider\": \"deepseek\"}");

        assertEquals("fake", load(Map.of("cafecli.provider", "fake"), Map.of()).provider().name());
        assertEquals("fake", load(Map.of("paicli.provider", "fake"), Map.of()).provider().name());
        assertEquals("fake", load(Map.of(), Map.of("CAFECLI_PROVIDER", "fake")).provider().name());
        assertEquals("fake", load(Map.of(), Map.of("PAICLI_PROVIDER", "fake")).provider().name());
        assertEquals("kimi", load(Map.of("cafecli.provider", "kimi"),
                Map.of("CAFECLI_PROVIDER", "fake")).provider().name());
        assertEquals("deepseek", load(Map.of("cafecli.provider", " "), Map.of()).provider().name());
    }

    @Test
    void theFakeScriptIsAPropertyOrAVariableResolvedAgainstTheWorkingDirectory() {
        assertEquals(Optional.of(project.resolve("scripts/answer.json")),
                load(Map.of("cafecli.fake.script", "scripts/answer.json"), Map.of()).provider().fakeScript());
        assertEquals(Optional.of(root.resolve("elsewhere.json")),
                load(Map.of("paicli.fake.script", root.resolve("elsewhere.json").toString()), Map.of())
                        .provider().fakeScript());
        assertEquals(Optional.of(project.resolve("from-env.json")),
                load(Map.of(), Map.of("CAFECLI_FAKE_SCRIPT", "from-env.json")).provider().fakeScript());
        assertEquals(Optional.of(project.resolve("from-property.json")),
                load(Map.of("cafecli.fake.script", "from-property.json"),
                        Map.of("CAFECLI_FAKE_SCRIPT", "from-env.json")).provider().fakeScript());
    }

    @Test
    void providerConfigWinsOverEnvironmentThenProjectDotEnvThenHomeDotEnv() throws IOException {
        Files.writeString(home.resolve(".env"), "DEEPSEEK_API_KEY=home-key\nDEEPSEEK_MODEL=home-model\nDEEPSEEK_BASE_URL=home-url\n");
        Files.writeString(project.resolve(".env"), "# comment\nDEEPSEEK_API_KEY=project-key\nDEEPSEEK_MODEL=project-model\nDEEPSEEK_BASE_URL=project-url\n");
        var fromFile = load(Map.of("cafecli.provider", "deepseek"), Map.of()).provider();
        assertEquals("project-key", fromFile.apiKey());
        assertEquals("project-model", fromFile.model());
        assertEquals("project-url", fromFile.baseUrl());
        Files.delete(project.resolve(".env"));
        assertEquals("home-key", load(Map.of(), Map.of()).provider().apiKey());
        var env = Map.of("DEEPSEEK_API_KEY", " env-key ", "DEEPSEEK_MODEL", " env-model ", "DEEPSEEK_BASE_URL", " env-url ");
        var fromEnv = load(Map.of(), env).provider();
        assertEquals("deepseek", fromEnv.name());
        assertEquals("env-key", fromEnv.apiKey());
        assertEquals("env-model", fromEnv.model());
        assertEquals("env-url", fromEnv.baseUrl());
        userConfig(".cafecli", """
                {"defaultProvider":"deepseek","providers":{"deepseek":
                 {"apiKey":"config-key","model":"config-model","baseUrl":"config-url","temperature":0.3,"maxTokens":10}}}
                """);
        var fromConfig = load(Map.of(), env).provider();
        assertEquals("config-key", fromConfig.apiKey());
        assertEquals("config-model", fromConfig.model());
        assertEquals("config-url", fromConfig.baseUrl());
        org.junit.jupiter.api.Assertions.assertFalse(fromConfig.toString().contains("config-key"));
    }

    @Test
    void supportedAliasesStaySelectedButUnknownOrMissingKeyDefaultsUseTheV1FallbackOrder() throws IOException {
        userConfig(".cafecli", """
                {"defaultProvider":"unknown","providers":{"unknown":{"apiKey":"unknown-key"}}}
                """);
        assertEquals("deepseek", load(Map.of(), Map.of("DEEPSEEK_API_KEY", "x")).provider().name());
        assertEquals("glm", load(Map.of(), Map.of("GLM_API_KEY", "g", "DEEPSEEK_API_KEY", "d")).provider().name());
        userConfig(".cafecli", """
                {"defaultProvider":"stepfun","providers":{"stepfun":{"apiKey":"alias-key","model":"alias-model"}}}
                """);
        var alias = load(Map.of(), Map.of("DEEPSEEK_API_KEY", "x")).provider();
        assertEquals("step", alias.name());
        assertEquals("alias-key", alias.apiKey());
        assertEquals("alias-model", alias.model());
        userConfig(".cafecli", "{\"defaultProvider\":\"kimi\"}");
        assertEquals("kimi", load(Map.of(), Map.of("MOONSHOT_API_KEY", "moon-key")).provider().name());
    }

    @Test
    void typedModelOverridesAndTimeoutPropertiesReachTheProviderSlice() throws IOException {
        userConfig(".cafecli", """
                {"defaultProvider":"deepseek","models":[
                 {"provider":"deepseek","id":"deepseek-v4-flash","contextWindow":42000,"maxOutputTokens":1234},
                 {"provider":"deepseek","id":"local-model","contextWindow":9000,"features":["REASONING"]}]}
                """);
        var selection = load(Map.of("paicli.llm.read.timeout.seconds", "17",
                "cafecli.llm.connect.timeout.seconds", "3"), Map.of()).provider();
        var registry = new com.cafecli.ai.ModelRegistry(selection.modelOverrides());
        assertEquals(42_000, registry.defaultModel("deepseek").contextWindow());
        assertEquals(1234, registry.defaultModel("deepseek").maxOutputTokens());
        assertEquals(9000, registry.resolve("deepseek", "local-model").contextWindow());
        assertEquals(new com.cafecli.ai.openai.HttpTimeouts(3, 17, 60, 600), selection.timeouts());
    }

    @Test
    void aliasesPreserveNormalizedBeforeAliasConfigAndKimiBeforeMoonshotEnvironmentPrecedence() throws IOException {
        userConfig(".cafecli", """
                {"defaultProvider":"moonshot-ai","providers":{"moonshot-ai":{"apiKey":"alias-key","model":"alias-model","baseUrl":"alias-base"}}}
                """);
        Files.writeString(project.resolve(".env"), "KIMI_API_KEY=dot-kimi-key\nKIMI_MODEL=dot-kimi-model\nKIMI_BASE_URL=dot-kimi-base\n");
        var selection = load(Map.of(), Map.of("MOONSHOT_API_KEY", "env-moon-key", "MOONSHOT_MODEL", "env-moon-model",
                "MOONSHOT_BASE_URL", "env-moon-base")).provider();
        assertEquals("kimi", selection.name());
        assertEquals("dot-kimi-key", selection.apiKey());
        assertEquals("dot-kimi-model", selection.model());
        assertEquals("dot-kimi-base", selection.baseUrl());
        // v1 resolves normalized-provider values (including env/.env) before alias-provider config fields.
        selection = load(Map.of(), Map.of("KIMI_API_KEY", "env-kimi-key", "KIMI_MODEL", "env-kimi-model")).provider();
        assertEquals("env-kimi-key", selection.apiKey());
        assertEquals("env-kimi-model", selection.model());
    }

    @Test
    void blankProjectAssignmentsMaskHomeAssignmentsAndDotEnvDoesNotStripQuotesOrExpandShell() throws IOException {
        Files.writeString(home.resolve(".env"), "DEEPSEEK_API_KEY=home-key\n");
        Files.writeString(project.resolve(".env"), "DEEPSEEK_API_KEY=\nDEEPSEEK_API_KEY=second-key\n");
        assertEquals("glm", load(Map.of(), Map.of()).provider().name());
        Files.writeString(project.resolve(".env"), "DEEPSEEK_API_KEY=\"literal-key\"\nDEEPSEEK_MODEL=${MODEL}\n");
        var selection = load(Map.of(), Map.of()).provider();
        assertEquals("\"literal-key\"", selection.apiKey());
        assertEquals("${MODEL}", selection.model());
    }

    @Test
    void fallbackDoesNotSkipAKnownProviderWithAKeyJustBecauseItsV2AdapterIsNotAvailable() throws IOException {
        userConfig(".cafecli", "{\"defaultProvider\":\"step-fun\"}");
        var selection = load(Map.of(), Map.of("STEP_API_KEY", "step-key", "DEEPSEEK_API_KEY", "deepseek-key")).provider();
        assertEquals("step", selection.name());
        assertEquals("step-key", selection.apiKey());
        userConfig(".cafecli", "{\"defaultProvider\":\"step\"}");
        assertEquals("deepseek", load(Map.of(), Map.of("DEEPSEEK_API_KEY", "deepseek-key")).provider().name());
    }

    @Test
    void malformedCredentialConfigAndInvalidMetadataProduceWarningsWithoutPrintingTheirValues() throws IOException {
        userConfig(".cafecli", "{\"providers\":{\"deepseek\":{\"apiKey\":\"do-not-print-key\"}}, malformed}");
        var config = load(Map.of(), Map.of());
        assertEquals(1, config.warnings().size());
        org.junit.jupiter.api.Assertions.assertFalse(config.warnings().toString().contains("do-not-print-key"));
        userConfig(".cafecli", """
                {"models":[{"provider":"deepseek","id":"x","features":["invalid-feature"]}]}
                """);
        config = load(Map.of("cafecli.llm.read.timeout.seconds", "0", "paicli.llm.read.timeout.seconds", "20"), Map.of());
        assertEquals(1, config.warnings().size());
        assertEquals(300, config.provider().timeouts().read());
        assertEquals(List.of(), config.provider().modelOverrides());
    }

    private Path userConfig(String homeDir, String json) throws IOException {
        Path file = Files.createDirectories(home.resolve(homeDir)).resolve("config.json");
        Files.writeString(file, json);
        return file;
    }

    private HarnessConfig load(Map<String, String> properties, Map<String, String> environment) {
        return ConfigLoader.load(new ConfigSources(properties, environment, home, project));
    }
}
