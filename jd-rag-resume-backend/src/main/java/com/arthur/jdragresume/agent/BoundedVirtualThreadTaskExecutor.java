package com.arthur.jdragresume.agent;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual-thread {@link AsyncTaskExecutor} with strict admission and concurrency bounds.
 * <p>
 * Calling threads fail fast with {@link TaskRejectedException} once {@code maxConcurrency + queueCapacity}
 * tasks are already active or waiting, preserving queue-full backpressure while running each accepted
 * blocking I/O turn on a Java 21 virtual thread.
 */
public class BoundedVirtualThreadTaskExecutor implements AsyncTaskExecutor, DisposableBean {
    private final ExecutorService executor;
    private final Semaphore concurrencyPermits;
    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger(0);
    private volatile boolean closed = false;

    public BoundedVirtualThreadTaskExecutor(String threadNamePrefix, int maxConcurrency, int queueCapacity) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must be >= 0");
        }
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
        this.concurrencyPermits = new Semaphore(maxConcurrency);
        this.maxPending = (int) Math.min(Integer.MAX_VALUE, (long) maxConcurrency + queueCapacity);
    }

    @Override
    public void execute(Runnable task) {
        if (closed) {
            throw new TaskRejectedException("Virtual-thread executor has been shut down");
        }
        if (!tryReserveSlot()) {
            throw new TaskRejectedException(
                    "Agent virtual-thread queue capacity reached (" + maxPending + ")");
        }
        try {
            executor.execute(() -> runWithPermit(task));
        } catch (RuntimeException ex) {
            pending.decrementAndGet();
            throw new TaskRejectedException("Failed to submit task to virtual-thread executor", ex);
        }
    }

    @Override
    public Future<?> submit(Runnable task) {
        return AsyncTaskExecutor.super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return AsyncTaskExecutor.super.submit(task);
    }

    private boolean tryReserveSlot() {
        while (true) {
            int current = pending.get();
            if (current >= maxPending) {
                return false;
            }
            if (pending.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void runWithPermit(Runnable task) {
        boolean acquired = false;
        try {
            concurrencyPermits.acquire();
            acquired = true;
            task.run();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (task instanceof Future<?> future) {
                future.cancel(true);
            }
        } finally {
            if (acquired) {
                concurrencyPermits.release();
            }
            pending.decrementAndGet();
        }
    }

    public int pendingCount() {
        return pending.get();
    }

    public int availableConcurrencyPermits() {
        return concurrencyPermits.availablePermits();
    }

    @Override
    public void destroy() {
        closed = true;
        executor.shutdown();
    }
}
