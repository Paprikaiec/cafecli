package com.paicli.agent;

import com.paicli.agent.task.AdmissionControl;
import com.paicli.agent.task.AgentSpawner;
import com.paicli.agent.task.BackgroundCompletionSink;
import com.paicli.agent.task.IdleTaskReaper;
import com.paicli.agent.task.TaskRegistry;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 前台同步派生子 Agent（任务）的实现：在派生它的那一轮内把子 Agent 跑到收敛，
 * 只把最终结果文本（result-only）交回 {@code spawn_agent} 工具。
 *
 * <p>隔离（ADR 0007「默认隔离」）：每个子任务用一个全新的、独立历史与独立预算的
 * {@link Agent}（标准 Agent，而非 SubAgent）；子 Agent 拿到自己的 {@code ToolRegistry}，
 * 且其中不注入 spawner，因此本期子任务无法再派生。
 *
 * <p>运行时（ADR 0006）：每个任务（及其阻塞等待）跑在<b>虚拟线程</b>上（要求 JRE 21）；并发由
 * {@link AdmissionControl} 兜底——深度上限硬拦截无限递归，并发名额把峰值运行任务约束在上限内，
 * 父等待子时释放自身名额避免等待型死锁。
 *
 * <p>result-only：子 Agent 装一个丢弃所有输出的 {@link Renderer}，它的流式 content / 工具块
 * 都写进黑洞，不会打到主 transcript，也不会进入主 Agent 的对话历史。返回给工具的只有
 * {@link Agent#run(String)} 的最终结果文本。
 */
public final class ForegroundAgentSpawner implements AgentSpawner {

    private static final Logger log = LoggerFactory.getLogger(ForegroundAgentSpawner.class);

    /** 主 Agent（深度 0）派生的前台任务记为深度 1。子任务再派生（深度 2+）的传播留待后续切片。 */
    private static final int FOREGROUND_TASK_DEPTH = 1;

    private final LlmClient llmClient;
    private final TaskRegistry taskRegistry;
    private final ToolRegistry parentRegistry;
    private final AdmissionControl admission;
    private volatile BackgroundCompletionSink backgroundSink;
    // 完成的任务转为空闲-可续派：保留其子 Agent（含对话历史）以备 send_message 续派 / task_output 检视；
    // 由 IdleTaskReaper（LRU，#12）约束保留数，超上限的最旧空闲任务被驱逐（续派/检视返回「已过期」）。
    private final Map<String, Agent> retainedAgents = new ConcurrentHashMap<>();
    private volatile IdleTaskReaper reaper = IdleTaskReaper.fromProperties();

    public ForegroundAgentSpawner(LlmClient llmClient, TaskRegistry taskRegistry) {
        this(llmClient, taskRegistry, null);
    }

    /**
     * 注入派生者（主 Agent）的 {@code parentRegistry}：每个子任务的工具表经
     * {@link ToolRegistry#createChildRegistry()} 派生——昂贵服务（MCP/LSP 进程/网络/审计/
     * 长期记忆/快照工作区/项目路径）共享，会话相邻状态（Skill 缓冲、模型选择、LSP pending
     * 缓冲）每任务私有（ADR 0007）。{@code parentRegistry==null} 时退回为子任务建一份默认
     * registry（#01 行为，保留给纯工具测试）。准入控制取系统属性默认。
     */
    public ForegroundAgentSpawner(LlmClient llmClient, TaskRegistry taskRegistry, ToolRegistry parentRegistry) {
        this(llmClient, taskRegistry, parentRegistry, AdmissionControl.fromProperties());
    }

    /** 注入 {@link AdmissionControl}（并发上限/深度上限）；用于配置或测试约束并行度。 */
    public ForegroundAgentSpawner(LlmClient llmClient, TaskRegistry taskRegistry,
                                  ToolRegistry parentRegistry, AdmissionControl admission) {
        this.llmClient = llmClient;
        this.taskRegistry = taskRegistry;
        this.parentRegistry = parentRegistry;
        this.admission = admission == null ? AdmissionControl.fromProperties() : admission;
    }

    /** 注入后台任务完成回调（{@code AgentSession::onBackgroundTaskDone}）：未注入则后台派生退化为前台同步。 */
    public void setBackgroundCompletionSink(BackgroundCompletionSink sink) {
        this.backgroundSink = sink;
    }

    /** 注入空闲任务 LRU 回收策略（保留上限）：默认取系统属性；用于配置或测试。 */
    public void setIdleTaskReaper(IdleTaskReaper reaper) {
        if (reaper != null) {
            this.reaper = reaper;
        }
    }

    @Override
    public String spawn(String description, String prompt) {
        String taskId = taskRegistry.register(description);
        log.info("Spawning foreground task {} (depth {}): {}", taskId, FOREGROUND_TASK_DEPTH, description);
        // 前台：在虚拟线程上跑（含准入名额），调用方等其完成拿 result-only。
        return runOnVirtualThreadAndJoin(taskId, () -> admittedRun(taskId, () -> buildAndRunChild(taskId, prompt)));
    }

    @Override
    public String spawnBackground(String description, String prompt) {
        if (backgroundSink == null) {
            // 未接事件循环（headless / 纯前台）：退化为前台同步执行，保持可预测。
            return spawn(description, prompt);
        }
        String taskId = taskRegistry.register(description);
        log.info("Spawning background task {} (depth {}): {}", taskId, FOREGROUND_TASK_DEPTH, description);
        // 后台：在虚拟线程上跑（含准入名额），本轮不阻塞地立即返回 task_id；完成时把通知投递回事件循环。
        Thread.ofVirtual().name("paicli-bgtask-" + taskId).start(() -> {
            String notification = admittedRun(taskId, () -> buildAndRunChild(taskId, prompt));
            backgroundSink.onTaskDone(taskId, notification);
        });
        return statusLine(taskId, "background")
                + " 已在后台启动，完成后会把结论推送回来（task_id=" + taskId + "）";
    }

    @Override
    public String continueTask(String taskId, String message) {
        Agent child = retainedAgents.get(taskId);
        if (child == null) {
            return expired(taskId);
        }
        log.info("Continuing task {}", taskId);
        // 在保留的对话历史上跑新回合：Agent.run 追加新 user 消息到既有历史，复用上下文。
        return runOnVirtualThreadAndJoin(taskId, () -> admittedRun(taskId, () -> runAndRecord(taskId, child, message)));
    }

    /** 构建子 Agent（隔离派生 / 默认 registry）并跑一个回合，返回 result-only。 */
    private String buildAndRunChild(String taskId, String prompt) {
        Agent child = parentRegistry == null
                ? new Agent(llmClient)
                : new Agent(llmClient, parentRegistry.createChildRegistry());
        child.setRenderer(silentRenderer());
        return runAndRecord(taskId, child, prompt);
    }

    /**
     * 占用一个并发名额跑任务体、结束后归还名额（ADR 0006）。深度超限即拒绝派生（不占名额）。
     * 内联执行——调用方负责把它放到（前台 join 的 / 后台游离的）虚拟线程上。
     */
    private String admittedRun(String taskId, TaskBody body) {
        try {
            admission.acquire(FOREGROUND_TASK_DEPTH);
        } catch (AdmissionControl.AdmissionException e) {
            taskRegistry.markFailed(taskId, e.getMessage());
            log.warn("Task {} rejected by admission: {}", taskId, e.getMessage());
            return statusLine(taskId, "rejected") + "\n\n" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskRegistry.markFailed(taskId, "派生等待名额时被中断");
            return statusLine(taskId, "failed") + "\n\n派生等待名额时被中断";
        }
        try {
            return body.run();
        } catch (Throwable t) {
            String reason = "子任务执行失败: " + t.getMessage();
            taskRegistry.markFailed(taskId, reason);
            log.warn("Task {} threw", taskId, t);
            return statusLine(taskId, "failed") + "\n\n" + reason;
        } finally {
            admission.release();
        }
    }

    /** 在一个虚拟线程上跑任务体并等其完成（前台派生用）。 */
    private String runOnVirtualThreadAndJoin(String taskId, TaskBody body) {
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread vt = Thread.ofVirtual().name("paicli-task-" + taskId).start(() -> {
            try {
                out.set(body.run());
            } catch (Throwable t) {
                err.set(t);
            }
        });
        try {
            vt.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vt.interrupt();
            String reason = "子任务等待被中断";
            taskRegistry.markFailed(taskId, reason);
            return statusLine(taskId, "failed") + "\n\n" + reason;
        }
        Throwable failure = err.get();
        if (failure != null) {
            String reason = "子任务执行失败: " + failure.getMessage();
            taskRegistry.markFailed(taskId, reason);
            log.warn("Task {} threw on virtual thread", taskId, failure);
            return statusLine(taskId, "failed") + "\n\n" + reason;
        }
        return out.get();
    }

    @FunctionalInterface
    private interface TaskBody {
        String run();
    }

    @Override
    public String taskOutput(String taskId) {
        Agent child = retainedAgents.get(taskId);
        if (child == null) {
            return expired(taskId);
        }
        return renderTrajectory(taskId, child);
    }

    /**
     * 跑一个回合（首派或续派共用），把结果按 DONE/FAILED 登记，并保留子 Agent 转入空闲-可续派，
     * 返回 result-only 文本。
     *
     * <p>Agent.run 在「已流式输出」时返回空串（正文已打到被丢弃的渲染器），真正最终文本留在
     * 子对话历史里 → 以历史最后一条 assistant 文本为准，兜底用 run() 返回值，保证 result-only 不丢。
     * Agent.run 把子 Agent 的 LLM 异常吞掉并返回 "❌ ..." 文本 → 据此判定失败，让登记表能区分
     * DONE / FAILED，而非把失败伪装成正常结果。
     */
    private String runAndRecord(String taskId, Agent child, String input) {
        String runResult = child.run(input == null ? "" : input);
        String result = resolveFinalText(child, runResult);
        retainedAgents.put(taskId, child);  // 完成即转空闲-可续派，保留历史供续派 / 检视
        // LRU 回收：超过保留上限的最旧空闲任务被驱逐——其子 Agent 移出，续派/检视将返回「已过期」。
        for (String reapedId : reaper.onIdle(taskId, preview(result))) {
            retainedAgents.remove(reapedId);
        }
        if (looksLikeFailure(result)) {
            taskRegistry.markFailed(taskId, result);
            log.warn("Task {} reported failure: {}", taskId, preview(result));
            return statusLine(taskId, "failed") + "\n\n" + result;
        }
        taskRegistry.markDone(taskId, result);
        log.info("Task {} done", taskId);
        return statusLine(taskId, "done") + "\n\n" + result;
    }

    private static String expired(String taskId) {
        return "任务 " + taskId + " 已过期或不存在，请用 spawn_agent 重开。";
    }

    /** 把子任务的完整对话历史渲染成可读轨迹（含中间工具调用，非 result-only）；跳过 system prompt。 */
    private static String renderTrajectory(String taskId, Agent child) {
        StringBuilder sb = new StringBuilder("[").append(taskId).append(" · 完整轨迹]");
        for (LlmClient.Message m : child.getConversationHistory()) {
            String role = m.role() == null ? "?" : m.role();
            if ("system".equals(role)) {
                continue;
            }
            String content = m.content() == null ? "" : m.content();
            sb.append('\n').append(role).append(": ").append(content);
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  ⚙ ").append(tc.function().name())
                            .append(' ').append(tc.function().arguments());
                }
            }
        }
        return sb.toString();
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
