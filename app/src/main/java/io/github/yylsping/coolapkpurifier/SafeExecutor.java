package io.github.yylsping.coolapkpurifier;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Executor submission that treats a shut-down worker as a no-op (BUG-D):
 * terminal-state cleanup may race a late callback, and the rejection must
 * never surface as an exception.
 */
final class SafeExecutor {
    private SafeExecutor() {
    }

    /** @return false when the executor already rejected the task. */
    static boolean tryExecute(Executor executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException shutdown) {
            return false;
        }
    }
}
