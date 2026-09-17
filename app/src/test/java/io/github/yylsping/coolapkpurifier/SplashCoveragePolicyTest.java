package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Splash readiness is UI-cleaner truth only. The decision observer is
 * diagnostics and appears nowhere in these inputs.
 */
public final class SplashCoveragePolicyTest {
    @Test
    public void nonEmbeddedHostIsFullWithActivityCleanerOnly() {
        assertEquals(SplashCoveragePolicy.Coverage.FULL,
                SplashCoveragePolicy.coverage(true, false, false));
        assertEquals(SplashCoveragePolicy.Coverage.NONE,
                SplashCoveragePolicy.coverage(false, false, false));
    }

    @Test
    public void embeddedHostIsFullOnlyWithEmbeddedCleaner() {
        assertEquals(SplashCoveragePolicy.Coverage.FULL,
                SplashCoveragePolicy.coverage(true, true, true));
        assertEquals(SplashCoveragePolicy.Coverage.FULL,
                SplashCoveragePolicy.coverage(false, true, true));
    }

    @Test
    public void embeddedHostWithoutEmbeddedCleanerDegradesToPartial() {
        assertEquals(SplashCoveragePolicy.Coverage.PARTIAL,
                SplashCoveragePolicy.coverage(true, true, false));
        assertEquals(SplashCoveragePolicy.Coverage.NONE,
                SplashCoveragePolicy.coverage(false, true, false));
    }

    @Test
    public void readyMeansAtLeastOneUiCleanerLive() {
        assertTrue(SplashCoveragePolicy.ready(true, false, false));
        assertTrue(SplashCoveragePolicy.ready(false, true, true));
        assertTrue("PARTIAL still works at the UI layer",
                SplashCoveragePolicy.ready(true, true, false));
        assertFalse(SplashCoveragePolicy.ready(false, false, false));
        assertFalse(SplashCoveragePolicy.ready(false, true, false));
    }
}
