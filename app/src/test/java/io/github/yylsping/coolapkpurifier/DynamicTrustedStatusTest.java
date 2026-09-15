package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DynamicTrustedStatusTest {
    @Test
    public void degradedFallbackInstallSuccessReportsPhysicalPresence() {
        String status = status(BootstrapState.DEGRADED,
                true, true, false, true, false, "terminalCleanup");

        assertTrue(status.contains("fallbackRequired=true"));
        assertTrue(status.contains("specificHookInstalled=false"));
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
    public void readySpecificHookAfterSuccessfulRetirementReportsAbsentFrameworkHook() {
        String status = status(BootstrapState.READY,
                true, true, true, false, false, "postRetirement");

        assertTrue(status.contains("phase=postRetirement terminalState=READY"));
        assertTrue(status.contains("specificHookInstalled=true"));
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
    public void embeddedSplashRequiresBothActivityAndDecisionHooks() {
        String incomplete = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, true, true, false, true, false,
                false, 0, false, true, "decisionMissing", "terminalCleanup");
        assertTrue(incomplete.contains("specificHookInstalled=false"));
        assertTrue(incomplete.contains("activityHookInstalled=true"));
        assertTrue(incomplete.contains("decisionRequired=true"));
        assertTrue(incomplete.contains("decisionHookInstalled=false"));
        assertTrue(incomplete.contains("availability=UNAVAILABLE"));

        String complete = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                true, true, true, true, true, false, false,
                false, 0, false, true, null, "postRetirement");
        assertTrue(complete.contains("specificHookInstalled=true"));
        assertTrue(complete.contains("decisionHookInstalled=true"));
        assertTrue(complete.contains("availability=READY"));
    }

    private static String status(BootstrapState state,
                                 boolean splashEnabled,
                                 boolean fallbackRequired,
                                 boolean specific,
                                 boolean instrumentationPresent,
                                 boolean retirePending,
                                 String phase) {
        return DynamicTrustedStatus.summaryLine(
                state,
                splashEnabled,
                fallbackRequired,
                specific,
                false,
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
