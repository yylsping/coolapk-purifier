package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class FeatureExposureLedgerTest {
    @Test
    public void firstEventsUseRelativeElapsedTimeAndEmitOnce() {
        FakeClock clock = new FakeClock(5_000L);
        List<String> emitted = new ArrayList<>();
        FeatureExposureLedger ledger = new FeatureExposureLedger(clock, emitted::add, 10);

        clock.advance(10);
        ledger.recordHookInstalled(PurifierConfig.Feature.FEED_SPONSOR);
        ledger.recordHookInstalled(PurifierConfig.Feature.FEED_SPONSOR);
        clock.advance(5);
        ledger.recordEntered(PurifierConfig.Feature.FEED_SPONSOR);
        ledger.recordEntered(PurifierConfig.Feature.FEED_SPONSOR);
        clock.advance(7);
        ledger.recordSampleSeen(PurifierConfig.Feature.FEED_SPONSOR);
        ledger.recordSampleSeen(PurifierConfig.Feature.FEED_SPONSOR);
        clock.advance(9);
        ledger.recordModified(PurifierConfig.Feature.FEED_SPONSOR);

        assertEquals(4, emitted.size());
        assertTrue(emitted.get(0).contains("event=FIRST_HOOK_INSTALLED"));
        assertTrue(emitted.get(1).contains("event=FIRST_ENTERED"));
        assertTrue(emitted.get(2).contains("event=FIRST_SAMPLE_SEEN"));
        assertTrue(emitted.get(3).contains("event=FIRST_MODIFIED"));

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("originElapsed=5000"));
        assertTrue(summary.contains("remove_feed_sponsor={hookInstalled=true"));
        assertTrue(summary.contains("firstHookInstalledAtElapsed=10"));
        assertTrue(summary.contains("firstEnteredAtElapsed=15"));
        assertTrue(summary.contains("firstSampleSeenAtElapsed=22"));
        assertTrue(summary.contains("firstModifiedAtElapsed=31"));
        assertTrue(summary.contains("modifiedCount=1"));
        assertTrue(summary.contains("lastModifiedAtElapsed=31"));
    }

    @Test
    public void modifiedCountSaturatesAtConfiguredBound() {
        FakeClock clock = new FakeClock(100L);
        List<String> emitted = new ArrayList<>();
        FeatureExposureLedger ledger = new FeatureExposureLedger(clock, emitted::add, 2);

        ledger.recordModified(PurifierConfig.Feature.AUTO_COMMENT);
        clock.advance(1);
        ledger.recordModified(PurifierConfig.Feature.AUTO_COMMENT);
        clock.advance(1);
        ledger.recordModified(PurifierConfig.Feature.AUTO_COMMENT);

        String summary = ledger.summaryLine("explicit");
        assertTrue(summary.contains("remove_auto_comment={hookInstalled=false"));
        assertTrue(summary.contains("modifiedCount=2"));
        assertTrue(summary.contains("modifiedCountCapped=true"));
        assertTrue(summary.contains("lastModifiedAtElapsed=2"));
        assertEquals("only the first modification is logged", 1, emitted.size());
    }

    @Test
    public void summaryContainsOnlyFixedFeatureDiagnostics() {
        FakeClock clock = new FakeClock(0L);
        List<String> emitted = new ArrayList<>();
        FeatureExposureLedger ledger = new FeatureExposureLedger(clock, emitted::add, 3);
        ledger.recordEntered(PurifierConfig.Feature.DETAIL_SPONSOR);

        String summary = ledger.summaryLine("diagnosticDump");
        assertTrue(summary.startsWith("featureExposure phase=diagnosticDump"));
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            assertTrue(summary.contains(feature.key + "={"));
        }
        assertFalse(summary.contains("token"));
        assertFalse(summary.contains("account"));
        assertFalse(summary.contains("payload"));
    }

    @Test
    public void emitterFailureCannotChangeFeatureAccounting() {
        FakeClock clock = new FakeClock(10L);
        FeatureExposureLedger ledger = new FeatureExposureLedger(
                clock, line -> { throw new IllegalStateException("log unavailable"); }, 3);

        ledger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
        ledger.recordEntered(PurifierConfig.Feature.SPLASH);
        ledger.recordSampleSeen(PurifierConfig.Feature.SPLASH);
        ledger.recordModified(PurifierConfig.Feature.SPLASH);

        String summary = ledger.summaryLine("terminal:READY");
        assertTrue(summary.contains("remove_splash_ads={hookInstalled=true"));
        assertTrue(summary.contains("modifiedCount=1"));
    }

    private static final class FakeClock implements FeatureExposureLedger.Clock {
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
