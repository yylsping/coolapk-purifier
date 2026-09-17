package io.github.yylsping.coolapkpurifier;

/**
 * Pure gating rules for the embedded splash UI cleaner. Exact-class
 * identification only; never widened to fuzzy names or ordinary fragments.
 */
final class SplashEmbeddedPolicy {
    /** Exact embedded splash fragment of the MainActivity-hosted container. */
    static final String FRAGMENT_CLASS = "com.coolapk.market.view.splash.SplashAdFragment";

    static boolean isExactFragmentClass(String className) {
        return FRAGMENT_CLASS.equals(className);
    }

    /**
     * Suppression requires the feature switch on and the fragment actually
     * added to its host. Duplicate-signal prevention is owned by
     * {@link SplashEmbeddedDispatch}, not by this gate. The decision observer
     * is deliberately absent from the inputs: UI suppression never depends on
     * observation state.
     */
    static boolean shouldSuppress(boolean featureEnabled, boolean fragmentAdded) {
        return featureEnabled && fragmentAdded;
    }

    private SplashEmbeddedPolicy() {
    }
}
