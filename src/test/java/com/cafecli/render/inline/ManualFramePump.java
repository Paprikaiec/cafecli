package com.cafecli.render.inline;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

final class ManualFramePump implements FramePump {
    // Concurrent because frames are now requested from the geometry sampler's own thread as well as
    // from the test thread: a sampled transition asks for the frame that will consume it.
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

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

    /**
     * Run every queued frame, including frames those frames ask for.
     *
     * <p>A frame requests its successor while a deferred redraw is owed, so this is what lets a test
     * say "let the renderer finish reacting" instead of counting pumps. The bound is a safety net: with
     * the clock held still inside [[geometry turbulence]] the chain never terminates on its own, and
     * every one of those frames writes nothing.
     */
    void drain() {
        for (int guard = 0; guard < 64 && !pending.isEmpty(); guard++) {
            pending.poll().run();
        }
    }

    @Override
    public void close() {
        pending.clear();
    }
}
