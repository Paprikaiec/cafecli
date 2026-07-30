package com.cafecli.agent.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准入控制（ADR 0006）的纯策略单测：无线程即可断言深度上限、名额会计、以及「父等待子时释放配额」
 * 的无死锁性质（类比 AgentBudgetTest）。
 */
class AdmissionControlTest {

    @Test
    void enforcesDepthCapHard() {
        AdmissionControl ac = new AdmissionControl(8, 2);
        assertFalse(ac.admitsDepth(0), "深度 0 是主 Agent 自身，不可作为派生深度");
        assertTrue(ac.admitsDepth(1));
        assertTrue(ac.admitsDepth(2));
        assertFalse(ac.admitsDepth(3), "超过 maxDepth 应被拒绝");
        // acquire 在超限深度直接抛、不占名额
        assertThrows(AdmissionControl.AdmissionException.class, () -> ac.acquire(3));
        assertEquals(8, ac.availablePermits(), "被拒绝的派生不应占用名额");
    }

    @Test
    void permitsAccountForRunningTasks() throws InterruptedException {
        AdmissionControl ac = new AdmissionControl(2, 5);
        assertEquals(2, ac.availablePermits());

        ac.acquire(1);
        ac.acquire(1);
        assertEquals(0, ac.availablePermits(), "占满后无可用名额");
        assertFalse(ac.tryAcquire(1), "占满时非阻塞获取失败");

        ac.release();
        assertEquals(1, ac.availablePermits());
        assertTrue(ac.tryAcquire(1), "释放后又可获取");
        assertEquals(0, ac.availablePermits());
    }

    @Test
    void waitReleasesQuotaSoChildNeverDeadlocks() throws InterruptedException {
        // 池子只有 1 个名额：父占用后若不释放，子任务永远拿不到名额 → 等待型死锁。
        AdmissionControl ac = new AdmissionControl(1, 5);
        ac.acquire(1);                                   // 父任务占用唯一名额
        assertEquals(0, ac.availablePermits());

        String childResult = ac.releasingPermitWhile(() -> {
            // 父等待期间释放了名额，子任务因此能拿到 —— 无死锁的关键
            assertEquals(1, ac.availablePermits(), "父等待子时应释放自身名额");
            ac.acquire(2);                               // 子任务（深度 2）占用名额
            assertEquals(0, ac.availablePermits());
            ac.release();                                // 子任务结束
            return "child-ran";
        });

        assertEquals("child-ran", childResult, "子任务在父等待期间成功跑完");
        assertEquals(0, ac.availablePermits(), "等待结束后父重新占用名额");
        ac.release();
        assertEquals(1, ac.availablePermits());
    }

    @Test
    void deepNarrowSpawnTreeCompletesWithoutDeadlockOnSinglePermit() throws InterruptedException {
        // 池子只有 1 个名额、一条深度 5 的链：每层父都释放名额等子 → 全链跑完、无死锁、名额守恒。
        AdmissionControl ac = new AdmissionControl(1, 5);
        ac.acquire(1);
        int leaf = descend(ac, 2, 5);
        assertEquals(5, leaf, "深度链应一路下探到叶子层");
        ac.release();
        assertEquals(1, ac.availablePermits(), "全链结束后名额完全归还");
    }

    private int descend(AdmissionControl ac, int depth, int maxDepth) throws InterruptedException {
        if (depth > maxDepth) {
            return depth - 1;
        }
        return ac.releasingPermitWhile(() -> {
            ac.acquire(depth);
            try {
                return descend(ac, depth + 1, maxDepth);
            } finally {
                ac.release();
            }
        });
    }

    @Test
    void fromPropertiesUsesDefaultsThenSystemProperties() {
        AdmissionControl def = AdmissionControl.fromProperties();
        assertEquals(AdmissionControl.DEFAULT_MAX_CONCURRENCY, def.maxConcurrency());
        assertEquals(AdmissionControl.DEFAULT_MAX_DEPTH, def.maxDepth());

        System.setProperty("paicli.task.max-concurrency", "3");
        System.setProperty("paicli.task.max-depth", "4");
        try {
            AdmissionControl configured = AdmissionControl.fromProperties();
            assertEquals(3, configured.maxConcurrency());
            assertEquals(4, configured.maxDepth());
        } finally {
            System.clearProperty("paicli.task.max-concurrency");
            System.clearProperty("paicli.task.max-depth");
        }
    }
}
