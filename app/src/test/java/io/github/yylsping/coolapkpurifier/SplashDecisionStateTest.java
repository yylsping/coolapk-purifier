package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local observation state: recorded, readable, never authoritative. */
public final class SplashDecisionStateTest {
    @Test
    public void emptyStateReportsNoObservation() {
        SplashDecisionState state = new SplashDecisionState(() -> 42L);
        assertNull(state.lastDecisionOriginal());
        assertEquals(-1L, state.lastDecisionObservedAtElapsed());
        assertFalse(state.expectedSplash());
    }

    @Test
    public void latestObservationWinsWithElapsedTimestamp() {
        AtomicLong now = new AtomicLong(7L);
        SplashDecisionState state = new SplashDecisionState(now::get);

        state.record(true);
        assertEquals(Boolean.TRUE, state.lastDecisionOriginal());
        assertTrue(state.expectedSplash());
        assertEquals(7L, state.lastDecisionObservedAtElapsed());

        now.set(19L);
        state.record(false);
        assertEquals(Boolean.FALSE, state.lastDecisionOriginal());
        assertFalse(state.expectedSplash());
        assertEquals(19L, state.lastDecisionObservedAtElapsed());
    }
}
