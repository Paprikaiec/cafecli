package com.cafecli.harness.config;

import com.cafecli.config.ConfigHome;
import com.cafecli.ai.Model;
import com.cafecli.hook.FinishGateTenant;
import com.cafecli.ai.ModelRegistry;
import com.cafecli.ai.openai.HttpTimeouts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.EnumSet;
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
 *   <li><b>Run budgets</b> — properties {@code cafecli.react.token.budget} (default
 *       {@code Integer.MAX_VALUE}) and {@code cafecli.react.stagnation.window} (default 3), with legacy property
 *       fallback and positive-integer parsing as in {@code AgentBudget}; a configured window of 1 is rejected.</li>
 *   <li><b>Provider</b> — {@code defaultProvider} in the user home's {@code config.json}, default {@code glm}
 *       ({@code CafeCliConfig}); trimmed and lower-cased ({@code LlmClientFactory}). New in v2, and above it: the
 *       property {@code cafecli.provider} (else {@code paicli.provider}), then the environment variable
 *       {@code CAFECLI_PROVIDER} (else {@code PAICLI_PROVIDER}), so the fake can be selected without editing the
 *       user's file. A blank value counts as not set. Aliases and fallback-by-key follow
 *       {@code LlmClientFactory}: try the configured provider, then glm / deepseek / step / kimi / freellmapi.</li>
 *   <li><b>Provider key / model / base URL</b> — the user config's {@code providers.<name>} entry, then that
 *       provider's environment variable, then project {@code .env}, then user-home {@code .env}; Kimi's
 *       {@code MOONSHOT_*} alias follows {@code KIMI_*}. The DeepSeek adapter still ignores configured base URLs,
 *       temperature and maxTokens, as v1 does.</li>
 *   <li><b>Model metadata</b> — optional {@code models} array in the same user config: provider / id plus optional
 *       api / contextWindow / maxOutputTokens / features fields override the bundled registry data.</li>
 *   <li><b>HTTP timeouts</b> — positive {@code llm.{connect,read,write,call}.timeout.seconds} properties, under the
 *       same cafecli / paicli precedence, defaulting to 60 / 300 / 60 / 600 seconds.</li>
 *   <li><b>Fake provider script</b> (new in v2) — property {@code cafecli.fake.script} (else
 *       {@code paicli.fake.script}), then {@code CAFECLI_FAKE_SCRIPT} (else {@code PAICLI_FAKE_SCRIPT}); relative to
 *       the working directory.</li>
 *   <li><b>Audit directory</b> — cafecli / paicli {@code audit.dir} property, then CAFECLI / PAICLI
 *       {@code AUDIT_DIR}, else the explicit user config home's {@code audit} directory; relative paths use the
 *       working directory, as v1's process does.</li>
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
    public static HistoryConfig history(ConfigSources sources) {
        String configured = firstSet(property(sources, "history.file"), environment(sources, "HISTORY_FILE"));
        Path path = configured == null ? ConfigHome.configDirUnder(sources.userHome()).resolve("history/input.history")
                : sources.workingDirectory().resolve(configured).normalize();
        if (Files.isDirectory(path)) path = path.resolve("input.history");
        return new HistoryConfig(path.normalize(), positiveInt(firstSet(property(sources, "history.size"),
                environment(sources, "HISTORY_SIZE")), 2000), positiveInt(firstSet(property(sources, "history.fileSize"),
                environment(sources, "HISTORY_FILE_SIZE")), 10000));
    }

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
        return new HarnessConfig(provider(sources, userConfig, warnings, homes.user().resolve("config.json")),
                positiveInt(property(sources, "react.hard.max.iterations"), DEFAULT_MAX_ITERATIONS),
                projectRoot, homes, warnings,
                new WorkspaceConfig(truthy(property(sources, "edit_file.disabled")), 60),
                auditDirectory(sources, homes), hooks(sources, homes), memory(sources, homes),
                new BudgetConfig(positiveInt(property(sources, "react.token.budget"), Integer.MAX_VALUE),
                        positiveInt(property(sources, "react.stagnation.window"), 3)),
                new McpConfig(projectRoot, homes, sources.properties(), sources.environment(), sources.userHome()),
                new LspConfig(projectRoot, lspEnabled(sources),
                        positiveInt(firstSet(property(sources, "lsp.max.diagnostics"),
                                environment(sources, "LSP_MAX_DIAGNOSTICS")), 20)), rag(sources,homes), web(sources), tasks(sources), snapshots(sources, homes));
    }

    private static com.cafecli.snapshot.SnapshotConfig snapshots(ConfigSources sources, ConfigHomes homes) {
        var config = com.cafecli.snapshot.SnapshotConfig.fromValues(
                snapshotValue(sources, "enabled"), snapshotValue(sources, "dir"), snapshotValue(sources, "max"),
                snapshotValue(sources, "excludes"), homes.user().resolve("snapshots"));
        Path directory = sources.workingDirectory().resolve(config.snapshotsRoot()).normalize();
        return new com.cafecli.snapshot.SnapshotConfig(config.enabled(), directory, config.maxSnapshots(), config.excludes());
    }

    private static String snapshotValue(ConfigSources sources, String name) {
        String value = property(sources, "snapshot." + name);
        return value != null ? value : environment(sources, "SNAPSHOT_" + name.toUpperCase(Locale.ROOT));
    }

    private static TaskConfig tasks(ConfigSources sources) {
        long autoMillis = TaskConfig.DEFAULT.autoBackgroundMillis();
        try {
            String value = property(sources, "task.auto-background-ms");
            if (value != null) autoMillis = Long.decode(value);
        } catch (NumberFormatException ignored) { }
        return new TaskConfig(false, autoMillis,
                taskInteger(sources, "task.max-concurrency", TaskConfig.DEFAULT.maxConcurrency()),
                taskInteger(sources, "task.max-depth", TaskConfig.DEFAULT.maxDepth()),
                taskInteger(sources, "task.max-idle", TaskConfig.DEFAULT.maxIdle()),
                taskInteger(sources, "task.message-mirror-cap", TaskConfig.DEFAULT.messageMirrorCap()));
    }

    private static int taskInteger(ConfigSources sources, String name, int fallback) {
        try {
            String value = property(sources, name);
            return value == null ? fallback : Integer.decode(value);
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private static WebConfig web(ConfigSources sources) {
        Map<String, String> values = dotEnv(sources);
        return new WebConfig(searchValue(sources, values, "SEARCH_PROVIDER"), searchValue(sources, values, "GLM_API_KEY"),
                searchValue(sources, values, "ZHIPU_SEARCH_ENGINE"), searchValue(sources, values, "SERPAPI_KEY"),
                searchValue(sources, values, "SEARXNG_URL"));
    }

    private static String searchValue(ConfigSources sources, Map<String, String> dotEnv, String key) {
        String value = firstSet(sources.environment().get(key), sources.properties().get(key));
        return value == null ? dotEnv.get(key) : value.trim();
    }

    private static RagConfig rag(ConfigSources sources,ConfigHomes homes) {
        String provider = embedding(sources, "PROVIDER", "ollama");
        String url = switch (provider.toLowerCase(Locale.ROOT)) {
            case "zhipu", "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            default -> "http://localhost:11434";
        };
        String directory=property(sources,"rag.dir");
        return new RagConfig(provider, embedding(sources, "MODEL", "nomic-embed-text:latest"),
                embedding(sources, "BASE_URL", url), embedding(sources, "API_KEY", ""),sources.workingDirectory().resolve(
                        directory==null ? homes.user().resolve("rag").toString() : directory).normalize());
    }

    private static String embedding(ConfigSources sources, String suffix, String fallback) {
        String key = "EMBEDDING_" + suffix;
        String value = sources.environment().get(key);
        if (value != null && !value.isEmpty()) return value;
        value = sources.properties().get(key);
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static boolean lspEnabled(ConfigSources sources) {
        String raw = firstSet(property(sources, "lsp.enabled"), environment(sources, "LSP_ENABLED"));
        return raw == null || Boolean.parseBoolean(raw.trim());
    }

    private static MemoryConfig memory(ConfigSources sources, ConfigHomes homes) {
        String directory = firstSet(property(sources, "memory.dir"), environment(sources, "MEMORY_DIR"));
        return MemoryConfig.forProject(directory == null ? homes.user().resolve("memory")
                : sources.workingDirectory().resolve(directory).normalize(), sources.workingDirectory());
    }

    private static HooksConfig hooks(ConfigSources sources, ConfigHomes homes) {
        return new HooksConfig(truthy(property(sources, "hook.pre_tool_use.enabled")),
                truthy(property(sources, "hook.post_tool_use.enabled")),
                truthy(property(sources, "hook.stop.enabled")),
                truthy(property(sources, "hook.user_prompt_submit.enabled")),
                truthy(property(sources, "hook.read_ledger.enabled")),
                truthy(property(sources, "hook.finish_gate.enabled")),
                java.util.Optional.ofNullable(firstSet(property(sources, "hook.finish_gate.verify_regex")))
                        .map(String::trim).orElse(FinishGateTenant.DEFAULT_VERIFY_REGEX),
                positiveInt(property(sources, "hook.finish_gate.block_cap"), 2),
                sources.properties(), sources.environment(), sources.userHome().toString(), sources.workingDirectory().normalize(), homes);
    }

    private static Path auditDirectory(ConfigSources sources, ConfigHomes homes) {
        String configured = firstSet(property(sources, "audit.dir"), environment(sources, "AUDIT_DIR"));
        return configured == null ? homes.user().resolve("audit")
                : sources.workingDirectory().resolve(configured).normalize();
    }

    /** Same accepted spellings as the file leaf's ToolArgs.parseBoolean. */
    private static boolean truthy(String raw) {
        return raw != null && ("true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim())
                || "yes".equalsIgnoreCase(raw.trim()));
    }

    private static ProviderSelection provider(ConfigSources sources, JsonNode userConfig,
                                              List<ConfigWarning> warnings, Path configFile) {
        String configured = firstSet(property(sources, "provider"), environment(sources, "PROVIDER"),
                text(userConfig.path("defaultProvider")), DEFAULT_PROVIDER).trim().toLowerCase(Locale.ROOT);
        Map<String, String> dotEnv = dotEnv(sources);
        String name = normalizeProvider(configured);
        String key = providerValue(userConfig, sources, dotEnv, name, "apiKey", "API_KEY");
        if (!configured.equals(name)) key = firstSet(key,
                providerValue(userConfig, sources, dotEnv, configured, "apiKey", "API_KEY"));
        // v1's createFromConfig tries the configured adapter, then the five supported providers in this order.
        // Unknown providers cannot create an adapter even with a key; known aliases with a key stay selected.
        if (!ProviderSelection.FAKE.equals(name) && (!PROVIDERS.contains(name) || key == null)) {
            for (String fallback : PROVIDERS) {
                String candidate = providerValue(userConfig, sources, dotEnv, fallback, "apiKey", "API_KEY");
                if (candidate != null) {
                    name = fallback;
                    key = candidate;
                    break;
                }
            }
        }
        String model = providerValue(userConfig, sources, dotEnv, name, "model", "MODEL");
        String baseUrl = providerValue(userConfig, sources, dotEnv, name, "baseUrl", "BASE_URL");
        if (name.equals(normalizeProvider(configured)) && !configured.equals(name)) {
            model = firstSet(model, providerValue(userConfig, sources, dotEnv, configured, "model", "MODEL"));
            baseUrl = firstSet(baseUrl, providerValue(userConfig, sources, dotEnv, configured, "baseUrl", "BASE_URL"));
        }
        Optional<Path> fakeScript = Optional
                .ofNullable(firstSet(property(sources, "fake.script"), environment(sources, "FAKE_SCRIPT")))
                .map(path -> sources.workingDirectory().resolve(path.trim()).normalize());
        HttpTimeouts timeouts = new HttpTimeouts(timeout(sources, "connect", 60), timeout(sources, "read", 300),
                timeout(sources, "write", 60), timeout(sources, "call", 600));
        Map<String, ProviderSettings> captured = new java.util.LinkedHashMap<>();
        for (String candidate : PROVIDERS) {
            ProviderSettings settings = new ProviderSettings(
                    providerValue(userConfig, sources, dotEnv, candidate, "apiKey", "API_KEY"),
                    providerValue(userConfig, sources, dotEnv, candidate, "model", "MODEL"),
                    providerValue(userConfig, sources, dotEnv, candidate, "baseUrl", "BASE_URL"));
            captured.put(candidate, settings);
        }
        Map<String,ProviderSettings> fallbacks=new java.util.LinkedHashMap<>();
        JsonNode emptyConfig=JSON.createObjectNode();
        for (String candidate : PROVIDERS) fallbacks.put(candidate,new ProviderSettings(
                providerValue(emptyConfig,sources,dotEnv,candidate,"apiKey","API_KEY"),
                providerValue(emptyConfig,sources,dotEnv,candidate,"model","MODEL"),
                providerValue(emptyConfig,sources,dotEnv,candidate,"baseUrl","BASE_URL")));
        return new ProviderSelection(name, fakeScript, key, model, baseUrl,
                modelOverrides(userConfig.path("models"), warnings, configFile), timeouts, captured,
                Optional.of(new ModelPreferences(configFile, (com.fasterxml.jackson.databind.node.ObjectNode) userConfig,fallbacks)),
                name.equals(normalizeProvider(configured)) ? configured : name);
    }

    private static final List<String> PROVIDERS = List.of("glm", "deepseek", "step", "kimi", "freellmapi");

    private static String normalizeProvider(String provider) {
        return switch (provider) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            default -> provider;
        };
    }

    private static String providerValue(JsonNode config, ConfigSources sources, Map<String, String> dotEnv,
                                        String provider, String field, String suffix) {
        String configured = text(config.path("providers").path(provider).path(field));
        if (configured != null && !configured.isBlank()) return configured;
        String key = provider.toUpperCase(Locale.ROOT) + "_" + suffix;
        String value = firstSet(sources.environment().get(key), dotEnv.get(key));
        if (value == null && provider.equals("kimi")) {
            value = firstSet(sources.environment().get("MOONSHOT_" + suffix), dotEnv.get("MOONSHOT_" + suffix));
        }
        return value == null ? null : value.trim();
    }

    /** Like v1: first matching assignment wins, project before user home, no shell expansion or quote stripping. */
    private static Map<String, String> dotEnv(ConfigSources sources) {
        Map<String, String> values = new HashMap<>();
        for (Path file : List.of(sources.workingDirectory().resolve(".env"), sources.userHome().resolve(".env"))) {
            try (var lines = Files.newBufferedReader(file)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int equals = line.indexOf('=');
                    if (equals > 0) values.putIfAbsent(line.substring(0, equals), line.substring(equals + 1).trim());
                }
            } catch (IOException ignored) {
                // v1 treats a missing or unreadable .env as empty.
            }
        }
        return values;
    }

    private static long timeout(ConfigSources sources, String kind, long fallback) {
        String raw = property(sources, "llm." + kind + ".timeout.seconds");
        try {
            long value = raw == null ? fallback : Long.parseLong(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Metadata overrides are ai records; the loader owns only their config representation and validation. */
    private static List<Model> modelOverrides(JsonNode nodes, List<ConfigWarning> warnings, Path configFile) {
        List<Model> models = new ArrayList<>();
        if (nodes.isMissingNode() || nodes.isNull()) return models;
        if (!nodes.isArray()) {
            warnings.add(new ConfigWarning(configFile, "models must be an array"));
            return models;
        }
        ModelRegistry defaults = new ModelRegistry();
        for (JsonNode node : nodes) {
            try {
                String provider = node.path("provider").asText("");
                String id = node.path("id").asText("");
                if (provider.isBlank() || id.isBlank()) throw new IllegalArgumentException();
                Model base = PROVIDERS.contains(provider) ? defaults.defaultModel(provider)
                        : new Model(id, provider, "openai-completions", 128_000, 8192, java.util.Set.of());
                var features = EnumSet.noneOf(Model.Feature.class);
                if (node.has("features")) {
                    if (!node.path("features").isArray()) throw new IllegalArgumentException();
                    for (JsonNode feature : node.path("features")) features.add(Model.Feature.valueOf(feature.asText()));
                } else {
                    features.addAll(base.features());
                }
                models.add(new Model(id, provider, node.path("api").asText(base.api()),
                        positiveInt(text(node.path("contextWindow")), base.contextWindow()),
                        positiveInt(text(node.path("maxOutputTokens")), base.maxOutputTokens()), features));
            } catch (IllegalArgumentException e) {
                // Do not put config values in diagnostics; the same file contains credentials.
                warnings.add(new ConfigWarning(configFile, "invalid model metadata entry ignored"));
            }
        }
        return models;
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
            warnings.add(new ConfigWarning(file, "cannot read config JSON (" + e.getClass().getSimpleName() + ")"));
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

    /** Interactive renderer selection; the UI receives the value rather than reading process inputs. */
    public static String rendererMode(ConfigSources sources) {
        return firstSet(property(sources, "renderer"), environment(sources, "RENDERER"), "inline");
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
