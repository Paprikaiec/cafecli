package com.cafecli.harness.context;

import com.cafecli.ai.Context;
import com.cafecli.ai.Model;
import com.cafecli.ai.ToolDefinition;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.StopReason;
import com.cafecli.core.log.AssistantEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.harness.SessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Owner-thread status projection; source usage survives compaction, while occupancy follows the current view. */
public final class SessionStatusTracker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private int runFrom;
    private List<ToolDefinition> tools;

    public SessionStatusTracker(List<ToolDefinition> initialTools) { tools = List.copyOf(initialTools); }
    public void start(List<LogEntry> log) { runFrom = log.size(); }
    public void request(Context context) { tools = context.tools(); }
    public SessionStatus capture(Model model,List<LogEntry> log,List<Message> messages,String systemPrompt) {
        long input = 0, output = 0, cached = 0;
        for (var entry : log.subList(Math.min(runFrom,log.size()),log.size())) {
            if (entry instanceof AssistantEntry assistant) {
                var message = assistant.message();
                if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) continue;
                input += Math.max(0,message.usage().inputTokens());
                output += Math.max(0,message.usage().outputTokens());
                cached += Math.max(0,message.usage().cachedInputTokens());
            }
        }
        long schemas;
        try { schemas = ContextTokens.textTokens(JSON.writeValueAsString(tools)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
        // v1 counted its system message with the same four-token message framing as ordinary messages.
        long occupancy = ContextTokens.estimate(messages)+ContextTokens.textTokens(systemPrompt)+4+schemas;
        return new SessionStatus(model,occupancy,input,output,cached,null).withUsage(input,output,cached)
                .withContextDescription(describe(model,messages,systemPrompt));
    }
    public String describe(Model model,List<Message> messages,String systemPrompt) {
        String cache=!model.supports(Model.Feature.PROMPT_CACHING) ? "none" : switch (model.provider()) {
            case "glm" -> "glm-prompt-cache"; case "deepseek" -> "automatic-prefix-cache";
            case "step" -> "step-prefix-cache"; case "kimi" -> "moonshot-context-cache"; default -> "none";
        };
        var profile=com.cafecli.context.ContextProfile.forExactWindow(model.contextWindow(),model.supports(Model.Feature.PROMPT_CACHING),cache);
        int schema;
        try { schema=(int)ContextTokens.textTokens(JSON.writeValueAsString(tools)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
        return com.cafecli.context.ContextStatusFormatter.format(profile,model.id()+" ("+model.provider()+")",
                new com.cafecli.context.ContextStatusFormatter.Tokens((int)ContextTokens.textTokens(systemPrompt)+4,1),
                new com.cafecli.context.ContextStatusFormatter.Tokens((int)ContextTokens.estimate(messages),messages.size()),schema,
                "上下文策略: " + profile.summaryWithoutShortTermMemory());
    }
}
