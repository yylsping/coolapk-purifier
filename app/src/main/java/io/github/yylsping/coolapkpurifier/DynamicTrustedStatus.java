package io.github.yylsping.coolapkpurifier;

/**
 * Pure formatter for dynamic-feature terminal diagnostics.
 *
 * <p>Policy and physical state are deliberately separate: whether a fallback
 * is required cannot prove that an Instrumentation hook was installed, and a
 * completed retirement attempt cannot prove that an unhook succeeded.
 */
final class DynamicTrustedStatus {
    private DynamicTrustedStatus() {
    }

    static String summaryLine(BootstrapState state,
                              boolean splashEnabled,
                              boolean fallbackRequired,
                              boolean activitySplashHookInstalled,
                              boolean splashDecisionRequired,
                              boolean splashDecisionHookInstalled,
                              boolean instrumentationHookPresent,
                              boolean frameworkRetirePending,
                              boolean feedEnabled,
                              int feedHookCount,
                              boolean feedAccessorsComplete,
                              boolean feedCoverageSettled,
                              String failureReason,
                              String phase) {
        String unavailableReason = failureReason == null
                ? "resolutionIncomplete" : failureReason;
        boolean specificSplashHookInstalled = activitySplashHookInstalled
                && (!splashDecisionRequired || splashDecisionHookInstalled);
        StringBuilder sb = new StringBuilder("dynamicTrustedStatus phase=")
                .append(phase == null ? "terminal" : phase)
                .append(" terminalState=").append(state);

        sb.append(' ').append(PurifierConfig.Feature.SPLASH.key)
                .append("={enabledAtStart=").append(splashEnabled)
                .append(" fallbackRequired=").append(fallbackRequired)
                .append(" specificHookInstalled=").append(specificSplashHookInstalled)
                .append(" activityHookInstalled=").append(activitySplashHookInstalled)
                .append(" decisionRequired=").append(splashDecisionRequired)
                .append(" decisionHookInstalled=").append(splashDecisionHookInstalled)
                .append(" instrumentationHookPresent=").append(instrumentationHookPresent)
                .append(" frameworkRetirePending=").append(frameworkRetirePending);
        if (!splashEnabled) {
            sb.append(" availability=NOT_REQUIRED failureReason=-");
        } else {
            boolean ready = specificSplashHookInstalled && state == BootstrapState.READY;
            sb.append(" availability=").append(ready ? "READY" : "UNAVAILABLE")
                    .append(" failureReason=").append(ready ? "-" : unavailableReason);
        }
        sb.append('}');

        sb.append(' ').append(PurifierConfig.Feature.FEED_SPONSOR.key)
                .append("={enabledAtStart=").append(feedEnabled)
                .append(" hookCount=").append(feedHookCount)
                .append(" accessorsComplete=").append(feedAccessorsComplete)
                .append(" coverageSettled=").append(feedCoverageSettled);
        if (!feedEnabled) {
            sb.append(" availability=NOT_REQUIRED failureReason=-");
        } else {
            boolean ready = state == BootstrapState.READY
                    && feedHookCount > 0 && feedAccessorsComplete && feedCoverageSettled;
            sb.append(" availability=").append(ready ? "READY" : "UNAVAILABLE")
                    .append(" failureReason=").append(ready ? "-" : unavailableReason);
        }
        sb.append('}');
        return sb.toString();
    }
}
