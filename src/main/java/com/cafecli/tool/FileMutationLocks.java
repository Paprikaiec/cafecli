package com.cafecli.tool;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

final class FileMutationLocks {
    private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private FileMutationLocks() {
    }

    static <T> T withLock(Path key, Callable<T> body) throws Exception {
        ReentrantLock lock = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            return body.call();
        } finally {
            lock.unlock();
        }
    }
}
