package com.paicli.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 取消语义（ADR 0005）的纯策略单测：给定注册表状态，断言 ESC 恰好取消「当前前台回合 + 附着任务」、
 * 游离任务存活；/task cancel 精确取消单个任务。无线程即可断言决策。
 */
class CancellationRegistryTest {

    @Test
    void escCancelsForegroundTurnAndAttachedTasksButNotDetached() {
        CancellationRegistry registry = new CancellationRegistry();
        CancellationToken turn = registry.beginForegroundTurn();
        CancellationToken attachedA = registry.registerTask("task_1", true);
        CancellationToken attachedB = registry.registerTask("task_2", true);
        CancellationToken detached = registry.registerTask("task_3", false);

        int cancelledTasks = registry.cancelForegroundAndAttached();

        assertEquals(2, cancelledTasks, "应恰好取消两个附着任务");
        assertTrue(turn.isCancelled(), "当前前台回合应被取消");
        assertTrue(attachedA.isCancelled(), "附着任务应被取消");
        assertTrue(attachedB.isCancelled(), "附着任务应被取消");
        assertFalse(detached.isCancelled(), "游离任务不应受 ESC 影响");
    }

    @Test
    void detachRemovesTaskFromEscScope() {
        CancellationRegistry registry = new CancellationRegistry();
        registry.beginForegroundTurn();
        CancellationToken token = registry.registerTask("task_1", true);

        // 前台超时转后台 / 显式后台 → 游离
        registry.detach("task_1");
        assertFalse(registry.isAttached("task_1"), "detach 后任务应为游离");

        registry.cancelForegroundAndAttached();
        assertFalse(token.isCancelled(), "游离后 ESC 不应再取消它");
    }

    @Test
    void cancelTaskCancelsExactlyThatTask() {
        CancellationRegistry registry = new CancellationRegistry();
        CancellationToken a = registry.registerTask("task_1", false);
        CancellationToken b = registry.registerTask("task_2", false);

        assertTrue(registry.cancelTask("task_1"), "应取消已知任务");
        assertTrue(a.isCancelled(), "目标任务被取消");
        assertFalse(b.isCancelled(), "其他任务不受影响");
    }

    @Test
    void cancelTaskWorksRegardlessOfAttachment() {
        CancellationRegistry registry = new CancellationRegistry();
        CancellationToken detached = registry.registerTask("task_9", false);

        // /task cancel 对游离任务也生效（这正是单独终止跑偏后台任务的入口）
        assertTrue(registry.cancelTask("task_9"));
        assertTrue(detached.isCancelled());
    }

    @Test
    void cancelUnknownTaskReturnsFalse() {
        CancellationRegistry registry = new CancellationRegistry();
        assertFalse(registry.cancelTask("task_404"), "未知任务返回 false，不崩");
    }

    @Test
    void beginForegroundTurnReplacesTokenSoOldTurnIsNotCancelledByEsc() {
        CancellationRegistry registry = new CancellationRegistry();
        CancellationToken first = registry.beginForegroundTurn();
        CancellationToken second = registry.beginForegroundTurn();

        registry.cancelForegroundAndAttached();
        assertFalse(first.isCancelled(), "上一回合令牌不应被新回合的 ESC 取消");
        assertTrue(second.isCancelled(), "ESC 取消的是当前回合令牌");
    }

    @Test
    void endForegroundTurnOnlyClearsWhenStillCurrent() {
        CancellationRegistry registry = new CancellationRegistry();
        CancellationToken first = registry.beginForegroundTurn();
        CancellationToken second = registry.beginForegroundTurn();

        // 结束上一回合不应清掉已开始的当前回合
        registry.endForegroundTurn(first);
        registry.cancelForegroundAndAttached();
        assertTrue(second.isCancelled(), "endForegroundTurn(旧令牌) 不应清掉当前回合令牌");
    }
}
