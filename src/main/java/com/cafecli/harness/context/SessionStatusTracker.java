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
        return new SessionStatus(model,occupancy,input,output,cached,null).withUsage(input,output,cached);
    }
}
