package com.cafecli.agent.task;

/** v1 compatibility entry point; task policy lives in the shared leaf. */
public final class IdleTaskReaper extends com.cafecli.task.IdleTaskReaper {
    public IdleTaskReaper(int maxIdle) { super(maxIdle); }
    public static IdleTaskReaper fromProperties() {
        return new IdleTaskReaper(Integer.getInteger("paicli.task.max-idle", DEFAULT_MAX_IDLE));
    }
}
