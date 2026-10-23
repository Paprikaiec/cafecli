package com.cafecli.agent.task;

/** v1 compatibility entry point; task policy lives in the shared leaf. */
public final class BoundedMessageMirror extends com.cafecli.task.BoundedMessageMirror {
    public BoundedMessageMirror(int cap) { super(cap); }
    public static BoundedMessageMirror fromProperties() {
        return new BoundedMessageMirror(Integer.getInteger("paicli.task.message-mirror-cap", DEFAULT_CAP));
    }
}
