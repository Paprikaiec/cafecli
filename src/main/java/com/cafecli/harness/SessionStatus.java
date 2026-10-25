package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.context.TokenUsageFormatter;
import java.util.Objects;

/** Immutable MAIN presentation data, captured by its owner without exposing projection or engine internals. */
public record SessionStatus(Model model, long contextTokens, long inputTokens, long outputTokens,
                            long cachedInputTokens, String estimatedCost, boolean approvalEnabled, String contextDescription) {
    public SessionStatus(Model model,long contextTokens,long inputTokens,long outputTokens,long cachedInputTokens,
                         String estimatedCost,boolean approvalEnabled) {
        this(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,estimatedCost,approvalEnabled,"");
    }
    public SessionStatus(Model model, long contextTokens, long inputTokens, long outputTokens, long cachedInputTokens, String estimatedCost) {
        this(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,estimatedCost,false);
    }
    public SessionStatus { Objects.requireNonNull(model); Objects.requireNonNull(contextDescription); }
    public static SessionStatus idle(Model model) { return new SessionStatus(model,0,0,0,0,null); }
    public SessionStatus withUsage(long input, long output, long cached) {
        return new SessionStatus(model,contextTokens,input,output,cached,
                TokenUsageFormatter.estimatedCostCny(model.provider(),bounded(input),bounded(output),bounded(cached)),approvalEnabled,contextDescription);
    }
    public SessionStatus withModel(Model model) {
        return new SessionStatus(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,null,approvalEnabled,contextDescription)
                .withUsage(inputTokens,outputTokens,cachedInputTokens);
    }
    public SessionStatus withApproval(boolean enabled) {
        return new SessionStatus(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,estimatedCost,enabled,contextDescription);
    }
    public SessionStatus withContextDescription(String description) {
        return new SessionStatus(model,contextTokens,inputTokens,outputTokens,cachedInputTokens,estimatedCost,approvalEnabled,description);
    }
    private static int bounded(long count) { return (int)Math.min(Integer.MAX_VALUE,Math.max(0,count)); }
}
