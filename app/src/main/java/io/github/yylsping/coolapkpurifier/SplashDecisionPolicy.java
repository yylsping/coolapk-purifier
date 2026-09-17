package io.github.yylsping.coolapkpurifier;

/**
 * OBSERVE_ONLY policy for Coolapk's splash ad decision method.
 *
 * <p>The original method always runs and its boolean result is returned to
 * the host unchanged — regardless of any module switch. This module never
 * overrides the host's business decision; suppression lives exclusively in
 * the UI layer (exact splash activities, exact embedded splash fragment).
 * The observation callback is purely diagnostic: its failure can neither
 * change the returned value nor replay the host.
 */
final class SplashDecisionPolicy {
    static final String MODE = "OBSERVE_ONLY";

    interface Proceed {
        Object call() throws Throwable;
    }

    interface Observation {
        void completed(Object original, Object returned);
    }

    static Object intercept(Proceed proceed, Observation observation) throws Throwable {
        Object original = proceed.call();
        try {
            observation.completed(original, original);
        } catch (Throwable ignored) {
            // Diagnostics must not alter the host result or replay the host.
        }
        return original;
    }

    private SplashDecisionPolicy() {
    }
}
