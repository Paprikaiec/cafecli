package com.cafecli.render.inline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledFramePumpTest {

    @Test
    void firstFrameLeadsAndNextDirtyFrameTrailsTheInterval() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        List<Runnable> tasks = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(0));
                    delays.add(invocation.getArgument(1));
                    return future;
                });
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AtomicInteger frames = new AtomicInteger();

        try (ScheduledFramePump pump = new ScheduledFramePump(scheduler, now::get, 33_000_000L)) {
            pump.request(frames::incrementAndGet);
            assertEquals(0L, delays.get(0));
            tasks.remove(0).run();

            now.addAndGet(10_000_000L);
            pump.request(frames::incrementAndGet);
            assertEquals(23_000_000L, delays.get(1));
            tasks.remove(0).run();
        }

        assertEquals(2, frames.get());
    }
}
