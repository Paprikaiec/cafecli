package com.cafecli.agent.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskRegistryTest {

    @Test
    void registersTaskWithStableIdInRunningState() {
        TaskRegistry registry = new TaskRegistry();

        String id = registry.register("调研 Loom 现状");

        assertEquals("task_1", id, "首个任务应拿到稳定 id task_1");
        Task task = registry.get(id);
        assertNotNull(task);
        assertEquals(Task.State.RUNNING, task.state());
        assertEquals("调研 Loom 现状", task.description());
        assertNull(task.result(), "RUNNING 任务尚无结果");
    }

    @Test
    void assignsMonotonicIdsToSubsequentTasks() {
        TaskRegistry registry = new TaskRegistry();

        assertEquals("task_1", registry.register("一"));
        assertEquals("task_2", registry.register("二"));
        assertEquals("task_3", registry.register("三"));

        List<Task> tasks = registry.tasks();
        assertEquals(List.of("task_1", "task_2", "task_3"),
                tasks.stream().map(Task::id).toList(),
                "tasks() 应按派生先后排序");
    }

    @Test
    void marksTaskDoneWithResult() {
        TaskRegistry registry = new TaskRegistry();
        String id = registry.register("任务");

        registry.markDone(id, "最终结论");

        Task task = registry.get(id);
        assertEquals(Task.State.DONE, task.state());
        assertEquals("最终结论", task.result());
    }

    @Test
    void marksTaskFailedWithReason() {
        TaskRegistry registry = new TaskRegistry();
        String id = registry.register("任务");

        registry.markFailed(id, "子任务执行失败: boom");

        Task task = registry.get(id);
        assertEquals(Task.State.FAILED, task.state());
        assertTrue(task.result().contains("boom"));
    }

    @Test
    void transitionOnUnknownTaskIsNoOp() {
        TaskRegistry registry = new TaskRegistry();

        // 不存在的 id 不应抛异常，也不应凭空创建任务
        registry.markDone("task_404", "x");
        registry.markFailed("task_404", "y");

        assertTrue(registry.tasks().isEmpty());
        assertNull(registry.get("task_404"));
    }
}
