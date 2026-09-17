package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision observation, activity finish and embedded suppression are separate
 * event families: observations never increment the UI modification counters.
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
        assertEquals(0, ledger.embeddedUiSuppressedCount());

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("decisionObserved=3"));
        assertTrue(summary.contains("decisionOriginalTrue=2"));
        assertTrue(summary.contains("decisionOriginalFalse=1"));
        assertTrue(summary.contains("activityFinished=0"));
        assertTrue(summary.contains("embeddedUiSuppressed=0"));
    }

    @Test
    public void activityAndEmbeddedModificationsAreCountedSeparately() {
        FakeClock clock = new FakeClock(0L);
        List<String> emitted = new ArrayList<>();
        SplashUiLedger ledger = new SplashUiLedger(clock, emitted::add);

        ledger.recordActivityEntered();
        ledger.recordActivityFinished();
        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedUiSuppressed();
        ledger.recordEmbeddedUiEntered();
        ledger.recordEmbeddedUiSuppressed();

        assertEquals(0, ledger.decisionObservedCount());
        assertEquals(1, ledger.activityFinishedCount());
        assertEquals(2, ledger.embeddedUiSuppressedCount());

        String summary = ledger.summaryLine("explicit");
        assertTrue(summary.contains("activityEntered=1"));
        assertTrue(summary.contains("activityFinished=1"));
        assertTrue(summary.contains("embeddedUiEntered=2"));
        assertTrue(summary.contains("embeddedUiSuppressed=2"));
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
        ledger.recordEmbeddedUiSuppressed();

        assertEquals(5, emitted.size());
        assertTrue(emitted.get(0).contains("splashUi event=FIRST_DECISION_OBSERVED original=true"));
        assertTrue(emitted.get(1).contains("event=FIRST_ACTIVITY_ENTERED"));
        assertTrue(emitted.get(2).contains("event=FIRST_ACTIVITY_FINISHED"));
        assertTrue(emitted.get(3).contains("event=FIRST_EMBEDDED_UI_ENTERED"));
        assertTrue(emitted.get(4).contains("event=FIRST_EMBEDDED_UI_SUPPRESSED"));

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("originElapsed=100"));
        assertTrue(summary.contains("firstDecisionObservedAtElapsed=10"));
        assertTrue(summary.contains("firstActivityEnteredAtElapsed=15"));
        assertTrue(summary.contains("firstActivityFinishedAtElapsed=15"));
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
