package com.paicli.agent.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 每任务对话历史的 UI / transcript 镜像，设上限（ADR 0005 生命周期，对标 CC 的 TEAMMATE_MESSAGES_UI_CAP）：
 * 超过上限时淘汰最旧，使单个任务的 transcript 镜像内存有界。
 *
 * <p>纯策略对象：只做有界队列会计，可在无线程环境下断言「在上限处封顶、淘汰最旧」。
 */
public final class BoundedMessageMirror {

    public static final int DEFAULT_CAP = 50;

    private final int cap;
    private final Deque<String> messages = new ArrayDeque<>();

    public BoundedMessageMirror(int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("cap 必须 ≥ 1，实际：" + cap);
        }
        this.cap = cap;
    }

    /** 从系统属性读取镜像上限：{@code paicli.task.message-mirror-cap}（默认 50）。 */
    public static BoundedMessageMirror fromProperties() {
        return new BoundedMessageMirror(Integer.getInteger("paicli.task.message-mirror-cap", DEFAULT_CAP));
    }

    public int cap() {
        return cap;
    }

    /** 追加一条镜像消息；超过上限淘汰最旧。 */
    public synchronized void add(String message) {
        messages.addLast(message == null ? "" : message);
        while (messages.size() > cap) {
            messages.removeFirst();
        }
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(messages);
    }
}
