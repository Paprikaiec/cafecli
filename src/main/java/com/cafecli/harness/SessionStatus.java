package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.context.TokenUsageFormatter;
import java.util.Objects;

/** Immutable MAIN presentation data, captured by its owner without exposing projection or engine internals. */
public record SessionStatus(Model model, long contextTokens, long inputTokens, long outputTokens,
                            long cachedInputTokens, String estimatedCost) {
    public SessionStatus { Objects.requireNonNull(model); }
    public static SessionStatus idle(Model model) { return new SessionStatus(model,0,0,0,0,null); }
    public SessionStatus withUsage(long input, long output, long cached) {
        return new SessionStatus(model,contextTokens,input,output,cached,
                TokenUsageFormatter.estimatedCostCny(model.provider(),bounded(input),bounded(output),bounded(cached)));
    }
    public SessionStatus withModel(Model model) {
        return new SessionStatus(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,null)
                .withUsage(inputTokens,outputTokens,cachedInputTokens);
    }
    private static int bounded(long count) { return (int)Math.min(Integer.MAX_VALUE,Math.max(0,count)); }
}
