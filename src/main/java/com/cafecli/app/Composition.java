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
        Provider provider = new com.cafecli.ai.RoutingProvider(java.util.Map.of(
                config.provider().name(), provider(config.provider())));
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
        List<Capability> childCapabilities = List.of(new SessionCapability(),
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
                        mcp::serverManager, permissions, new com.cafecli.browser.BrowserConnectivityCheck()));
        var tasks = new com.cafecli.harness.tasks.SubAgentsCapability(factory,
                com.cafecli.harness.AgentProfile.mainAgent(model, childCapabilities, config.maxIterations()));
        var capabilities = new java.util.ArrayList<>(childCapabilities);
        capabilities.add(tasks);
        return AgentSession.builder()
                .config(config)
                .factory(factory)
                .model(model)
                .capabilities(capabilities)
                .build();
    }

    private static Provider provider(ProviderSelection selection) throws Unavailable {
        if ("deepseek".equals(selection.name())) {
            if (selection.apiKey() == null || selection.apiKey().isBlank()) {
                throw new Unavailable("❌ DeepSeek needs an API key: DEEPSEEK_API_KEY or providers.deepseek.apiKey in config.json");
            }
            // v1 ignores DeepSeek's configured baseUrl; preserve its production endpoint. Localhost fixtures
            // inject the endpoint directly at the ai adapter seam.
            return new DeepSeekProvider(selection.apiKey(), DeepSeekProvider.DEFAULT_URL, selection.timeouts());
        }
        if (!selection.isFake()) {
            throw new Unavailable(Cli.notAvailable("provider " + selection.name(), "v2 supports DeepSeek and the fake "
                    + "provider so far (-Dcafecli.provider=fake -Dcafecli.fake.script=<script.json>)"));
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
