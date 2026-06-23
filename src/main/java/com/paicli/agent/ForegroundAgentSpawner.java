package com.paicli.agent;

import com.paicli.agent.task.AgentSpawner;
import com.paicli.agent.task.TaskRegistry;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 前台同步派生子 Agent（任务）的实现：在派生它的那一轮内把子 Agent 跑到收敛，
 * 只把最终结果文本（result-only）交回 {@code spawn_agent} 工具。
 *
 * <p>隔离（ADR 0007「默认隔离」）：每个子任务用一个全新的、独立历史与独立预算的
 * {@link Agent}（标准 Agent，而非 SubAgent）；子 Agent 拿到自己的 {@code ToolRegistry}，
 * 且其中不注入 spawner，因此本期子任务无法再派生（递归留给 #06 深度限制）。
 *
 * <p>result-only：子 Agent 装一个丢弃所有输出的 {@link Renderer}，它的流式 content / 工具块
 * 都写进黑洞，不会打到主 transcript，也不会进入主 Agent 的对话历史。返回给工具的只有
 * {@link Agent#run(String)} 的最终结果文本。
 */
public final class ForegroundAgentSpawner implements AgentSpawner {

    private static final Logger log = LoggerFactory.getLogger(ForegroundAgentSpawner.class);

    private final LlmClient llmClient;
    private final TaskRegistry taskRegistry;

    public ForegroundAgentSpawner(LlmClient llmClient, TaskRegistry taskRegistry) {
        this.llmClient = llmClient;
        this.taskRegistry = taskRegistry;
    }

    @Override
    public String spawn(String description, String prompt) {
        String taskId = taskRegistry.register(description);
        log.info("Spawning foreground task {}: {}", taskId, description);

        try {
            Agent child = new Agent(llmClient);
            child.setRenderer(silentRenderer());
            String runResult = child.run(prompt == null ? "" : prompt);

            // Agent.run 在「已流式输出」时返回空串（正文已经打到（被丢弃的）渲染器），
            // 真正的最终文本留在子对话历史里。这里以历史里最后一条 assistant 文本为准，
            // 兜底再用 run() 的返回值，保证 result-only 文本不丢。
            String result = resolveFinalText(child, runResult);

            // Agent.run 把子 Agent 的 LLM 异常吞掉并返回 "❌ ..." 文本，这里据此判定失败，
            // 让登记表能区分 DONE / FAILED，而不是把失败伪装成正常结果。
            if (looksLikeFailure(result)) {
                taskRegistry.markFailed(taskId, result);
                log.warn("Foreground task {} reported failure: {}", taskId, preview(result));
                return statusLine(taskId, "failed") + "\n\n" + result;
            }

            taskRegistry.markDone(taskId, result);
            log.info("Foreground task {} done", taskId);
            return statusLine(taskId, "done") + "\n\n" + result;
        } catch (Exception e) {
            String reason = "子任务执行失败: " + e.getMessage();
            taskRegistry.markFailed(taskId, reason);
            log.warn("Foreground task {} threw", taskId, e);
            return statusLine(taskId, "failed") + "\n\n" + reason;
        }
    }

    /**
     * 解析子 Agent 的最终结果文本。{@link Agent#run(String)} 在已流式输出时返回空串，
     * 此时回到子对话历史取最后一条非空 assistant 文本；失败（run 返回 "❌ …"）时直接用返回值。
     */
    private static String resolveFinalText(Agent child, String runResult) {
        if (looksLikeFailure(runResult)) {
            return runResult;
        }
        if (runResult != null && !runResult.isBlank()) {
            return runResult;
        }
        String lastAssistant = lastAssistantText(child);
        return lastAssistant != null ? lastAssistant : (runResult == null ? "" : runResult);
    }

    private static String lastAssistantText(Agent child) {
        List<LlmClient.Message> history = child.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            LlmClient.Message message = history.get(i);
            if ("assistant".equals(message.role())
                    && message.content() != null && !message.content().isBlank()
                    && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                return message.content();
            }
        }
        return null;
    }

    private static boolean looksLikeFailure(String result) {
        if (result == null) {
            return true;
        }
        String trimmed = result.stripLeading();
        return trimmed.startsWith("❌");
    }

    private static String statusLine(String taskId, String status) {
        return "[" + taskId + " · " + status + "]";
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "..." : oneLine;
    }

    /** 丢弃所有输出的渲染器：子 Agent 的流式内容与工具块都不打到主 transcript。 */
    private static Renderer silentRenderer() {
        return new Renderer() {
            private final PrintStream discard =
                    new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);

            @Override public void start() {}
            @Override public void close() {}
            @Override public PrintStream stream() { return discard; }
            @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {}
            @Override public void appendDiff(String filePath, String before, String after) {}
            @Override public void updateStatus(StatusInfo status) {}
            @Override public ApprovalResult promptApproval(ApprovalRequest request) {
                // 子任务前台跑、无人值守 → 默认拒绝危险操作，不阻塞主轮次。
                return ApprovalResult.reject("子任务不支持交互式审批");
            }
            @Override public int openPalette(String title, List<String> items) { return -1; }
        };
    }
}
