package io.github.yylsping.coolapkpurifier;

import android.os.SystemClock;

/**
 * Short-lived, process-local splash decision observation state. Written only
 * by the decision observer, never persisted, never fed back into host state.
 * UI cleaners may read it for correlation logging only — it is never a
 * gating precondition for UI-layer suppression.
 */
final class SplashDecisionState {
    interface Clock {
        long elapsedRealtime();
    }

    private static final long UNSET = -1L;

    private final Clock clock;
    private volatile Boolean lastDecisionOriginal;
    private volatile long lastDecisionObservedAtElapsed = UNSET;

    SplashDecisionState() {
        this(SystemClock::elapsedRealtime);
    }

    SplashDecisionState(Clock clock) {
        this.clock = clock;
    }

    void record(boolean original) {
        lastDecisionOriginal = original;
        lastDecisionObservedAtElapsed = clock.elapsedRealtime();
    }

    /** Null when no decision was observed in this process yet. */
    Boolean lastDecisionOriginal() {
        return lastDecisionOriginal;
    }

    long lastDecisionObservedAtElapsed() {
        return lastDecisionObservedAtElapsed;
    }

    /** The host intended to show a splash ad on the last observation. */
    boolean expectedSplash() {
        return Boolean.TRUE.equals(lastDecisionOriginal);
    }
}
