package com.cafecli.task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空闲任务的 LRU 回收策略（ADR 0005 生命周期）：约束「空闲-可续派」任务的内存——保留最近活跃的 N 个
 * 可续派，超出者按最久未活跃淘汰。被回收任务的历史应被驱逐（续派/检视返回「已过期」），但保留一行结果
 * 摘要供 {@code /task} 历史查看。
 *
 * <p>运行中任务不归本类管（由 {@link AdmissionControl} 的并发名额管）；本类只管已转空闲的任务留存，二者分离。
 *
 * <p>纯策略对象：只做 LRU 会计与淘汰决策，不持有 Agent / 线程；可在无线程环境下断言「到达上限时淘汰谁」
 * （类比 AdmissionControlTest）。{@code onIdle} 返回被淘汰的 task_id，由集成层据此驱逐其保留的子 Agent。
 */
public class IdleTaskReaper {

    public static final int DEFAULT_MAX_IDLE = 8;

    private final int maxIdle;
    // 插入序即活跃序：remove+put 把刚活跃的挪到末尾；淘汰从头部（最久未活跃）取。
    private final LinkedHashMap<String, String> idle = new LinkedHashMap<>();
    // 被回收任务的一行结果摘要（供 /task 历史）。
    private final LinkedHashMap<String, String> reapedSummaries = new LinkedHashMap<>();

    public IdleTaskReaper(int maxIdle) {
        if (maxIdle < 1) {
            throw new IllegalArgumentException("maxIdle 必须 ≥ 1，实际：" + maxIdle);
        }
        this.maxIdle = maxIdle;
    }



    public int maxIdle() {
        return maxIdle;
    }

    /**
     * 任务转空闲（完成 / 续派完成）：登记并刷新为最近活跃；超过上限时淘汰最久未活跃的，返回被回收的
     * task_id 列表（其历史应被驱逐、续派返回「已过期」，一行摘要进 reaped 历史）。
     */
    public synchronized List<String> onIdle(String taskId, String resultSummary) {
        idle.remove(taskId);                       // 刷新到最近活跃（末尾）
        idle.put(taskId, resultSummary == null ? "" : resultSummary);
        reapedSummaries.remove(taskId);            // 若曾被回收又复活，移出 reaped

        List<String> evicted = new ArrayList<>();
        Iterator<Map.Entry<String, String>> it = idle.entrySet().iterator();
        while (idle.size() > maxIdle) {
            Map.Entry<String, String> oldest = it.next();
            reapedSummaries.put(oldest.getKey(), oldest.getValue());
            evicted.add(oldest.getKey());
            it.remove();
        }
        return evicted;
    }

    /** Running tasks no longer consume idle retention slots. */
    public synchronized void onRunning(String taskId) { idle.remove(taskId); }

    /** 任务仍在保留、可续派。 */
    public synchronized boolean isLive(String taskId) {
        return idle.containsKey(taskId);
    }

    /** 任务已被 LRU 回收：续派/检视应返回「已过期」。 */
    public synchronized boolean isReaped(String taskId) {
        return reapedSummaries.containsKey(taskId);
    }

    /** 被回收任务的一行结果摘要（供 /task 历史）；未回收返回 null。 */
    public synchronized String reapedSummary(String taskId) {
        return reapedSummaries.get(taskId);
    }

    public synchronized int liveCount() {
        return idle.size();
    }
}
