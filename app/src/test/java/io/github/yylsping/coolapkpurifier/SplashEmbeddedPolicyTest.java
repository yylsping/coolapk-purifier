package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Embedded UI cleaner gating: exact fragment only, feature switch required,
 * one signal per instance, and no dependency on decision observation state.
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
    public void suppressionRequiresSwitchAddedInstanceAndNoPriorSignal() {
        assertTrue(SplashEmbeddedPolicy.shouldSuppress(true, true, false));
        assertFalse("feature off", SplashEmbeddedPolicy.shouldSuppress(false, true, false));
        assertFalse("not added", SplashEmbeddedPolicy.shouldSuppress(true, false, false));
        assertFalse("already signalled",
                SplashEmbeddedPolicy.shouldSuppress(true, true, true));
    }

    @Test
    public void policyInputsCarryNoDecisionObservationDependency() {
        // The UI cleaner must work with zero decision observations: the policy
        // signature has no observation input, so both switch states fully
        // decide the outcome on their own.
        assertTrue(SplashEmbeddedPolicy.shouldSuppress(true, true, false));
        assertFalse(SplashEmbeddedPolicy.shouldSuppress(false, true, false));
    }
}
