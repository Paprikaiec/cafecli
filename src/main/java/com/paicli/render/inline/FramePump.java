package com.paicli.render.inline;

/** Schedules dirty render frames; tests inject a deterministic manual implementation. */
interface FramePump extends AutoCloseable {

    void request(Runnable frame);

    @Override
    default void close() {
    }
}
