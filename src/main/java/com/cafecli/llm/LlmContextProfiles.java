package com.cafecli.llm;

import com.cafecli.context.ContextProfile;

/** v1's bridge from an {@link LlmClient} to a {@link ContextProfile}. */
public final class LlmContextProfiles {
    private LlmContextProfiles() {}

    /** A null client gets a 128k window and no prompt caching. */
    public static ContextProfile from(LlmClient llmClient) {
        return ContextProfile.forWindow(
                llmClient == null ? 128_000 : llmClient.maxContextWindow(),
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode());
    }
}
