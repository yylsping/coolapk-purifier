package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Embedded UI cleaner gating: exact fragment only, feature switch required,
 * and no dependency on decision observation state. Per-instance duplicate
 * prevention is covered by {@link SplashEmbeddedDispatchTest}.
 */
public final class SplashEmbeddedPolicyTest {
    @Test
    public void onlyTheExactSplashAdFragmentMatches() {
        assertTrue(SplashEmbeddedPolicy.isExactFragmentClass(
                "com.coolapk.market.view.splash.SplashAdFragment"));
        assertFalse(SplashEmbeddedPolicy.isExactFragmentClass(
                "com.coolapk.market.view.cardlist.EntityListFragment"));
        assertFalse(SplashEmbeddedPolicy.isExactFragmentClass(
                "com.coolapk.market.view.splash.SplashAdActivity"));
        assertFalse(SplashEmbeddedPolicy.isExactFragmentClass(
                "com.coolapk.market.view.main.MainActivity"));
        assertFalse(SplashEmbeddedPolicy.isExactFragmentClass(null));
    }

    @Test
    public void suppressionRequiresSwitchAndAddedInstance() {
        assertTrue(SplashEmbeddedPolicy.shouldSuppress(true, true));
        assertFalse("feature off", SplashEmbeddedPolicy.shouldSuppress(false, true));
        assertFalse("not added", SplashEmbeddedPolicy.shouldSuppress(true, false));
    }

    @Test
    public void policyInputsCarryNoDecisionObservationDependency() {
        // The UI cleaner must work with zero decision observations: the policy
        // signature has no observation input, so both switch states fully
        // decide the outcome on their own.
        assertTrue(SplashEmbeddedPolicy.shouldSuppress(true, true));
        assertFalse(SplashEmbeddedPolicy.shouldSuppress(false, true));
    }
}
