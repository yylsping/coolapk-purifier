package io.github.yylsping.coolapkpurifier;

import android.os.SystemClock;

/**
 * Process-local splash event ledger with separated counters.
 *
 * <p>Decision observations, exact-Activity finishes and embedded finish
 * dispatch are distinct event families: a decision observation is never a UI
 * modification, and only real UI-layer actions (activity finish, embedded
 * finish signal) increment the modification counters. For the embedded path
 * the ledger deliberately distinguishes "finish signal submitted to the
 * FragmentManager" (SIGNAL_SENT) from the module's own delayed observation
 * that the fragment is gone (REMOVAL_OBSERVED); a bare signal is never
 * reported as removal, and a local observation is never reported as a
 * host-side acknowledgement. UNCONFIRMED (still added after the observation
 * window), OBSERVATION_FAILED (the isAdded probe itself was unreadable) and
 * DISPATCH_FAILED (the signal could not be submitted) are tracked as three
 * separate outcomes.
 *
 * <p>Emits one line per first occurrence; full snapshots are emitted by the
 * coordinator at terminal lifecycle points. Elapsed-realtime clock only; no
 * wall-clock, account or host payload data.
 */
final class SplashUiLedger {
    private static final long UNSET = -1L;

    interface Clock {
        long elapsedRealtime();
    }

    interface Emitter {
        void emit(String line);
    }

    private final Clock clock;
    private final Emitter emitter;
    private final long originElapsed;

    private int decisionObservedCount;
    private int decisionOriginalTrueCount;
    private int decisionOriginalFalseCount;
    private int activityEnteredCount;
    private int activityFinishedCount;
    private int embeddedUiEnteredCount;
    private int embeddedFinishSignalSentCount;
    private int embeddedRemovalObservedCount;
    private int embeddedFinishUnconfirmedCount;
    private int embeddedRemovalObservationFailedCount;
    private int embeddedFinishDispatchFailedCount;
    private long firstDecisionObservedAtElapsed = UNSET;
    private long firstActivityEnteredAtElapsed = UNSET;
    private long firstActivityFinishedAtElapsed = UNSET;
    private long firstEmbeddedUiEnteredAtElapsed = UNSET;
    private long firstEmbeddedFinishSignalSentAtElapsed = UNSET;
    private long firstEmbeddedRemovalObservedAtElapsed = UNSET;
    private long firstEmbeddedFinishUnconfirmedAtElapsed = UNSET;
    private long firstEmbeddedRemovalObservationFailedAtElapsed = UNSET;
    private long firstEmbeddedFinishDispatchFailedAtElapsed = UNSET;

    SplashUiLedger(Emitter emitter) {
        this(SystemClock::elapsedRealtime, emitter);
    }

    SplashUiLedger(Clock clock, Emitter emitter) {
        if (clock == null) {
            throw new IllegalArgumentException("clock required");
        }
        this.clock = clock;
        this.emitter = emitter;
        this.originElapsed = clock.elapsedRealtime();
    }

    /** The host decision method ran; {@code original} is its untouched result. */
    synchronized void recordDecisionObserved(boolean original) {
        decisionObservedCount++;
        if (original) {
            decisionOriginalTrueCount++;
        } else {
            decisionOriginalFalseCount++;
        }
        if (firstDecisionObservedAtElapsed == UNSET) {
            firstDecisionObservedAtElapsed = nowRelative();
            emitFirst("DECISION_OBSERVED", "original=" + original);
        }
    }

    /** An exact splash Activity matched the finish gate. */
    synchronized void recordActivityEntered() {
        activityEnteredCount++;
        if (firstActivityEnteredAtElapsed == UNSET) {
            firstActivityEnteredAtElapsed = nowRelative();
            emitFirst("ACTIVITY_ENTERED", null);
        }
    }

    /** An exact splash Activity was actually finished by the UI cleaner. */
    synchronized void recordActivityFinished() {
        activityFinishedCount++;
        if (firstActivityFinishedAtElapsed == UNSET) {
            firstActivityFinishedAtElapsed = nowRelative();
            emitFirst("ACTIVITY_FINISHED", null);
        }
    }

    /** The exact embedded splash fragment completed its original lifecycle. */
    synchronized void recordEmbeddedUiEntered() {
        embeddedUiEnteredCount++;
        if (firstEmbeddedUiEnteredAtElapsed == UNSET) {
            firstEmbeddedUiEnteredAtElapsed = nowRelative();
            emitFirst("EMBEDDED_UI_ENTERED", null);
        }
    }

    /**
     * The host-native finish signal was successfully submitted to the
     * FragmentManager. This does NOT prove the host consumed it or removed
     * the splash UI; see {@link #recordEmbeddedRemovalObserved()}.
     */
    synchronized void recordEmbeddedFinishSignalSent() {
        embeddedFinishSignalSentCount++;
        if (firstEmbeddedFinishSignalSentAtElapsed == UNSET) {
            firstEmbeddedFinishSignalSentAtElapsed = nowRelative();
            emitFirst("EMBEDDED_FINISH_SIGNAL_SENT", "payloadSource=HOST_NATIVE");
        }
    }

