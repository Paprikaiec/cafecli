package com.cafecli.harness.config;

import com.cafecli.config.ConfigHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Loads the {@link HarnessConfig} from {@link ConfigSources}, from the same sources and in the same precedence as v1,
 * and reads nothing else: no system property, environment variable, home or working directory that is not in the
 * sources. Each knob, with where v1 reads it:
 *
 * <ul>
 *   <li><b>Config homes</b> — {@code <user home>/.cafecli} and {@code <project root>/.cafecli}, each falling back to
 *       {@code .paicli} when only that exists ({@code ConfigHome.configDirUnder}).</li>
 *   <li><b>Hard iteration cap</b> — property {@code cafecli.react.hard.max.iterations}, else
 *       {@code paicli.react.hard.max.iterations}; blank, unparsable or not positive means 50. No environment
 *       variable, no config file ({@code AgentBudget}).</li>
 *   <li><b>Provider</b> — {@code defaultProvider} in the user home's {@code config.json}, default {@code glm}
 *       ({@code CafeCliConfig}); trimmed and lower-cased ({@code LlmClientFactory}). New in v2, and above it: the
 *       property {@code cafecli.provider} (else {@code paicli.provider}), then the environment variable
 *       {@code CAFECLI_PROVIDER} (else {@code PAICLI_PROVIDER}), so the fake can be selected without editing the
 *       user's file. A blank value counts as not set.</li>
 *   <li><b>Fake provider script</b> (new in v2) — property {@code cafecli.fake.script} (else
 *       {@code paicli.fake.script}), then {@code CAFECLI_FAKE_SCRIPT} (else {@code PAICLI_FAKE_SCRIPT}); relative to
 *       the working directory.</li>
 *   <li><b>Project root</b> — the working directory, as v1's {@code Path.of(".").toAbsolutePath().normalize()}.</li>
 * </ul>
 *
 * <p>The legacy fallback is v1's, from its one home ({@code ConfigHome}, its map-taking overloads): a
 * {@code cafecli.*} property that is present wins over its {@code paicli.*} twin, even when blank, as after v1's
 * property bridge; {@code CAFECLI_*} when set, else {@code PAICLI_*}. New knobs take the property before the
 * environment variable, as v1's snapshot and audit knobs do.
 *
 * <p>A {@code config.json} that exists but cannot be read is not an error: like v1, the loader uses the defaults and
 * reports a {@link ConfigWarning}.
 */
public final class ConfigLoader {

    private static final int DEFAULT_MAX_ITERATIONS = 50;
    private static final String DEFAULT_PROVIDER = "glm";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {
    }

    public static HarnessConfig load(ConfigSources sources) {
        Path projectRoot = sources.workingDirectory().normalize();
        ConfigHomes homes = new ConfigHomes(ConfigHome.configDirUnder(sources.userHome()),
                ConfigHome.configDirUnder(projectRoot));
        List<ConfigWarning> warnings = new ArrayList<>();
        JsonNode userConfig = readJson(homes.user().resolve("config.json"), warnings);
        return new HarnessConfig(provider(sources, userConfig),
                positiveInt(property(sources, "react.hard.max.iterations"), DEFAULT_MAX_ITERATIONS),
                projectRoot, homes, warnings);
    }

    private static ProviderSelection provider(ConfigSources sources, JsonNode userConfig) {
        String name = firstSet(property(sources, "provider"), environment(sources, "PROVIDER"),
                text(userConfig.path("defaultProvider")), DEFAULT_PROVIDER);
        Optional<Path> fakeScript = Optional
                .ofNullable(firstSet(property(sources, "fake.script"), environment(sources, "FAKE_SCRIPT")))
                .map(path -> sources.workingDirectory().resolve(path.trim()).normalize());
        return new ProviderSelection(name.trim().toLowerCase(Locale.ROOT), fakeScript);
    }

    /**
     * The file's JSON, or an empty object when there is no such file. A file that exists but cannot be read is a
     * warning and counts as empty, so every default applies — v1's {@code CafeCliConfig.load} does the same.
     */
    private static JsonNode readJson(Path file, List<ConfigWarning> warnings) {
        if (!Files.exists(file)) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(file.toFile());
            if (node != null && node.isObject()) {
                return node;
            }
            warnings.add(new ConfigWarning(file, "not a JSON object"));
        } catch (IOException e) {
            warnings.add(new ConfigWarning(file, String.valueOf(e.getMessage())));
        }
        return JSON.createObjectNode();
    }

    /** A scalar's text, as Jackson would bind it to a {@code String} field; {@code null} when absent or null. */
    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    /** The first value that is neither {@code null} nor blank; {@code null} if there is none. */
    private static String firstSet(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** {@code cafecli.<name>}, else {@code paicli.<name>}, by v1's one rule for it ({@link ConfigHome#property}). */
    private static String property(ConfigSources sources, String name) {
        return ConfigHome.property(sources.properties(), name);
    }

    /** {@code CAFECLI_<name>}, else {@code PAICLI_<name>}, by v1's one rule for it ({@link ConfigHome#env}). */
    private static String environment(ConfigSources sources, String name) {
        return ConfigHome.env(sources.environment(), "CAFECLI_" + name, "PAICLI_" + name);
    }

    /** v1's {@code AgentBudget.readIntProperty}: blank, unparsable or not positive means the default. */
    private static int positiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
