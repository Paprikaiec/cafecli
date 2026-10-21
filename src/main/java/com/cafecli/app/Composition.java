package com.cafecli.app;

import com.cafecli.ai.Model;
import com.cafecli.ai.ModelRegistry;
import com.cafecli.ai.openai.DeepSeekProvider;
import com.cafecli.ai.Provider;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeScript;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.Capability;
import com.cafecli.harness.workspace.WorkspaceCapability;
import com.cafecli.harness.config.HarnessConfig;
import com.cafecli.harness.config.ProviderSelection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
        Provider provider = provider(config.provider());
        List<Capability> capabilities = List.of(new WorkspaceCapability(config.projectRoot(), config.workspace()));
        return AgentSession.builder()
                .config(config)
                .provider(provider)
                .model(config.provider().isFake() ? FAKE_MODEL
                        : new ModelRegistry(config.provider().modelOverrides())
                                .resolve(config.provider().name(), config.provider().model()))
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
