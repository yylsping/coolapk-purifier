package io.github.yylsping.coolapkpurifier;

/**
 * Pure two-layer readiness strategy.
 *
 * <p>"Core ready" means the installed hooks already filter ads: splash covered,
 * at least one live feed hook, classifier accessors complete. "Coverage
 * settled" means the late-discovery problem is closed: every historically
 * known feed anchor class contributes a live hook, so a staged runtime DEX
 * that appends another known feed path would have been observed already.
 *
 * <p>READY requires BOTH layers. A version that genuinely lacks one of the
 * anchor classes can never settle by anchors; it settles only at the 20s
 * deadline, which is the single non-event settling path and also the only
 * path that may finish READY without anchor coverage.
 */
final class ReadinessPolicy {
    private ReadinessPolicy() {
    }

    /** Terminal decision of one resolution session. */
    enum SessionOutcome {
        /** Core ready and coverage settled: finish READY. */
        READY,
        /** Core ready but a known feed anchor is still unhooked: retry late discovery. */
        RETRY_COVERAGE,
        /** Core capability still missing (splash/feed/accessors): keep resolving. */
        RETRY_CORE
    }

    /**
     * Core filtering capability. One live feed hook is enough for the hooks to
     * work, but it proves nothing about coverage — that is
     * {@link #isCoverageSettledByAnchors}.
     */
    static boolean isCoreReady(boolean splashReady, int feedHookCount,
                               boolean accessorsComplete) {
        return splashReady && feedHookCount > 0 && accessorsComplete;
    }

    /**
     * Fast coverage convergence: both historically known feed anchor classes
     * (EntityAdHelper and EntityListFragment) each contribute at least one
     * live installed hook. Strong-fingerprint candidates in additional
     * classes are installed in the same session they are discovered, so when
     * both anchors are live the currently visible DEX snapshot has been fully
     * harvested.
     */
    static boolean isCoverageSettledByAnchors(boolean adHelperHooked,
                                              boolean entityListFragmentHooked) {
        return adHelperHooked && entityListFragmentHooked;
    }

    static SessionOutcome sessionOutcome(boolean coreReady, boolean coverageSettled) {
        if (coreReady && coverageSettled) {
            return SessionOutcome.READY;
        }
        return coreReady ? SessionOutcome.RETRY_COVERAGE : SessionOutcome.RETRY_CORE;
    }

    /**
     * Terminal state at the 20s deadline. The deadline settles coverage by
     * definition, so a core-ready process finishes READY even without anchor
     * coverage; a core-incapable process is DEGRADED. READY is never degraded
     * because the watchdog returns early for READY.
     */
    static BootstrapState deadlineTerminalState(boolean coreReady) {
        return coreReady ? BootstrapState.READY : BootstrapState.DEGRADED;
    }

    /**
     * The 8s watchdog retries a session for EVERY non-terminal state. In
     * particular FULL_RESOLVE (core ready, coverage pending) must have a
     * watchdog-driven retry that does not depend on further class loading.
     */
    static boolean shouldWatchdogRetrySession(BootstrapState state) {
        return state != null && !state.isTerminal();
    }
}
