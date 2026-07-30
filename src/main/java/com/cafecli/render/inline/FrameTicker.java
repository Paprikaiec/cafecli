package com.cafecli.render.inline;

/** Periodic producer event source; it never writes terminal bytes itself. */
interface FrameTicker extends AutoCloseable {

    Cancellable start(Runnable tick);

    @Override
    default void close() {
    }

    interface Cancellable {
        void cancel();
    }
}
