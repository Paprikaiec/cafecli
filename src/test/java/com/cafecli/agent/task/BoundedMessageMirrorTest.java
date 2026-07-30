package com.cafecli.agent.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每任务消息镜像上限（ADR 0005）的纯策略单测：在上限处封顶、淘汰最旧。
 */
class BoundedMessageMirrorTest {

    @Test
    void capsAtLimitAndEvictsOldest() {
        BoundedMessageMirror mirror = new BoundedMessageMirror(3);
        mirror.add("m1");
        mirror.add("m2");
        mirror.add("m3");
        mirror.add("m4");

        assertEquals(3, mirror.size(), "镜像应封顶在上限");
        assertEquals(List.of("m2", "m3", "m4"), mirror.snapshot(), "应淘汰最旧的 m1，保留最近三条");
    }

    @Test
    void fromPropertiesUsesDefaultThenSystemProperty() {
        assertEquals(BoundedMessageMirror.DEFAULT_CAP, BoundedMessageMirror.fromProperties().cap());
        System.setProperty("paicli.task.message-mirror-cap", "20");
        try {
            assertEquals(20, BoundedMessageMirror.fromProperties().cap());
        } finally {
            System.clearProperty("paicli.task.message-mirror-cap");
        }
    }
}
