package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL 审批泵（ADR 0005）的可测接缝：双线程 + latch 证明调用方 park 直到主线程投递决定；请求方标注透传；
 * approve-all 会话级全局跨 agent；ESC reject 正 park 的审批；HITL 关闭直接放行。无终端。
 */
class ApprovalPumpTest {

    private static ApprovalRequest req(String tool, String args, String caller) {
        return ApprovalRequest.of(tool, args, null, caller, null);
    }

    @Test
    void workerParksUntilMainResolves() throws Exception {
        ApprovalPump pump = new ApprovalPump();
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<ApprovalResult> got = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            requested.countDown();
            got.set(pump.requestApproval(req("edit_file", "{}", "主 Agent")));
        });
        worker.start();

        assertTrue(requested.await(2, TimeUnit.SECONDS));
        ApprovalPump.Pending pending = pump.poll(Duration.ofSeconds(2));
        assertTrue(pending != null, "主线程应能取到待审批请求");
        assertNull(got.get(), "resolve 之前 worker 应仍 park 着（未拿到结果）");

        pump.resolve(pending, ApprovalResult.approve());
        worker.join(2000);
        assertTrue(got.get() != null && got.get().isApproved(), "resolve 后 worker 应 unpark 拿到批准");
    }

    @Test
    void requesterLabelIsCarriedOnTheRequest() throws Exception {
        ApprovalPump pump = new ApprovalPump();
        Thread worker = new Thread(() -> pump.requestApproval(req("edit_file", "{}", "task_3 · 调研 Loom")));
        worker.start();

        ApprovalPump.Pending pending = pump.poll(Duration.ofSeconds(2));
        assertTrue(pending != null);
        assertEquals("task_3 · 调研 Loom", pending.request().callerContext(),
                "每条审批应标注请求方（主 Agent / task_N），供串行展示时区分");
        pump.resolve(pending, ApprovalResult.reject("test cleanup"));
        worker.join(2000);
    }

    @Test
    void approveAllShortCircuitsAcrossAgents() throws Exception {
        ApprovalPump pump = new ApprovalPump();

        // agent A 的请求 → 主线程「全部批准」
        Thread a = new Thread(() -> pump.requestApproval(req("edit_file", "{}", "主 Agent")));
        a.start();
        ApprovalPump.Pending p = pump.poll(Duration.ofSeconds(2));
        assertTrue(p != null);
        pump.resolve(p, ApprovalResult.approveAll());
        a.join(2000);

        // agent B（另一个任务）对同工具的请求 → 应直接放行、不入队（会话级全局）
        ApprovalResult bResult = pump.requestApproval(req("edit_file", "{}", "task_9 · 实现"));
        assertTrue(bResult.isApproved(), "approve-all 应对本会话所有 agent 生效");
        assertFalse(pump.hasPending(), "已 approve-all 的工具不应再入队");
        assertTrue(pump.isApprovedAllByTool("edit_file"));
    }

    @Test
    void escRejectsParkedApproval() throws Exception {
        ApprovalPump pump = new ApprovalPump();
        AtomicReference<ApprovalResult> got = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            requested.countDown();
            got.set(pump.requestApproval(req("execute_command", "{}", "主 Agent")));
        });
        worker.start();
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        // 等它确实入队
        ApprovalPump.Pending ignored = null;
        for (int i = 0; i < 50 && !pump.hasPending(); i++) {
            Thread.sleep(10);
        }

        int rejected = pump.rejectAllPending("ESC 取消");
        worker.join(2000);

        assertEquals(1, rejected, "ESC 应 reject 掉正 park 的审批");
        assertTrue(got.get() != null && got.get().isRejected(), "被 ESC reject 后 worker 应 unpark 收到拒绝");
    }

    @Test
    void disabledHitlShortCircuitsToApprove() {
        ApprovalPump pump = new ApprovalPump();
        pump.setEnabled(false);
        ApprovalResult result = pump.requestApproval(req("execute_command", "{}", "主 Agent"));
        assertTrue(result.isApproved(), "HITL 关闭时直接放行，不入队、不 park");
        assertFalse(pump.hasPending());
    }

    @Test
    void pendingNotifierFiresOnEnqueueForBackgroundAnnounce() throws Exception {
        ApprovalPump pump = new ApprovalPump();
        AtomicReference<String> announced = new AtomicReference<>();
        pump.setPendingNotifier(r -> announced.set(r.callerContext()));

        Thread worker = new Thread(() -> pump.requestApproval(req("edit_file", "{}", "task_5 · 后台调研")));
        worker.start();
        ApprovalPump.Pending p = pump.poll(Duration.ofSeconds(2));
        assertTrue(p != null);
        assertEquals("task_5 · 后台调研", announced.get(),
                "入队应触发通知（Plan A：Main 据此 printAbove 告知后台任务在等审批）");
        pump.resolve(p, ApprovalResult.reject("test cleanup"));
        worker.join(2000);
    }
}
