package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Two-layer readiness: core filtering capability vs feed coverage
 * convergence. These decisions used to live inline in the coordinator where
 * they could not be exercised without an Xposed runtime.
 */
public final class ReadinessPolicyTest {
    @Test
    public void coreReadyRequiresSplashFeedHooksAndAccessors() {
        assertFalse(ReadinessPolicy.isCoreReady(false, 0, false));
        assertFalse(ReadinessPolicy.isCoreReady(true, 0, true));
        assertFalse(ReadinessPolicy.isCoreReady(true, 1, false));
        assertFalse(ReadinessPolicy.isCoreReady(false, 1, true));
        assertTrue(ReadinessPolicy.isCoreReady(true, 1, true));
        assertTrue(ReadinessPolicy.isCoreReady(true, 3, true));
    }

    @Test
    public void coverageRequiresBothAnchorClasses() {
        assertFalse(ReadinessPolicy.isCoverageSettledByAnchors(false, false));
        assertFalse(ReadinessPolicy.isCoverageSettledByAnchors(true, false));
        assertFalse(ReadinessPolicy.isCoverageSettledByAnchors(false, true));
        assertTrue(ReadinessPolicy.isCoverageSettledByAnchors(true, true));
    }

    @Test
    public void sessionOutcomeMatrix() {
        assertEquals(ReadinessPolicy.SessionOutcome.READY,
                ReadinessPolicy.sessionOutcome(true, true));
        assertEquals(ReadinessPolicy.SessionOutcome.RETRY_COVERAGE,
                ReadinessPolicy.sessionOutcome(true, false));
        assertEquals(ReadinessPolicy.SessionOutcome.RETRY_CORE,
                ReadinessPolicy.sessionOutcome(false, true));
        assertEquals(ReadinessPolicy.SessionOutcome.RETRY_CORE,
                ReadinessPolicy.sessionOutcome(false, false));
    }

    /**
     * Staged-dex feed convergence (Issue #3 root cause 2/5 in policy form):
     * session 1 sees only EntityAdHelper (core works, coverage pending);
     * session 2 sees EntityListFragment too and must finish READY.
     */
    @Test
    public void stagedFeedDexConvergesInsteadOfFinishingEarly() {
        boolean splashReady = true;
        boolean accessorsComplete = true;

        // Session 1: only the EntityAdHelper dex is visible.
        ReadinessPolicy.SessionOutcome session1 = ReadinessPolicy.sessionOutcome(
                ReadinessPolicy.isCoreReady(splashReady, 2, accessorsComplete),
                ReadinessPolicy.isCoverageSettledByAnchors(true, false));
        assertEquals(ReadinessPolicy.SessionOutcome.RETRY_COVERAGE, session1);

        // Session 2: the loader appended the EntityListFragment dex.
        ReadinessPolicy.SessionOutcome session2 = ReadinessPolicy.sessionOutcome(
                ReadinessPolicy.isCoreReady(splashReady, 4, accessorsComplete),
                ReadinessPolicy.isCoverageSettledByAnchors(true, true));
        assertEquals(ReadinessPolicy.SessionOutcome.READY, session2);
    }

    /**
     * A version that genuinely lacks one anchor never settles by anchors; the
     * deadline is the only path that may finish it READY.
     */
    @Test
    public void missingAnchorVersionSettlesOnlyAtTheDeadline() {
        assertEquals(ReadinessPolicy.SessionOutcome.RETRY_COVERAGE,
                ReadinessPolicy.sessionOutcome(true,
                        ReadinessPolicy.isCoverageSettledByAnchors(true, false)));
        assertEquals(BootstrapState.READY,
                ReadinessPolicy.deadlineTerminalState(true));
        assertEquals(BootstrapState.DEGRADED,
                ReadinessPolicy.deadlineTerminalState(false));
    }

    @Test
    public void deadlineTerminalStateMirrorsCoreCapability() {
        assertEquals(BootstrapState.READY, ReadinessPolicy.deadlineTerminalState(true));
        assertEquals(BootstrapState.DEGRADED, ReadinessPolicy.deadlineTerminalState(false));
    }

    /**
     * The 8s watchdog retries sessions from EVERY non-terminal state. The old
     * branch order only retried WAIT_RUNTIME_DEX/CACHE_VERIFY, leaving a
     * coverage-pending FULL_RESOLVE without any watchdog retry.
     */
    @Test
    public void watchdogRetriesEveryNonTerminalState() {
        for (BootstrapState state : BootstrapState.values()) {
            boolean expected = !(state == BootstrapState.READY
                    || state == BootstrapState.DEGRADED);
            assertEquals("state=" + state, expected,
                    ReadinessPolicy.shouldWatchdogRetrySession(state));
        }
        assertFalse(ReadinessPolicy.shouldWatchdogRetrySession(null));
    }

    /**
     * Deadline ordering (Issue #3 follow-up P1): a process still in
     * WAIT_RUNTIME_DEX/CACHE_VERIFY/BOOTSTRAP at 20s must terminate instead of
     * consuming the deadline on yet another non-terminal retry.
     */
    @Test
    public void deadlineTerminalDecisionAppliesToIntermediateStates() {
        for (BootstrapState state : BootstrapState.values()) {
            if (state == BootstrapState.READY) {
                continue;
            }
            assertEquals("state=" + state, BootstrapState.DEGRADED,
                    ReadinessPolicy.deadlineTerminalState(false));
            assertEquals("state=" + state, BootstrapState.READY,
                    ReadinessPolicy.deadlineTerminalState(true));
        }
    }
}
