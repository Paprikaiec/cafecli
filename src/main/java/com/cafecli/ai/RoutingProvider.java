package com.cafecli.ai;

import java.util.Map;
import java.util.Objects;

/** Routes model metadata to explicitly supplied adapters; never sends an unknown provider to another endpoint. */
public final class RoutingProvider implements Provider {
    private final Map<String, Provider> providers;

    public RoutingProvider(Map<String, Provider> providers) {
        this.providers = new java.util.concurrent.ConcurrentHashMap<>(providers);
    }

    /** Adds an explicitly configured generation; retained profiles keep their original route forever. */
    public void addRoute(String route,Provider provider) {
        Objects.requireNonNull(route); Objects.requireNonNull(provider);
        if (providers.putIfAbsent(route,provider)!=null) throw new IllegalArgumentException("duplicate provider route: " + route);
    }

    @Override public AssistantStream stream(Model model, Context context, StreamOptions options) {
        Objects.requireNonNull(model);
        Provider provider = providers.get(model.providerRoute());
        if (provider != null) return provider.stream(model, context, options);
        return AssistantStream.of(model, options.cancellation(), out -> {
            out.fail(ErrorKind.FATAL, "No adapter configured for provider: " + model.providerRoute());
            return false;
        });
    }
}
