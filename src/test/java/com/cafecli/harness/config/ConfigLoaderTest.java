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
    void aConfigJsonWithoutADefaultProviderMeansGlm() throws IOException {
        userConfig(".cafecli", "{\"providers\": {\"deepseek\": {\"apiKey\": \"x\"}}}");

        assertEquals("glm", load(Map.of(), Map.of()).provider().name());
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

    private Path userConfig(String homeDir, String json) throws IOException {
        Path file = Files.createDirectories(home.resolve(homeDir)).resolve("config.json");
        Files.writeString(file, json);
        return file;
    }

    private HarnessConfig load(Map<String, String> properties, Map<String, String> environment) {
        return ConfigLoader.load(new ConfigSources(properties, environment, home, project));
    }
}
