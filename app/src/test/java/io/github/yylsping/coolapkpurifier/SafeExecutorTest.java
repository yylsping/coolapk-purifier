package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Manifest §8.5 / BUG-D: a terminal-state cleanup racing a late callback on
 * a shut-down worker must never surface RejectedExecutionException.
 */
public final class SafeExecutorTest {
    @Test
    public void shutdownExecutorRejectsSilently() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.shutdown();
        AtomicBoolean ran = new AtomicBoolean();
        boolean submitted = SafeExecutor.tryExecute(worker, () -> ran.set(true));
        assertFalse(submitted);
        assertFalse(ran.get());
    }

    @Test
    public void liveExecutorRunsTheTask() {
        AtomicBoolean ran = new AtomicBoolean();
        boolean submitted = SafeExecutor.tryExecute(Runnable::run, () -> ran.set(true));
        assertTrue(submitted);
        assertTrue(ran.get());
    }
}
