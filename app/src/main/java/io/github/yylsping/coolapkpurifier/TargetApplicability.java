package io.github.yylsping.coolapkpurifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Topology filter between resolved/cached dynamic targets and hook
 * installation (release-hardening §2).
 *
 * <p>A feature disabled at process start must never get its business hooks —
 * from a fresh DexKit resolution AND from a cache hit alike. This is the single
 * choke point: target sets pass through here before anything is applied.
 *
 * <p>Capability dependency: the {@code getter.*} entity accessors exist only
 * to serve the feed filtering pipeline ({@link EntityAccessors} →
 * {@link EntityClassifier} → {@link EntityListFilter}), so they follow the
 * FEED_SPONSOR capability, not the splash one.
 */
final class TargetApplicability {
    private TargetApplicability() {
    }

    /** True when this target key may be applied under the current topology. */
    static boolean isApplicable(String key, HookTopology topology) {
        if (topology == null) {
            // No topology snapshot (should not happen in a resolution session):
            // keep the previous unfiltered behaviour rather than dropping work.
            return true;
        }
        if (TargetResolver.isSplashKey(key)
                || TargetResolver.isSplashDecisionKey(key)) {
            return topology.needsSplash();
        }
        if (TargetResolver.isFeedKey(key) || isGetterKey(key)) {
            return topology.needsFeed();
        }
        // Unknown keys are inert: the applier only consumes splash/feed keys
        // and the accessor builder only reads getter keys.
        return true;
    }

    static boolean isGetterKey(String key) {
        return key != null && key.startsWith("getter.");
    }

    /**
     * The subset of {@code targets} that may drive hook installation in this
     * process. Never mutates the input.
     */
    static Map<String, ResolvedTarget> filter(Map<String, ResolvedTarget> targets,
                                              HookTopology topology) {
        if (targets == null || targets.isEmpty() || topology == null) {
            return targets;
        }
        Map<String, ResolvedTarget> applicable = new LinkedHashMap<>();
        for (Map.Entry<String, ResolvedTarget> entry : targets.entrySet()) {
            if (isApplicable(entry.getKey(), topology)) {
                applicable.put(entry.getKey(), entry.getValue());
            }
        }
        return applicable;
    }

    /**
     * Cache-write merge: targets of features disabled at start are never
     * resolved or applied this process, but the verified cache entries for
     * them must survive the save so a later process with a different topology
     * can still hit them. Entries the current session re-resolved (same key)
     * always win.
     */
    static Map<String, ResolvedTarget> mergeForSave(Map<String, ResolvedTarget> resolved,
                                                    Map<String, ResolvedTarget> verifiedCache,
                                                    HookTopology topology) {
        Map<String, ResolvedTarget> merged = new LinkedHashMap<>(resolved);
        if (verifiedCache == null || topology == null) {
            return merged;
        }
        for (Map.Entry<String, ResolvedTarget> entry : verifiedCache.entrySet()) {
            if (!merged.containsKey(entry.getKey())
                    && !isApplicable(entry.getKey(), topology)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }
}
