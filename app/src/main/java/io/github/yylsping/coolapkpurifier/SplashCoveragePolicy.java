package io.github.yylsping.coolapkpurifier;

/**
 * Pure splash UI-coverage readiness policy.
 *
 * <p>Readiness is decided exclusively by UI-layer cleaners. The decision
 * observer is a diagnostic capability: its absence never makes splash
 * coverage unavailable, and its presence never proves coverage.
 */
final class SplashCoveragePolicy {
    enum Coverage {
        /** Every known splash surface for this host has a live UI cleaner. */
        FULL,
        /** Embedded host without a live embedded cleaner; exact activities still covered. */
        PARTIAL,
        /** No UI cleaner is live at all. */
        NONE
    }

    static Coverage coverage(boolean activityInstalled, boolean embeddedHost,
                             boolean embeddedUiInstalled) {
        if (embeddedHost) {
            if (embeddedUiInstalled) {
                return Coverage.FULL;
            }
            return activityInstalled ? Coverage.PARTIAL : Coverage.NONE;
        }
        return activityInstalled ? Coverage.FULL : Coverage.NONE;
    }

    static boolean ready(boolean activityInstalled, boolean embeddedHost,
                         boolean embeddedUiInstalled) {
        return coverage(activityInstalled, embeddedHost, embeddedUiInstalled)
                != Coverage.NONE;
    }

    private SplashCoveragePolicy() {
    }
}
