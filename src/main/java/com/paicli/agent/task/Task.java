package com.paicli.agent.task;

/**
 * 一个会话内任务（Task）——主 Agent 派生出来的自治子 Agent 的生命周期记录。
 *
 * <p>对应 CONTEXT.md「Agent 编排」里的「任务 / Task」：主 Agent 自主派生、独立历史、
 * 独立预算的子 Agent。本期（#01）任务在派生它的那一轮内前台同步跑完，因此状态从
 * {@link State#RUNNING} 直接迁移到 {@link State#DONE} 或 {@link State#FAILED}。
 *
 * <p>不可变值对象：状态迁移通过 {@link #completed(String)} / {@link #failed(String)} 返回新实例，
 * 由 {@link TaskRegistry} 负责替换登记表里的条目。
 */
public record Task(String id, String description, State state, String result) {

    public enum State {
        /** 任务正在前台执行。 */
        RUNNING,
        /** 任务成功跑完，{@link #result()} 是子 Agent 的最终结果文本。 */
        DONE,
        /** 任务执行失败，{@link #result()} 是可读的失败说明。 */
        FAILED
    }

    /** 新登记的任务总是从 RUNNING 开始，尚无结果。 */
    public static Task running(String id, String description) {
        return new Task(id, description, State.RUNNING, null);
    }

    /** 迁移到 DONE，并附上子 Agent 的最终结果文本。 */
    public Task completed(String finalResult) {
        return new Task(id, description, State.DONE, finalResult);
    }

    /** 迁移到 FAILED，并附上可读的失败说明。 */
    public Task failed(String reason) {
        return new Task(id, description, State.FAILED, reason);
    }
}
