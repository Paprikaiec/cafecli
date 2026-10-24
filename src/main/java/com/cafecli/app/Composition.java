package com.cafecli.app;

import com.cafecli.ai.Model;
import com.cafecli.ai.ModelRegistry;
import com.cafecli.ai.Provider;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeScript;
import com.cafecli.ai.openai.DeepSeekProvider;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.Capability;
import com.cafecli.harness.SessionCapability;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.harness.config.ProviderSelection;
import com.cafecli.harness.hooks.HooksCapability;
import com.cafecli.harness.context.BudgetCapability;
import com.cafecli.harness.context.CompactionCapability;
import com.cafecli.harness.memory.MemoryCapability;
import com.cafecli.harness.skills.SkillsCapability;
import com.cafecli.harness.lsp.LspCapability;
import com.cafecli.harness.mcp.McpCapability;
import com.cafecli.harness.permission.AuditCapability;
import com.cafecli.harness.permission.PermissionCapability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.policy.AuditLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The composition root (ADR 0020 decision 7): the one place v2 is wired together, by explicit constructor injection in
 * a declared order — no service locator, no DI container — from the configuration loaded once at the entry. Nothing
 * in {@code ai}, {@code core}, {@code harness} or {@code ui} depends on this package.
 *
 * <p>The order, which later tickets extend in place: the provider and the model; the capabilities, each constructed
 * with its slice of the configuration and the capabilities it builds on, in declared order (the workspace so far); the session over them.
 */
final class Composition {

    /**
     * The fake provider's model record. Values: the fake answers whatever it is scripted to, so the window and output
     * limit are a generous 128k / 8k (v1's default {@code maxTokens} is 8192) and it claims no feature, so nothing
     * downstream takes a code path a real model would have to opt into.
     */
    static final Model FAKE_MODEL = new Model("fake-model", ProviderSelection.FAKE, "fake", 128_000, 8_192, Set.of());

    private Composition() {
    }

    /** The session for one headless run. */
    static AgentSession headlessSession(HarnessConfig config) throws Unavailable {
        return headlessSession(config, ignored -> {});
    }

    static AgentSession headlessSession(HarnessConfig config, Consumer<String> status) throws Unavailable {
        return headlessSession(config, status, ignored -> {});
    }

    static AgentSession headlessSession(HarnessConfig config, Consumer<String> status, Consumer<String> warning) throws Unavailable {
        return headlessSession(config, status, warning, null);
    }

    static AgentSession headlessSession(HarnessConfig config, Consumer<String> status, Consumer<String> warning,
                                        com.cafecli.harness.trace.TraceCapability trace) throws Unavailable {
        return session(config, status, warning, trace, false);
    }

    /** Interactive assembly remains terminal-free; UI adapters are supplied by the later UI integration. */
    static AgentSession interactiveSession(HarnessConfig config, Consumer<String> status, Consumer<String> warning) throws Unavailable {
        return session(config, status, warning, null, true);
    }

