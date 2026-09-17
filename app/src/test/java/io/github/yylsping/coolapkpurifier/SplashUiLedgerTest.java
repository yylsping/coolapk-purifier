package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision observation, activity finish and embedded finish dispatch are
 * separate event families: observations never increment the UI modification
 * counters, a submitted finish signal is never reported as removal, and a
 * local delayed observation is never reported as a host acknowledgement.
 */
public final class SplashUiLedgerTest {
    @Test
    public void decisionObservationsNeverCountAsUiModifications() {
        FakeClock clock = new FakeClock(1_000L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        ledger.recordDecisionObserved(true);
        ledger.recordDecisionObserved(false);
        ledger.recordDecisionObserved(true);

        assertEquals(3, ledger.decisionObservedCount());
        assertEquals(0, ledger.activityFinishedCount());
        assertEquals(0, ledger.embeddedFinishSignalSentCount());
        assertEquals(0, ledger.embeddedRemovalObservedCount());

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("decisionObserved=3"));
        assertTrue(summary.contains("decisionOriginalTrue=2"));
        assertTrue(summary.contains("decisionOriginalFalse=1"));
        assertTrue(summary.contains("activityFinished=0"));
        assertTrue(summary.contains("embeddedFinishSignalSent=0"));
        assertTrue(summary.contains("embeddedRemovalObserved=0"));
        assertTrue(summary.contains("embeddedFinishUnconfirmed=0"));
        assertTrue(summary.contains("embeddedFinishDispatchFailed=0"));
    }

    @Test
    public void activityAndEmbeddedModificationsAreCountedSeparately() {
        FakeClock clock = new FakeClock(0L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        ledger.recordActivityEntered();
        ledger.recordActivityFinished();
        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedFinishSignalSent();
        ledger.recordEmbeddedRemovalObserved();
        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedFinishSignalSent();
        ledger.recordEmbeddedFinishUnconfirmed();

        assertEquals(0, ledger.decisionObservedCount());
        assertEquals(1, ledger.activityFinishedCount());
        assertEquals(2, ledger.embeddedFinishSignalSentCount());
        assertEquals(1, ledger.embeddedRemovalObservedCount());
        assertEquals(1, ledger.embeddedFinishUnconfirmedCount());
        assertEquals(0, ledger.embeddedFinishDispatchFailedCount());

        String summary = ledger.summaryLine("explicit");
        assertTrue(summary.contains("activityEntered=1"));
        assertTrue(summary.contains("activityFinished=1"));
        assertTrue(summary.contains("embeddedUiEntered=2"));
        assertTrue(summary.contains("embeddedFinishSignalSent=2"));
        assertTrue(summary.contains("embeddedRemovalObserved=1"));
        assertTrue(summary.contains("embeddedFinishUnconfirmed=1"));
        assertTrue(summary.contains("embeddedFinishDispatchFailed=0"));
    }

    @Test
    public void signalSentIsNotRemovalUntilObserved() {
        FakeClock clock = new FakeClock(0L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedFinishSignalSent();

        assertEquals(1, ledger.embeddedFinishSignalSentCount());
        assertEquals("bare signal must not imply removal",
                0, ledger.embeddedRemovalObservedCount());

        ledger.recordEmbeddedRemovalObserved();
        assertEquals(1, ledger.embeddedRemovalObservedCount());
    }

    @Test
    public void unconfirmedIsDistinctFromDispatchFailure() {
        FakeClock clock = new FakeClock(0L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        ledger.recordEmbeddedFinishUnconfirmed();
        ledger.recordEmbeddedFinishDispatchFailed();

        assertEquals(1, ledger.embeddedFinishUnconfirmedCount());
        assertEquals(1, ledger.embeddedFinishDispatchFailedCount());
        assertTrue(emitted.get(0).contains("event=FIRST_EMBEDDED_FINISH_UNCONFIRMED"));
        assertTrue(emitted.get(1).contains("event=FIRST_EMBEDDED_FINISH_DISPATCH_FAILED"));
    }

    @Test
    public void firstEventsEmitOnceWithRelativeElapsed() {
        FakeClock clock = new FakeClock(100L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        clock.advance(10);
        ledger.recordDecisionObserved(true);
        ledger.recordDecisionObserved(true);
        clock.advance(5);
        ledger.recordActivityEntered();
        ledger.recordActivityEntered();
        ledger.recordActivityFinished();
        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedFinishSignalSent();
        ledger.recordEmbeddedFinishSignalSent();
        ledger.recordEmbeddedRemovalObserved();

        assertEquals(6, emitted.size());
        assertTrue(emitted.get(0).contains("splashUi event=FIRST_DECISION_OBSERVED original=true"));
        assertTrue(emitted.get(1).contains("event=FIRST_ACTIVITY_ENTERED"));
        assertTrue(emitted.get(2).contains("event=FIRST_ACTIVITY_FINISHED"));
        assertTrue(emitted.get(3).contains("event=FIRST_EMBEDDED_UI_ENTERED"));
        assertTrue(emitted.get(4).contains(
                "event=FIRST_EMBEDDED_FINISH_SIGNAL_SENT payloadSource=HOST_NATIVE"));
        assertTrue(emitted.get(5).contains("event=FIRST_EMBEDDED_REMOVAL_OBSERVED"));

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("originElapsed=100"));
        assertTrue(summary.contains("firstDecisionObservedAtElapsed=10"));
        assertTrue(summary.contains("firstActivityEnteredAtElapsed=15"));
        assertTrue(summary.contains("firstActivityFinishedAtElapsed=15"));
        assertTrue(summary.contains("firstEmbeddedFinishSignalSentAtElapsed=15"));
        assertTrue(summary.contains("firstEmbeddedRemovalObservedAtElapsed=15"));
    }

    private static final class FakeClock implements SplashUiLedger.Clock {
        private long now;

        FakeClock(long now) {
            this.now = now;
        }

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long elapsedRealtime() {
            return now;
        }
    }
}
