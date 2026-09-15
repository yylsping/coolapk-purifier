package io.github.yylsping.coolapkpurifier;

import java.util.function.BooleanSupplier;

/**
 * Preserves host execution, exceptions and false results. Suppression happens
 * only after the exact Coolapk decision method completed successfully.
 */
final class SplashDecisionPolicy {
    interface Proceed {
        Object call() throws Throwable;
    }

    interface Observation {
        void completed(Object original, Object returned, boolean enabled);
    }

    static Object intercept(Proceed proceed, BooleanSupplier enabled,
                            Observation observation) throws Throwable {
        Object original = proceed.call();
        boolean suppress;
        try {
            suppress = enabled.getAsBoolean();
        } catch (Throwable unavailable) {
            suppress = false;
        }
        Object returned = suppress && Boolean.TRUE.equals(original) ? Boolean.FALSE : original;
        try {
            observation.completed(original, returned, suppress);
        } catch (Throwable ignored) {
            // Diagnostics must not alter the host result or replay the host.
        }
        return returned;
    }

    static boolean ready(boolean activityInstalled, boolean embeddedHost,
                         boolean decisionInstalled) {
        return activityInstalled && (!embeddedHost || decisionInstalled);
    }

    private SplashDecisionPolicy() {
    }
}
