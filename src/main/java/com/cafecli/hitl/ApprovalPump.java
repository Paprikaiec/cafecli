package com.cafecli.hitl;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * HITL 审批泵（ADR 0005）：把「请求」与「裁决」解耦到两个线程，使**读终端只在主线程发生**，根治事件循环下
 * 「执行器线程读终端 vs 主线程 LineReader 抢终端」的死锁。
 *
 * <p>worker 线程（执行器 / 任务虚拟线程）调用 {@link #requestApproval}：若已 approve-all / HITL 关闭则直接放行，
 * 否则把请求入队并 <b>park 在一个 future</b> 上；主线程（输入泵）{@link #poll} 取出、渲染审批、{@link #resolve}
 * 投递决定 → worker unpark 拿到结果。审批串行（一次一个），approve-all 粘性<b>会话级全局</b>（对所有 agent 生效）。
 *
 * <p>取消（#09 耦合，对标 CC 的 resolveIfAborted）：ESC 取消当前前台回合 + 附着任务时，应 {@link #rejectPending}
 * 把这些正 park 的审批直接 reject，worker 立即 unpark 收尾，不会一直卡在审批上。
 *
 * <p>对标 Claude Code 的 {@code CanUseToolFn}：工具 await 一个由 UI resolve 的 Promise；规则命中即直接 allow；
 * 弹窗串行；abort 时正 park 的权限 resolve 成取消。本类是其 Java/JLine 对等实现。
 *
 * <p>{@link #requestApproval} 仍是 {@link HitlHandler} 契约里的同步阻塞方法——调用方（{@code HitlToolRegistry}）零改动。
 */
public final class ApprovalPump implements HitlHandler {

    /** 一条待裁决的审批：请求 + 调用方 park 在其上的 future。 */
    public record Pending(ApprovalRequest request, CompletableFuture<ApprovalResult> future) {}

    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Set<Pending> outstanding = ConcurrentHashMap.newKeySet();
    // approve-all 粘性：会话级全局，对所有 agent（主 + 任务）生效。
    private final Set<String> approvedAllTools = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedAllServers = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;
    // 入队通知（Plan A）：后台任务审批入队时由 Main 经 printAbove 告知用户「⏸ task_N 需审批，回车处理」。
    private volatile Consumer<ApprovalRequest> pendingNotifier = r -> {};

    public void setPendingNotifier(Consumer<ApprovalRequest> notifier) {
        this.pendingNotifier = notifier == null ? r -> {} : notifier;
    }

    // ---- worker 侧（park）----

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }
        String server = ApprovalPolicy.mcpServerName(request.toolName());
        if (isApprovedAllByTool(request.toolName()) || isApprovedAllByServer(server)) {
            return ApprovalResult.approve();   // 会话级全局放行，对所有 agent
        }
        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        Pending pending = new Pending(request, future);
        outstanding.add(pending);
        queue.add(pending);
        try {
            pendingNotifier.accept(request);
        } catch (RuntimeException ignored) {
            // 通知失败不影响审批主路径
        }
        try {
            ApprovalResult result = future.get();   // ← park 直到主线程 resolve（或 ESC reject）
            if (result.isApprovedAllForTool()) {
                approvedAllTools.add(request.toolName());
            }
            if (result.isApprovedAllForServer() && server != null) {
                approvedAllServers.add(server);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalResult.reject("审批等待被中断");
        } catch (ExecutionException e) {
            return ApprovalResult.reject("审批失败: " + e.getMessage());
        } finally {
            outstanding.remove(pending);
            queue.remove(pending);
        }
    }

    // ---- main 泵侧（render + resolve）----

    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /** 取下一个待审批请求（阻塞至超时）；返回 null 表示当前无待审批。 */
    public Pending poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 主线程渲染回决定后 resolve；worker unpark 拿到结果。 */
    public void resolve(Pending pending, ApprovalResult result) {
        if (pending != null) {
            pending.future().complete(result == null ? ApprovalResult.reject("空决定") : result);
        }
    }

    /**
     * 把匹配的正 park 审批直接 reject（ESC 取消用，#09 耦合）：worker 立即 unpark 收尾。
     * 返回被 reject 的条数。
     */
    public int rejectPending(Predicate<ApprovalRequest> match, String reason) {
        int rejected = 0;
        for (Pending p : outstanding) {
            if (match.test(p.request()) && !p.future().isDone()) {
                p.future().complete(ApprovalResult.reject(reason));
                queue.remove(p);
                rejected++;
            }
        }
        return rejected;
    }

    public int rejectAllPending(String reason) {
        return rejectPending(r -> true, reason);
    }

    // ---- HitlHandler 契约 ----

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return toolName != null && approvedAllTools.contains(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return serverName != null && approvedAllServers.contains(serverName);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void clearApprovedAll() {
        approvedAllTools.clear();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        if (serverName != null) {
            approvedAllServers.remove(serverName);
        }
    }
}
