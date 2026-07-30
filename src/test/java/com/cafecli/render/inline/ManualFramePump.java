package com.cafecli.render.inline;

import java.util.ArrayDeque;
import java.util.Queue;

final class ManualFramePump implements FramePump {
    private final Queue<Runnable> pending = new ArrayDeque<>();

    @Override
    public void request(Runnable frame) {
        pending.add(frame);
    }

    void pump() {
        Runnable frame = pending.poll();
        if (frame != null) {
            frame.run();
        }
    }

    @Override
    public void close() {
        pending.clear();
    }
}
