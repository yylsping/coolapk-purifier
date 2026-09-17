package io.github.yylsping.coolapkpurifier;

/**
 * Pure formatter for dynamic-feature terminal diagnostics.
 *
 * <p>Policy and physical state are deliberately separate: whether a fallback
 * is required cannot prove that an Instrumentation hook was installed, and a
 * completed retirement attempt cannot prove that an unhook succeeded.
 *
 * <p>Splash reports UI-layer truth only: the decision observer is a
 * diagnostic capability and never feeds availability; staticCoverage is the
 * {@link SplashCoveragePolicy} verdict of the activity/embedded UI cleaners,
 * while embeddedDispatchState reports the runtime finish-dispatch outcome
 * (NOT_SEEN/SENT/CONFIRMED/FAILED) as a separate signal.
 */
final class DynamicTrustedStatus {
    private DynamicTrustedStatus() {
    }

    static String summaryLine(BootstrapState state,
                              boolean splashEnabled,
                              boolean fallbackRequired,
                              boolean activitySplashHookInstalled,
                              boolean embeddedSplashHost,
                              boolean embeddedUiHookInstalled,
                              String embeddedDispatchState,
                              boolean decisionObserverInstalled,
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
        SplashCoveragePolicy.Coverage splashCoverage = SplashCoveragePolicy.coverage(
                activitySplashHookInstalled, embeddedSplashHost, embeddedUiHookInstalled);
        StringBuilder sb = new StringBuilder("dynamicTrustedStatus phase=")
                .append(phase == null ? "terminal" : phase)
                .append(" terminalState=").append(state);

        sb.append(' ').append(PurifierConfig.Feature.SPLASH.key)
                .append("={enabledAtStart=").append(splashEnabled)
                .append(" fallbackRequired=").append(fallbackRequired)
                .append(" activityHookInstalled=").append(activitySplashHookInstalled)
                .append(" embeddedHost=").append(embeddedSplashHost)
                .append(" embeddedUiHookInstalled=").append(embeddedUiHookInstalled)
                .append(" decisionObserverInstalled=").append(decisionObserverInstalled)
                .append(" instrumentationHookPresent=").append(instrumentationHookPresent)
                .append(" frameworkRetirePending=").append(frameworkRetirePending)
                .append(" staticCoverage=").append(splashCoverage)
                .append(" embeddedDispatchState=")
                .append(embeddedDispatchState == null ? "NOT_SEEN" : embeddedDispatchState);
        if (!splashEnabled) {
            sb.append(" availability=NOT_REQUIRED failureReason=-");
        } else {
            boolean ready = state == BootstrapState.READY
                    && splashCoverage != SplashCoveragePolicy.Coverage.NONE;
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
