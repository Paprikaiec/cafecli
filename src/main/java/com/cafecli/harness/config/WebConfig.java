package com.cafecli.harness.config;

/** Captured search-provider inputs; construction never reads the process environment. */
public record WebConfig(String provider, String glmKey, String zhipuEngine, String serpKey, String searxngUrl) {
    public com.cafecli.web.SearchProvider searchProvider() {
        return com.cafecli.web.SearchProviderFactory.create(provider, glmKey, zhipuEngine, serpKey, searxngUrl);
    }
    public static final WebConfig DEFAULT = new WebConfig(null, null, null, null, null);
}
