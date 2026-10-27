package com.cafecli.context;

import java.util.Locale;

public final class TokenUsageFormatter {
    private TokenUsageFormatter() {
    }

    public static String estimatedCostCny(String providerName, int inputTokens, int outputTokens, int cachedInputTokens) {
        String provider = providerName == null ? "" : providerName;
        double inputPerMillion;
        double cachedPerMillion;
        double outputPerMillion;
        if ("deepseek".equalsIgnoreCase(provider)) {
            inputPerMillion = 2.0;
            cachedPerMillion = 0.5;
            outputPerMillion = 8.0;
        } else if ("glm".equalsIgnoreCase(provider)) {
            inputPerMillion = 5.0;
            cachedPerMillion = 1.0;
            outputPerMillion = 15.0;
        } else {
            inputPerMillion = 5.0;
            cachedPerMillion = 1.0;
            outputPerMillion = 15.0;
        }
        int cached = Math.max(0, Math.min(inputTokens, cachedInputTokens));
        int uncachedInput = Math.max(0, inputTokens - cached);
        double cny = (uncachedInput / 1_000_000.0) * inputPerMillion
                + (cached / 1_000_000.0) * cachedPerMillion
                + (Math.max(0, outputTokens) / 1_000_000.0) * outputPerMillion;
        return String.format(Locale.ROOT, "¥%.4f", cny);
    }
}
