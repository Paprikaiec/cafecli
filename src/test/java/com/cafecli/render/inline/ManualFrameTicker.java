package com.cafecli.render.inline;

final class ManualFrameTicker implements FrameTicker {
    private Runnable tick;

    @Override
    public Cancellable start(Runnable tick) {
        this.tick = tick;
        return () -> this.tick = null;
    }

    void tick() {
        if (tick != null) {
            tick.run();
        }
    }

    @Override
    public void close() {
        tick = null;
    }
}
