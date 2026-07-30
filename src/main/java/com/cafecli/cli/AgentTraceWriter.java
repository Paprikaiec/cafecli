package com.cafecli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.agent.Agent;
import com.cafecli.llm.LlmClient.Message;
import com.cafecli.llm.LlmClient.ToolCall;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次 headless run 的 conversationHistory + 元数据序列化成 NDJSON。
 * 每条消息一行，末尾追加一行 result 摘要；事件语义对齐 Claude Code stream-json，
 * 便于 Part 2 的 normalizer 统一两臂日志。
 */
public final class AgentTraceWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTraceWriter() {}

    public static void write(Writer out, List<Message> history, Agent.TokenUsage usage,
                             String provider, String model, long wallMs) throws IOException {
        for (Message m : history) {
            out.write(MAPPER.writeValueAsString(toEvent(m)));
            out.write("\n");
        }
        out.write(MAPPER.writeValueAsString(summary(history, usage, provider, model, wallMs)));
        out.write("\n");
    }

    private static Map<String, Object> toEvent(Message m) {
        Map<String, Object> ev = new LinkedHashMap<>();
        switch (m.role()) {
            case "system" -> { ev.put("type", "system"); ev.put("text", m.content()); }
            case "user" -> {
                ev.put("type", "user");
                ev.put("text", m.content());
                if (m.hasContentParts() && m.hasImageContent()) {
                    ev.put("image_parts", m.imagePartCount());
                }
            }
            case "tool" -> {
                ev.put("type", "tool_result");
                ev.put("tool_call_id", m.toolCallId());
                ev.put("content", m.content());
            }
            case "assistant" -> {
                ev.put("type", "assistant");
                if (m.content() != null && !m.content().isBlank()) {
                    ev.put("text", m.content());
                }
                if (m.reasoningContent() != null && !m.reasoningContent().isBlank()) {
                    ev.put("reasoning", m.reasoningContent());
                }
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("id", tc.id());
                        c.put("name", tc.function().name());
                        c.put("arguments", tc.function().arguments());
                        calls.add(c);
                    }
                    ev.put("tool_calls", calls);
                }
            }
            default -> { ev.put("type", m.role()); ev.put("text", m.content()); }
        }
        return ev;
    }

    private static Map<String, Object> summary(List<Message> history, Agent.TokenUsage usage,
                                               String provider, String model, long wallMs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "result");
        s.put("provider", provider);
        s.put("model", model);
        s.put("wall_ms", wallMs);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("in", usage.inputTokens());
        tokens.put("out", usage.outputTokens());
        tokens.put("cache", usage.cachedInputTokens());
        s.put("tokens", tokens);
        long turns = history.stream().filter(m -> "assistant".equals(m.role())).count();
        s.put("turns", turns);
        return s;
    }
}
