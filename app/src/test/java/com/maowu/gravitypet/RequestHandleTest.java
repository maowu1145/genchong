package com.maowu.gravitypet;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.FutureTask;

import org.junit.Test;

public class RequestHandleTest {
    @Test
    public void cancelIsIdempotentAndCancelsAttachedTask() {
        RequestHandle handle = new RequestHandle();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        handle.attach(task);

        handle.cancel();
        handle.cancel();

        assertTrue(handle.isCancelled());
        assertTrue(task.isCancelled());
    }

    @Test
    public void taskAttachedAfterCancellationIsImmediatelyCancelled() {
        RequestHandle handle = new RequestHandle();
        handle.cancel();
        FutureTask<Void> task = new FutureTask<>(() -> null);

        handle.attach(task);

        assertTrue(task.isCancelled());
    }
}