    private static AgentSession session(HarnessConfig config, Consumer<String> status, Consumer<String> warning,
                                        com.cafecli.harness.trace.TraceCapability trace, boolean interactive) throws Unavailable {
        var adapters = new java.util.LinkedHashMap<String, Provider>();
        adapters.put(config.provider().startupRoute(), provider(config.provider()));
        for (var entry : config.provider().providers().entrySet()) {
            if (!entry.getKey().equals(config.provider().startupRoute()) && entry.getValue().hasKey()) {
                var settings = entry.getValue();
                adapters.put(entry.getKey(), provider(new ProviderSelection(entry.getKey(), java.util.Optional.empty(),
                        settings.apiKey(), settings.model(), settings.baseUrl(), config.provider().modelOverrides(), config.provider().timeouts())));
            }
        }
        Provider provider = new com.cafecli.ai.RoutingProvider(adapters);
        var snapshots = new com.cafecli.harness.snapshot.SnapshotCapability(
                new com.cafecli.snapshot.SnapshotService(new com.cafecli.snapshot.SideGitManager(config.projectRoot(), config.snapshots())), warning);
        // Own the shared writer before tasks/MCP so reverse cleanup closes all child users before it.
        Capability snapshotOwnership = new Capability() {
            @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) { session.onClose(snapshots); }
        };
        // v1 runHeadless uses the base registry: automatic approval, with no terminal reader or pump.
        PermissionCapability permissions = PermissionCapability.headless();
        AuditCapability audit = new AuditCapability(permissions, new AuditLog(config.auditDirectory()));
        McpCapability mcp = new McpCapability(config.mcp(), audit);
        var homes = config.homes();
        var factory = new com.cafecli.harness.AgentFactory(provider,
                new com.cafecli.prompt.PromptAssembler(new com.cafecli.prompt.PromptRepository(
                        homes.user().resolve("prompts"), homes.project().resolve("prompts"))),
                config.workspace().toolBatchTimeout());
        Model model = config.provider().isFake() ? FAKE_MODEL : new ModelRegistry(config.provider().modelOverrides())
                .resolve(config.provider().name(), config.provider().model());
        if (!model.providerRoute().equals(config.provider().startupRoute())) {
            model = new Model(model.id(), model.provider(), model.api(), model.contextWindow(), model.maxOutputTokens(),
                    model.features(), config.provider().startupRoute());
        }
        List<Capability> childCapabilities = List.of(snapshotOwnership, new SessionCapability(), new com.cafecli.harness.ModelCapability(config.provider(), warning),
                new WorkspaceCapability(config.projectRoot(), config.workspace()),
                new SkillsCapability(config.skills(), status),
                new com.cafecli.harness.images.ImagesCapability(config.projectRoot()),
                new LspCapability(config.lsp()),
                new MemoryCapability(config.memory()), new CompactionCapability(provider),
                new BudgetCapability(config.budget()),
                permissions, audit, mcp,
                new HooksCapability(config.hooks(), audit, null, status),
                new com.cafecli.harness.web.WebCapability(config.web().searchProvider(),
                        new com.cafecli.web.WebFetcher(), new com.cafecli.web.HtmlExtractor(),
                        new com.cafecli.web.NetworkPolicy(), status),
                new com.cafecli.harness.rag.RagCapability(config.projectRoot(), config.rag()),
                new com.cafecli.harness.browser.BrowserCapability(config.homes().user().resolve("sensitive_patterns.txt"),
                        mcp::serverManager, permissions, new com.cafecli.browser.BrowserConnectivityCheck()), snapshots);
        var tasks = new com.cafecli.harness.tasks.SubAgentsCapability(factory,
                com.cafecli.harness.AgentProfile.mainAgent(model, childCapabilities, config.maxIterations()), config.tasks());
        var capabilities = new java.util.ArrayList<>(childCapabilities);
        if (!interactive) capabilities.add(0, new HeadlessRetryStatus(status));
        if (interactive) {
            var localMentions = new com.cafecli.input.LocalPathMentionExpander(config.projectRoot(), Path.of(config.hooks().userHome()));
            Capability localInput = new Capability() {
                @Override public void attachSession(com.cafecli.harness.SessionRegistrar session) {
                    session.onSubmit((input, token) -> com.cafecli.harness.Submission.proceed(localMentions.expand(input)));
                }
            };
            // MCP resources expand first, local paths next, and the UserPromptSubmit gate sees both.
            capabilities.add(capabilities.indexOf(mcp) + 1, localInput);
        }
        // Post-turn scheduling follows Attached-task shutdown; pre-turn still precedes all ordinary writers.
        capabilities.add(capabilities.size() - 1, tasks);
        if (trace != null) capabilities.add(trace);
        return AgentSession.builder()
                .config(config)
                .factory(factory)
                .interactive(interactive)
                .model(model)
                .capabilities(capabilities)
                .build();
    }

    private static Provider provider(ProviderSelection selection) throws Unavailable {
        if (!selection.isFake()) {
            if (selection.apiKey() == null || selection.apiKey().isBlank()) {
                throw new Unavailable("❌ " + (selection.name().equals("deepseek") ? "DeepSeek" : selection.name()) + " needs an API key: "
                        + selection.name().toUpperCase(java.util.Locale.ROOT) + "_API_KEY or providers."
                        + selection.name() + ".apiKey in config.json");
            }
            return switch (selection.name()) {
                // As v1, configured GLM/DeepSeek base URLs are ignored.
                case "glm" -> new com.cafecli.ai.openai.GlmProvider(selection.apiKey(), selection.timeouts());
                case "deepseek" -> new DeepSeekProvider(selection.apiKey(), DeepSeekProvider.DEFAULT_URL, selection.timeouts());
                case "step" -> new com.cafecli.ai.openai.StepProvider(selection.apiKey(), selection.baseUrl(), selection.timeouts());
                case "kimi" -> new com.cafecli.ai.openai.KimiProvider(selection.apiKey(), selection.baseUrl(), selection.timeouts());
                case "freellmapi" -> new com.cafecli.ai.openai.FreeLlmApiProvider(selection.apiKey(), selection.baseUrl(), selection.timeouts());
                default -> throw new Unavailable("❌ unknown provider: " + selection.name());
            };
        }
        Path script = selection.fakeScript().orElseThrow(() -> new Unavailable(
                "❌ the fake provider needs a script: -Dcafecli.fake.script=<script.json> (or CAFECLI_FAKE_SCRIPT)"));
        try {
            return FakeProvider.scripted(FakeScript.load(script));
        } catch (IOException e) {
            throw new Unavailable("❌ cannot read the fake provider's script: " + e);
        } catch (IllegalArgumentException e) {
            throw new Unavailable("❌ invalid fake provider script: " + e.getMessage());
        }
    }

    /** No session can be built from this configuration; the message says why, for the user. */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }
}
