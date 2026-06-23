package com.paicli.agent.task;

import java.util.concurrent.Semaphore;

/**
 * 任务派生的准入控制（ADR 0006）：约束并发运行任务数、并对派生深度做硬兜底，防线程爆炸与等待型死锁。
 *
 * <p>两条机制，互相独立：
 * <ul>
 *   <li><b>深度上限</b>（硬兜底）：限制 主 Agent → 任务 → 子任务 的派生层数。主 Agent 记为深度 0，
 *       它派生的任务是深度 1，任务再派生的子任务是深度 2……在超过 {@code maxDepth} 的深度派生会被
 *       {@link #acquire(int)} 直接拒绝（抛 {@link AdmissionException}，不阻塞、不派生），避免无限递归。</li>
 *   <li><b>并发名额</b>（节流，非拒绝）：同时<em>运行</em>的任务数不超过 {@code maxConcurrency}；占满时
 *       {@link #acquire(int)} 阻塞直到有名额释放，使峰值并发 ≤ 上限。</li>
 * </ul>
 *
 * <p><b>等待即释放配额</b>：父任务前台等待子任务期间，用 {@link #releasingPermitWhile} 临时释放自身名额、
 * 等待结束再重新占用。否则「父占满池子、又各自阻塞等子任务，而子任务拿不到名额」会构成等待型死锁。
 *
 * <p>纯策略对象：仅做名额与深度的会计，不持有线程也不感知 Agent；可在无线程环境下断言其决策
 * （类比 {@code AgentBudget}）。运行中任务的留存由本类管理，空闲任务的回收由 LRU 另行处理（二者分离）。
 */
public final class AdmissionControl {

    public static final int DEFAULT_MAX_CONCURRENCY = 8;
    public static final int DEFAULT_MAX_DEPTH = 5;

    private final int maxConcurrency;
    private final int maxDepth;
    private final Semaphore permits;

    public AdmissionControl(int maxConcurrency, int maxDepth) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency 必须 ≥ 1，实际：" + maxConcurrency);
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth 必须 ≥ 1，实际：" + maxDepth);
        }
        this.maxConcurrency = maxConcurrency;
        this.maxDepth = maxDepth;
        this.permits = new Semaphore(maxConcurrency);
    }

    /** 从系统属性读取配置：{@code paicli.task.max-concurrency} / {@code paicli.task.max-depth}（含合理默认）。 */
    public static AdmissionControl fromProperties() {
        return new AdmissionControl(
                Integer.getInteger("paicli.task.max-concurrency", DEFAULT_MAX_CONCURRENCY),
                Integer.getInteger("paicli.task.max-depth", DEFAULT_MAX_DEPTH));
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /** 在 {@code depth}（主 Agent=0、其派生的任务=1……）派生一个任务是否在深度上限内。 */
    public boolean admitsDepth(int depth) {
        return depth >= 1 && depth <= maxDepth;
    }

    /**
     * 为一个深度为 {@code depth} 的任务占用一个并发名额；占满时阻塞直到有名额。
     * 深度超限直接抛 {@link AdmissionException}（不阻塞、不占名额）。
     */
    public void acquire(int depth) throws InterruptedException {
        rejectIfTooDeep(depth);
        permits.acquire();
    }

    /** {@link #acquire(int)} 的非阻塞版：深度超限抛异常；有名额则占用并返回 true，否则返回 false。 */
    public boolean tryAcquire(int depth) {
        rejectIfTooDeep(depth);
        return permits.tryAcquire();
    }

    /** 任务结束，归还一个并发名额。 */
    public void release() {
        permits.release();
    }

    /**
     * 父任务前台等待子任务期间临时释放自身名额、等待结束后重新占用——避免等待型死锁。
     * {@code waited} 通常是「派生子任务并 join 等其完成」的阻塞动作。
     */
    public <T> T releasingPermitWhile(InterruptibleSupplier<T> waited) throws InterruptedException {
        permits.release();
        try {
            return waited.get();
        } finally {
            permits.acquire();
        }
    }

    private void rejectIfTooDeep(int depth) {
        if (!admitsDepth(depth)) {
            throw new AdmissionException(
                    "派生深度 " + depth + " 超过上限 " + maxDepth + "，拒绝派生（避免无限递归）");
        }
    }

    /** 深度超限导致的派生拒绝（硬兜底）。 */
    public static final class AdmissionException extends RuntimeException {
        public AdmissionException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface InterruptibleSupplier<T> {
        T get() throws InterruptedException;
    }
}
