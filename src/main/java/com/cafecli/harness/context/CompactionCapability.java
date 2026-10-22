package com.cafecli.harness.context;

import com.cafecli.ai.CancellationToken;
import com.cafecli.ai.Context;
import com.cafecli.ai.Provider;
import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.StreamOptions;
import com.cafecli.ai.message.AssistantMessage;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.Message;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.context.ContextProfile;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One append-only compaction path, shared by boundary checks and overflow recovery. */
public final class CompactionCapability implements Capability {
    private static final int RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;
    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;
    private final Provider provider;

    public CompactionCapability(Provider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override public void attachAgent(AgentScope scope) {
        scope.messageKind(Summary.KIND);
        scope.sourceProjectionStage(SummaryProjection::apply);
        scope.iterationBoundaryWriter((log, token) -> {
            long threshold = (long) Math.floor(scope.model().contextWindow()
                    * ContextProfile.DEFAULT_COMPRESSION_TRIGGER_RATIO);
            return ContextTokens.estimate(scope.projectMessages(log)) + ContextTokens.textTokens(scope.systemPrompt()) + 4 >= threshold
                    ? compact(scope, log, token) : List.of();
        });
        scope.contextOverflowWriter((log, token) -> compact(scope, log, token));
    }

    private List<CustomEntry> compact(AgentScope scope, List<LogEntry> log, CancellationToken token) {
        if (token.isCancelled()) return List.of();
        List<Integer> users = new ArrayList<>();
        int covered = 0;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i) instanceof UserEntry) users.add(i);
            if (log.get(i) instanceof Summary summary) covered = Math.max(covered, summary.toExclusive());
        }
        if (users.size() <= RETAIN_RECENT_ROUNDS) return List.of();
        int split = users.get(users.size() - RETAIN_RECENT_ROUNDS);
        if (split <= covered) return List.of();
        // The split is an actual user-round boundary, never the tagged presentation of a custom entry.
        // Reattach prior summaries to the prefix view: their physical append position is after the range they cover.
        List<LogEntry> prefix = new ArrayList<>(log.subList(0, split));
        for (int i = split; i < log.size(); i++) {
            if (log.get(i) instanceof Summary summary && summary.toExclusive() <= split) prefix.add(summary);
        }
        StringBuilder text = new StringBuilder();
        for (Message message : scope.projectMessages(prefix)) {
            switch (message) {
                case UserMessage user -> text.append("USER: ").append(content(user.content()));
                case ToolResultMessage result -> text.append("TOOL: ").append(content(result.content()));
                case AssistantMessage assistant -> {
                    text.append("ASSISTANT: ").append(assistant.text());
                    for (var call : assistant.toolCalls()) text.append("\n  TOOL_CALL ")
                            .append(call.name()).append(": ").append(call.arguments());
                }
            }
            text.append("\n\n");
            if (text.length() > MAX_SUMMARY_INPUT_CHARS) {
                text.setLength(MAX_SUMMARY_INPUT_CHARS);
                text.append("...(超长内容已截断)\n");
                break;
            }
        }
        Context request = new Context("你是一个对话摘要助手，只输出摘要本身，不输出元描述。",
                List.of(UserMessage.text(SUMMARY_PROMPT.formatted(text))), List.of());
        String summary = null;
        try (var stream = provider.stream(scope.model(), request, new StreamOptions(token))) {
            for (StreamEvent event : stream) {
                if (event instanceof StreamEvent.Done done && done.message().toolCalls().isEmpty()) {
                    summary = done.message().text();
                }
                if (event instanceof StreamEvent.Failed) return List.of();
            }
        }
        if (token.isCancelled() || summary == null || summary.isBlank()) return List.of();
        Summary entry = new Summary(0, split, summary.trim());
        scope.emit(new AgentEvent.Compaction(new AgentEvent.Origin(scope.agentId()), 0, split));
        return List.of(entry);
    }

    private static String content(List<UserContent> content) {
        StringBuilder text = new StringBuilder();
        for (var block : content) if (block instanceof TextBlock part) text.append(part.text());
        return text.toString();
    }
}
