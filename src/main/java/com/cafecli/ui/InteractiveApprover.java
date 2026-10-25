package com.cafecli.ui;

import com.cafecli.ai.CancellationToken;
import com.cafecli.harness.permission.ApprovalRequest;
import com.cafecli.harness.permission.ApprovalResult;
import com.cafecli.harness.permission.Approver;
import com.cafecli.render.Renderer;
import com.cafecli.render.inline.InlineRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Input-box approval rendezvous; session permission policy owns all remembered grants. */
public final class InteractiveApprover implements Approver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Pending(com.cafecli.render.ApprovalRequest view, CompletableFuture<ApprovalResult> reply) { }
    private Renderer renderer;
    private Consumer<Runnable> enqueue;
    private Runnable refresh;
    private volatile Pending pending;

    void bind(Renderer renderer, Consumer<Runnable> enqueue, Runnable refresh) {
        if (this.renderer != null) throw new IllegalStateException("approver already bound");
        this.renderer = Objects.requireNonNull(renderer);
        this.enqueue = Objects.requireNonNull(enqueue);
        this.refresh = Objects.requireNonNull(refresh);
    }
    boolean pending() { var current = pending; return current != null && !current.reply().isDone(); }

    @Override public ApprovalResult approve(ApprovalRequest request, CancellationToken token) throws InterruptedException {
        if (enqueue == null) throw new IllegalStateException("interactive approver has no input box");
        var view = com.cafecli.render.ApprovalRequest.of(request.call().name(),request.call().arguments(),null,
                "main".equals(request.agentId()) ? "主 Agent" : request.agentId(), request.sensitiveNotice());
        var current = new Pending(view,new CompletableFuture<>());
        try (var ignored = token.onCancel(() -> current.reply().complete(ApprovalResult.reject("用户取消了当前回合")))) {
            enqueue.accept(() -> {
                if (current.reply().isDone()) return;
                // Notify before making the request answerable: the transcript must release the input box first.
                renderer.stream().println("⏸ " + view.callerContext()
                        + " 需要审批 " + view.toolName() + "（回车处理）");
                if (renderer instanceof InlineRenderer inline) inline.onApprovalPending();
                renderer.showApproval(view,"");
                pending = current;
                refresh.run();
            });
            try { return current.reply().get(); }
            catch (ExecutionException failure) { throw new IllegalStateException("approval failed",failure.getCause()); }
        } finally {
            // This precedes the next request's presentation on the same UI queue.
            enqueue.accept(() -> {
                if (pending == current) {
                    pending = null;
                    renderer.dismissApproval();
                    refresh.run();
                }
            });
        }
    }

    /** True means this submission belongs to the pending approval, including empty/invalid replies. */
    boolean answer(String input) {
        Pending current = pending;
        if (current == null || current.reply().isDone()) return false;
        String line = input == null ? "" : input.trim();
        String head = line.isEmpty() ? "" : line.split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        String rest = line.length() > head.length() ? line.substring(head.length()).trim() : "";
        ApprovalResult result = null;
        String notice = "";
        switch (head) {
            case "" -> { }
            case "y", "yes" -> result = ApprovalResult.approve();
            case "n", "no" -> result = ApprovalResult.reject(rest);
            case "s", "skip" -> result = ApprovalResult.skip();
            case "a", "all" -> {
                if (current.view().sensitiveNotice() != null && !current.view().sensitiveNotice().isBlank()) {
                    notice = "敏感操作不能整体批准，请用 y / n / s / m";
                } else {
                    String serverName = current.view().mcpServerName();
                    boolean server = serverName != null && !serverName.isBlank() && (rest.equals("server") || rest.equals("s"));
                    result = server ? ApprovalResult.approveAllByServer() : ApprovalResult.approveAll();
                }
            }
            case "m", "modify" -> {
                if (rest.isEmpty()) notice = "m 之后请给出替换用的 JSON 参数";
                else {
                    try { JSON.readTree(rest); result = ApprovalResult.modify(rest); }
                    catch (Exception failure) { notice = "参数不是合法 JSON：" + failure.getMessage(); }
                }
            }
            default -> notice = "无法识别的回答「" + line + "」；请用 y / n / a（或 s / m）";
        }
        if (result != null) current.reply().complete(result);
        else {
            String nextNotice = notice;
            enqueue.accept(() -> {
                if (pending == current && !current.reply().isDone()) renderer.showApproval(current.view(),nextNotice);
            });
        }
        return true;
    }
}
