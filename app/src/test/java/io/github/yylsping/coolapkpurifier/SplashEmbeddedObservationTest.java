package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Tri-state isAdded probing and delayed removal-observation mapping.
 * UNKNOWN never masquerades as removal, and the suppression gate fails
 * closed on UNKNOWN.
 */
public final class SplashEmbeddedObservationTest {

    public static final class AddedFake {
        public boolean isAdded() {
            return true;
        }
    }

    public static final class NotAddedFake {
        public boolean isAdded() {
            return false;
        }
    }

    public static final class ThrowingFake {
        public boolean isAdded() {
            throw new IllegalStateException("boom");
        }
    }

    public static final class NoMethodFake {
    }

    @Test
    public void probeAddedIsTriState() {
        assertEquals(SplashEmbeddedHooks.AddedState.ADDED,
                SplashEmbeddedHooks.probeAdded(new AddedFake()));
        assertEquals(SplashEmbeddedHooks.AddedState.NOT_ADDED,
                SplashEmbeddedHooks.probeAdded(new NotAddedFake()));
        assertEquals("reflection exception must be UNKNOWN",
                SplashEmbeddedHooks.AddedState.UNKNOWN,
                SplashEmbeddedHooks.probeAdded(new ThrowingFake()));
        assertEquals("missing method must be UNKNOWN",
                SplashEmbeddedHooks.AddedState.UNKNOWN,
                SplashEmbeddedHooks.probeAdded(new NoMethodFake()));
    }

    @Test
    public void unknownProbeFailsClosedAtSuppressionGate() {
        assertFalse("UNKNOWN must not be treated as added",
                SplashEmbeddedPolicy.shouldSuppress(true,
                        SplashEmbeddedHooks.probeAdded(new ThrowingFake())
                                == SplashEmbeddedHooks.AddedState.ADDED));
        assertFalse(SplashEmbeddedPolicy.shouldSuppress(true,
                SplashEmbeddedHooks.probeAdded(new NoMethodFake())
                        == SplashEmbeddedHooks.AddedState.ADDED));
    }

    @Test
    public void removalOutcomeMapping() {
        assertEquals("cleared weak ref means unreachable after removal",
                SplashEmbeddedHooks.RemovalOutcome.REMOVAL_OBSERVED,
                SplashEmbeddedHooks.removalOutcome(true,
                        SplashEmbeddedHooks.AddedState.UNKNOWN));
        assertEquals(SplashEmbeddedHooks.RemovalOutcome.REMOVAL_OBSERVED,
                SplashEmbeddedHooks.removalOutcome(false,
                        SplashEmbeddedHooks.AddedState.NOT_ADDED));
        assertEquals(SplashEmbeddedHooks.RemovalOutcome.UNCONFIRMED,
                SplashEmbeddedHooks.removalOutcome(false,
                        SplashEmbeddedHooks.AddedState.ADDED));
        assertEquals("UNKNOWN probe must never be reported as removal",
                SplashEmbeddedHooks.RemovalOutcome.OBSERVATION_UNKNOWN,
                SplashEmbeddedHooks.removalOutcome(false,
                        SplashEmbeddedHooks.AddedState.UNKNOWN));
    }

    @Test
    public void diagnosticsFailureAfterSuccessfulDispatchNeverReclassifies() {
        // Simulates: setFragmentResult succeeded, then a later diagnostics
        // step throws and the failure handler runs. The per-instance state
        // must stay SENT.
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();
        dispatch.tryMarkPending(fragment);
        dispatch.markSent(fragment);
        dispatch.markDispatchFailed(fragment);
        assertEquals(SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(fragment));
    }
}
