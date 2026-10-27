package com.cafecli.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲任务 LRU 回收（ADR 0005）的纯策略单测：到达上限时淘汰最久未活跃的；被回收任务返回「已过期」
 * 语义（isReaped）但保留一行摘要供 /task 历史；续派刷新活跃度避免被误淘汰。
 */
class IdleTaskReaperTest {

    @Test
    void evictsOldestWhenOverLimit() {
        IdleTaskReaper reaper = new IdleTaskReaper(3);
        assertTrue(reaper.onIdle("task_1", "结果1").isEmpty());
        assertTrue(reaper.onIdle("task_2", "结果2").isEmpty());
        assertTrue(reaper.onIdle("task_3", "结果3").isEmpty());

        List<String> evicted = reaper.onIdle("task_4", "结果4");

        assertEquals(List.of("task_1"), evicted, "应淘汰最久未活跃的 task_1");
        assertFalse(reaper.isLive("task_1"), "被回收任务不再可续派");
        assertTrue(reaper.isReaped("task_1"), "被回收任务标记为已过期");
        assertTrue(reaper.isLive("task_4") && reaper.isLive("task_2") && reaper.isLive("task_3"));
        assertEquals(3, reaper.liveCount(), "保留数封顶在上限");
    }

    @Test
    void reapedTaskRetainsOneLineSummaryForHistory() {
        IdleTaskReaper reaper = new IdleTaskReaper(1);
        reaper.onIdle("task_1", "task_1 的结论摘要");
        reaper.onIdle("task_2", "task_2 的结论摘要");

        assertTrue(reaper.isReaped("task_1"));
        assertEquals("task_1 的结论摘要", reaper.reapedSummary("task_1"),
                "被回收任务仍保留一行结果摘要供 /task 历史");
    }

    @Test
    void continueRefreshesRecencySoActiveTaskIsNotEvicted() {
        IdleTaskReaper reaper = new IdleTaskReaper(2);
        reaper.onIdle("task_1", "r1");
        reaper.onIdle("task_2", "r2");

        // task_1 被续派后再次转空闲 → 刷新为最近活跃
        reaper.onIdle("task_1", "r1b");
        // 新增 task_3 → 此时最久未活跃的是 task_2，应淘汰 task_2 而非刚活跃的 task_1
        List<String> evicted = reaper.onIdle("task_3", "r3");

        assertEquals(List.of("task_2"), evicted, "应淘汰最久未活跃的 task_2");
        assertTrue(reaper.isLive("task_1"), "刚续派活跃的 task_1 应被保留");
        assertFalse(reaper.isLive("task_2"));
    }

    @Test
    void runningTasksAreNotManagedHere() {
        // 运行中任务由准入名额管，不进 reaper；reaper 只在任务转空闲时登记。未登记的 id：既非 live 也非 reaped。
        IdleTaskReaper reaper = new IdleTaskReaper(5);
        assertFalse(reaper.isLive("task_running"));
        assertFalse(reaper.isReaped("task_running"));
    }

}
