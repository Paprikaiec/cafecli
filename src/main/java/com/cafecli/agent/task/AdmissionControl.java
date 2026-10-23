package com.cafecli.agent.task;

/** v1 compatibility entry point; task policy lives in the shared leaf. */
public final class AdmissionControl extends com.cafecli.task.AdmissionControl {
    public AdmissionControl(int maxConcurrency, int maxDepth) { super(maxConcurrency, maxDepth); }
    public static AdmissionControl fromProperties() {
        return new AdmissionControl(Integer.getInteger("paicli.task.max-concurrency", DEFAULT_MAX_CONCURRENCY),
                Integer.getInteger("paicli.task.max-depth", DEFAULT_MAX_DEPTH));
    }
}
