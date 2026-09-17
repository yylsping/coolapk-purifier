package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies an already topology-filtered dynamic target set to the business
 * hooks. Extracted from HookCoordinator so the cache-consumption → apply
 * boundary is unit-testable with fake hook sinks.
 *
 * <p>Only splash/feed keys drive installations here; getter keys flow into the
 * classifier exclusively through {@link Sink#updateAccessors}, which always
 * receives the complete merged target map.
 */
final class DynamicTargetApplier {
    interface Sink {
        /** Rebuilds the entity accessors from the COMPLETE merged target map. */
        void updateAccessors(Map<String, ResolvedTarget> merged, ClassLoader loader);

        /** Hooks one feed list transformer; true when a new hook went in. */
        boolean installFeed(Method method, ResolvedTarget target);

        /** Registers a resolved splash class with the splash gate. */
        void addSplashGateClass(Class<?> type);

        /** Installs the specific splash hook; true on success. */
        boolean installSplash(Class<?> type, ResolvedTarget target);

        /** Installs the exact embedded-splash decision observer; true on success. */
        boolean installSplashDecision(Method method, ResolvedTarget target);
    }

    static final class Outcome {
        int feedInstalled;
        final List<String> splashInstalled = new ArrayList<>();
        final List<String> splashUnresolved = new ArrayList<>();
        boolean splashDecisionInstalled;
    }

    private DynamicTargetApplier() {
    }

    static Outcome apply(Map<String, ResolvedTarget> targets,
                         Map<String, ResolvedTarget> merged,
                         ClassLoader loader, Sink sink, ModuleLog log, String source) {
        Outcome outcome = new Outcome();
        // Accessors must be rebuilt from the COMPLETE merged target map: an
        // earlier splash-only increment used to wipe the already verified
        // getters and fail the classifier closed for the first feed batches.
        sink.updateAccessors(merged, loader);

        for (Map.Entry<String, ResolvedTarget> entry : targets.entrySet()) {
            if (!TargetResolver.isFeedKey(entry.getKey())) {
                continue;
            }
            ResolvedTarget feed = entry.getValue();
            Method method = DescriptorUtils.methodForDescriptor(feed.methodDescriptor, loader);
            if (method != null) {
                if (sink.installFeed(method, feed)) {
                    outcome.feedInstalled++;
                }
            } else {
                log.info("feed descriptor not loadable source=" + source
                        + " key=" + entry.getKey() + " target=" + feed.describe());
            }
        }

        for (Map.Entry<String, ResolvedTarget> entry : targets.entrySet()) {
            if (!TargetResolver.isSplashKey(entry.getKey())) {
                continue;
            }
            ResolvedTarget splash = entry.getValue();
            try {
                Class<?> type = DescriptorUtils.classForName(splash.classDescriptor, loader);
                if (type == null) {
                    log.info("splash descriptor not loadable source=" + source
                            + " target=" + splash.describe());
                    continue;
                }
                sink.addSplashGateClass(type);
                if (sink.installSplash(type, splash)) {
                    outcome.splashInstalled.add(type.getName());
                } else {
                    outcome.splashUnresolved.add(type.getName());
                    log.info("splash specific hook not installed source=" + source
                            + " class=" + type.getName() + " frameworkFallback=true");
                }
            } catch (Throwable throwable) {
                log.info("splash descriptor not loadable yet source=" + source
                        + " target=" + splash.describe());
            }
        }

        ResolvedTarget decision = targets.get(TargetResolver.KEY_SPLASH_DECISION);
        if (decision != null) {
            Method method = DescriptorUtils.methodForDescriptor(
                    decision.methodDescriptor, loader);
            if (method != null) {
                outcome.splashDecisionInstalled =
                        sink.installSplashDecision(method, decision);
            } else {
                log.info("splash decision descriptor not loadable source=" + source
                        + " target=" + decision.describe());
            }
        }
        return outcome;
    }
}
