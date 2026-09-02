package com.maowu.gravitypet;

import java.net.HttpURLConnection;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A small, idempotent cancellation scope for one cloud request. */
final class RequestHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
    private volatile Future<?> future;

    void attach(Future<?> task) {
        future = task;
        if (cancelled.get() && task != null) task.cancel(true);
    }

    void track(HttpURLConnection value) {
        connection.set(value);
        if (cancelled.get() && value != null) value.disconnect();
    }

    boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        HttpURLConnection active = connection.getAndSet(null);
        if (active != null) active.disconnect();
        Future<?> task = future;
        if (task != null) task.cancel(true);
    }

    void clear(HttpURLConnection value) {
        connection.compareAndSet(value, null);
    }
}
