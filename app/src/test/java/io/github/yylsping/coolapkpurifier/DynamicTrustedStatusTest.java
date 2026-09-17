package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DynamicTrustedStatusTest {
    @Test
    public void degradedFallbackInstallSuccessReportsPhysicalPresence() {
        String status = status(BootstrapState.DEGRADED,
                true, true, false, true, false, "terminalCleanup");

        assertTrue(status.contains("fallbackRequired=true"));
        assertTrue(status.contains("activityHookInstalled=false"));
        assertTrue(status.contains("staticCoverage=NONE"));
        assertTrue(status.contains("instrumentationHookPresent=true"));
        assertTrue(status.contains("frameworkRetirePending=false"));
        assertTrue(status.contains("availability=UNAVAILABLE"));
    }

    @Test
    public void fallbackInstallFailureDoesNotMasqueradeAsRetainedHook() {
        String status = status(BootstrapState.DEGRADED,
                true, true, false, false, false, "terminalCleanup");

        assertTrue(status.contains("fallbackRequired=true"));
        assertTrue(status.contains("instrumentationHookPresent=false"));
    }

    @Test
    public void readyActivityCleanerAfterSuccessfulRetirementReportsAbsentFrameworkHook() {
        String status = status(BootstrapState.READY,
                true, true, true, false, false, "postRetirement");

        assertTrue(status.contains("phase=postRetirement terminalState=READY"));
        assertTrue(status.contains("activityHookInstalled=true"));
        assertTrue(status.contains("staticCoverage=FULL"));
        assertTrue(status.contains("instrumentationHookPresent=false"));
        assertTrue(status.contains("frameworkRetirePending=false"));
        assertTrue(status.contains("availability=READY"));
    }

    @Test
    public void unhookFailureKeepsLedgerAndPhysicalStatusActive() {
        HookLedger ledger = new HookLedger();
        ledger.record(HookLedger.Layer.FRAMEWORK, "splash",
                "coolapk-activity-create-2", "Instrumentation.callActivityOnCreate");

        String status = status(BootstrapState.READY,
                true, true, true, true, false, "postRetirement");
        String hookSummary = ledger.summaryLine("terminal:READY");

        assertTrue(status.contains("instrumentationHookPresent=true"));
        assertTrue(hookSummary.contains("frameworkActive=true"));
        assertTrue(hookSummary.contains("frameworkActiveHooks=[coolapk-activity-create-2]"));
    }

    @Test
    public void preRetirementTerminalSummaryMarksPendingAttempt() {
        String status = status(BootstrapState.READY,
                true, true, true, true, true, "terminalCleanup");

        assertTrue(status.contains("instrumentationHookPresent=true"));
        assertTrue(status.contains("frameworkRetirePending=true"));
    }

    @Test
    public void decisionObserverIsDiagnosticsAndNeverGatesAvailability() {
        // Embedded host, embedded UI cleaner live, observer missing: READY.
        String observerMissing = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, false, true, true, "REMOVAL_OBSERVED", false, false, false,
                false, 0, false, true, "observerMissing", "terminalCleanup");
        assertTrue(observerMissing.contains("embeddedHost=true"));
        assertTrue(observerMissing.contains("embeddedUiHookInstalled=true"));
        assertTrue(observerMissing.contains("embeddedDispatchState=REMOVAL_OBSERVED"));
        assertTrue(observerMissing.contains("decisionObserverInstalled=false"));
        assertTrue(observerMissing.contains("staticCoverage=FULL"));
        assertTrue(observerMissing.contains("availability=READY"));

        // Non-embedded host, activity cleaner live, observer missing: READY.
        String activityOnly = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, true, false, false, "NOT_SEEN", false, false, false,
                false, 0, false, true, null, "postRetirement");
        assertTrue(activityOnly.contains("staticCoverage=FULL"));
        assertTrue(activityOnly.contains("embeddedDispatchState=NOT_SEEN"));
        assertTrue(activityOnly.contains("availability=READY"));
    }

    @Test
    public void embeddedHostWithoutEmbeddedCleanerReportsPartialCoverage() {
        String partial = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, true, true, false, "UNCONFIRMED", true, false, false,
                false, 0, false, true, "embeddedUiMissing", "terminalCleanup");
        assertTrue(partial.contains("embeddedHost=true"));
        assertTrue(partial.contains("embeddedUiHookInstalled=false"));
        assertTrue(partial.contains("embeddedDispatchState=UNCONFIRMED"));
        assertTrue(partial.contains("decisionObserverInstalled=true"));
        assertTrue(partial.contains("staticCoverage=PARTIAL"));
        assertTrue(partial.contains("availability=READY"));
    }

    @Test
    public void nullDispatchStateFallsBackToNotSeen() {
        String status = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, true, false, false, null, false, false, false,
                false, 0, false, true, null, "postRetirement");
        assertTrue(status.contains("embeddedDispatchState=NOT_SEEN"));
    }

    private static String status(BootstrapState state,
                                 boolean splashEnabled,
                                 boolean fallbackRequired,
                                 boolean activityInstalled,
                                 boolean instrumentationPresent,
                                 boolean retirePending,
                                 String phase) {
        return DynamicTrustedStatus.summaryLine(
                state,
                splashEnabled,
                fallbackRequired,
                activityInstalled,
                false,
                false,
                "NOT_SEEN",
                false,
                instrumentationPresent,
                retirePending,
                false,
                0,
                false,
                true,
                "testFailure",
                phase);
    }
}
