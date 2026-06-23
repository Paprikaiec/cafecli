package com.paicli.agent.task;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存级任务登记表：追踪主 Agent 在本会话内派生的所有任务（Task）的生命周期。
 *
 * <p>每个任务分配一个稳定的 {@code task_id}（task_1, task_2, ...），并随子 Agent 的执行
 * 完成 RUNNING → DONE / FAILED 的状态迁移。本期（#01）是单会话内存表，不持久化；
 * 后续切到事件循环 / 后台派生时，这张表是任务可观测性的落点。
 *
 * <p>线程安全：派生本期是前台同步的，但登记表用并发结构兜底，方便 #07 后台派生复用。
 */
public final class TaskRegistry {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * 登记一个新任务，分配稳定的 {@code task_id} 并置为 RUNNING。
     *
     * @return 新登记任务的 id
     */
    public synchronized String register(String description) {
        String id = "task_" + sequence.incrementAndGet();
        tasks.put(id, Task.running(id, description));
        return id;
    }

    /** 把任务迁移到 DONE，并保存子 Agent 的最终结果文本。 */
    public synchronized void markDone(String taskId, String result) {
        transition(taskId, existing -> existing.completed(result));
    }

    /** 把任务迁移到 FAILED，并保存可读的失败说明。 */
    public synchronized void markFailed(String taskId, String reason) {
        transition(taskId, existing -> existing.failed(reason));
    }

    private void transition(String taskId, java.util.function.UnaryOperator<Task> op) {
        Task existing = tasks.get(taskId);
        if (existing == null) {
            return;
        }
        tasks.put(taskId, op.apply(existing));
    }

    /** 按 id 取任务，不存在返回 null。 */
    public Task get(String taskId) {
        return tasks.get(taskId);
    }

    /** 当前所有任务的快照，按 task_id 升序（即派生先后）。 */
    public List<Task> tasks() {
        return tasks.values().stream()
                .sorted((a, b) -> Integer.compare(sequenceOf(a.id()), sequenceOf(b.id())))
                .toList();
    }

    private static int sequenceOf(String taskId) {
        int underscore = taskId.lastIndexOf('_');
        try {
            return Integer.parseInt(taskId.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