    /**
     * The module's delayed observation saw the embedded splash fragment no
     * longer added. This is a local observation, not a host acknowledgement.
     */
    synchronized void recordEmbeddedRemovalObserved() {
        embeddedRemovalObservedCount++;
        if (firstEmbeddedRemovalObservedAtElapsed == UNSET) {
            firstEmbeddedRemovalObservedAtElapsed = nowRelative();
            emitFirst("EMBEDDED_REMOVAL_OBSERVED", null);
        }
    }

    /**
     * The observation window elapsed with the fragment still added. The
     * accepted result may still be delivered later by the host, so this is
     * not a failure and never triggers an automatic retry.
     */
    synchronized void recordEmbeddedFinishUnconfirmed() {
        embeddedFinishUnconfirmedCount++;
        if (firstEmbeddedFinishUnconfirmedAtElapsed == UNSET) {
            firstEmbeddedFinishUnconfirmedAtElapsed = nowRelative();
            emitFirst("EMBEDDED_FINISH_UNCONFIRMED", null);
        }
    }

    /**
     * The delayed observation itself failed: the reflective isAdded probe
     * was unreadable. Says nothing about the actual UI state and never
     * reclassifies the dispatch.
     */
    synchronized void recordEmbeddedRemovalObservationFailed() {
        embeddedRemovalObservationFailedCount++;
        if (firstEmbeddedRemovalObservationFailedAtElapsed == UNSET) {
            firstEmbeddedRemovalObservationFailedAtElapsed = nowRelative();
            emitFirst("EMBEDDED_REMOVAL_OBSERVATION_FAILED", null);
        }
    }

    /** Submitting the finish signal itself failed (exception). */
    synchronized void recordEmbeddedFinishDispatchFailed() {
        embeddedFinishDispatchFailedCount++;
        if (firstEmbeddedFinishDispatchFailedAtElapsed == UNSET) {
            firstEmbeddedFinishDispatchFailedAtElapsed = nowRelative();
            emitFirst("EMBEDDED_FINISH_DISPATCH_FAILED", null);
        }
    }

    synchronized int decisionObservedCount() {
        return decisionObservedCount;
    }

    synchronized int activityFinishedCount() {
        return activityFinishedCount;
    }

    synchronized int embeddedFinishSignalSentCount() {
        return embeddedFinishSignalSentCount;
    }

    synchronized int embeddedRemovalObservedCount() {
        return embeddedRemovalObservedCount;
    }

    synchronized int embeddedFinishUnconfirmedCount() {
        return embeddedFinishUnconfirmedCount;
    }

    synchronized int embeddedRemovalObservationFailedCount() {
        return embeddedRemovalObservationFailedCount;
    }

    synchronized int embeddedFinishDispatchFailedCount() {
        return embeddedFinishDispatchFailedCount;
    }

    /** Complete, deterministic one-line snapshot; no host data is accepted. */
    synchronized String summaryLine(String phase) {
        return "splashUiLedger phase=" + (phase == null ? "explicit" : phase)
                + " originElapsed=" + originElapsed
                + " decisionObserved=" + decisionObservedCount
                + " decisionOriginalTrue=" + decisionOriginalTrueCount
                + " decisionOriginalFalse=" + decisionOriginalFalseCount
                + " activityEntered=" + activityEnteredCount
                + " activityFinished=" + activityFinishedCount
                + " embeddedUiEntered=" + embeddedUiEnteredCount
                + " embeddedFinishSignalSent=" + embeddedFinishSignalSentCount
                + " embeddedRemovalObserved=" + embeddedRemovalObservedCount
                + " embeddedFinishUnconfirmed=" + embeddedFinishUnconfirmedCount
                + " embeddedRemovalObservationFailed=" + embeddedRemovalObservationFailedCount
                + " embeddedFinishDispatchFailed=" + embeddedFinishDispatchFailedCount
                + " firstDecisionObservedAtElapsed=" + display(firstDecisionObservedAtElapsed)
                + " firstActivityEnteredAtElapsed=" + display(firstActivityEnteredAtElapsed)
                + " firstActivityFinishedAtElapsed=" + display(firstActivityFinishedAtElapsed)
                + " firstEmbeddedUiEnteredAtElapsed=" + display(firstEmbeddedUiEnteredAtElapsed)
                + " firstEmbeddedFinishSignalSentAtElapsed="
                + display(firstEmbeddedFinishSignalSentAtElapsed)
                + " firstEmbeddedRemovalObservedAtElapsed="
                + display(firstEmbeddedRemovalObservedAtElapsed)
                + " firstEmbeddedFinishUnconfirmedAtElapsed="
                + display(firstEmbeddedFinishUnconfirmedAtElapsed)
                + " firstEmbeddedRemovalObservationFailedAtElapsed="
                + display(firstEmbeddedRemovalObservationFailedAtElapsed)
                + " firstEmbeddedFinishDispatchFailedAtElapsed="
                + display(firstEmbeddedFinishDispatchFailedAtElapsed);
    }

    private long nowRelative() {
        return Math.max(0L, clock.elapsedRealtime() - originElapsed);
    }

    private void emitFirst(String event, String detail) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emit("splashUi event=FIRST_" + event
                    + (detail == null ? "" : " " + detail));
        } catch (Throwable ignored) {
            // Diagnostics must never change host behavior or hook outcomes.
        }
    }

    private static String display(long value) {
        return value == UNSET ? "-" : Long.toString(value);
    }
}
