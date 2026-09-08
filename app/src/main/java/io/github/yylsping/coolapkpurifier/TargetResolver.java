package io.github.yylsping.coolapkpurifier;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared target keys. The actual resolvers are split by startup priority. */
final class TargetResolver {
    static final String KEY_FEED = "feed";
    static final String KEY_SPLASH_BASE = "splash_base";
    static final String KEY_GETTER_TEMPLATE = "getter.entityTemplate";
    static final String KEY_GETTER_ENTITY_ID = "getter.entityId";
    static final String KEY_GETTER_TITLE = "getter.title";
    static final String KEY_GETTER_ENTITY_TYPE = "getter.entityType";

    /**
     * Historically observed splash-family activity names. Used as a reflection
     * fallback so known versions keep splash coverage even when the DexKit
     * fingerprint tiers find nothing (e.g. obfuscated source metadata).
     */
    static final Set<String> LEGACY_SPLASH_CLASS_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.coolapk.market.view.splash.SplashActivity",
                    "com.coolapk.market.view.splash.SplashAdActivity",
                    "com.coolapk.market.view.splash.FullScreenAdActivity")));

    private TargetResolver() {
    }

    /** Feed entries may be suffixed (feed#2, feed#3...) for multi-method coverage. */
    static boolean isFeedKey(String key) {
        return key != null && (key.equals(KEY_FEED) || key.startsWith(KEY_FEED + "#"));
    }

    /** Splash entries may be suffixed (splash_base#2...) for multi-activity coverage. */
    static boolean isSplashKey(String key) {
        return key != null
                && (key.equals(KEY_SPLASH_BASE) || key.startsWith(KEY_SPLASH_BASE + "#"));
    }

    static String indexedKey(String base, int index) {
        return index == 0 ? base : base + "#" + (index + 1);
    }

    /**
     * Merges one session's incoming targets into the live map with
     * descriptor-stable keys. Multi-target keys (feed#N / splash_base#N) are
     * NOT positional: a target keeps the key its descriptor already owns in
     * the live map, and only a genuinely new descriptor takes the next free
     * suffix. Candidate-set or order changes across sessions therefore cannot
     * re-key an existing descriptor and overwrite an unrelated entry.
     */
    static void mergeTargets(Map<String, ResolvedTarget> live,
                             Map<String, ResolvedTarget> incoming) {
        if (live == null || incoming == null) {
            return;
        }
        for (ResolvedTarget target : incoming.values()) {
            if (target == null || target.key == null || target.key.isEmpty()) {
                continue;
            }
            if (isFeedKey(target.key)) {
                String key = stableKey(live, KEY_FEED, target.methodDescriptor, true);
                live.put(key, target.withKey(key));
            } else if (isSplashKey(target.key)) {
                String key = stableKey(live, KEY_SPLASH_BASE, target.classDescriptor, false);
                live.put(key, target.withKey(key));
            } else {
                // Fixed keys (getter.*): the key itself is the identity.
                live.put(target.key, target);
            }
        }
    }

    private static String stableKey(Map<String, ResolvedTarget> live, String base,
                                    String descriptor, boolean byMethod) {
        if (descriptor != null && !descriptor.isEmpty()) {
            for (Map.Entry<String, ResolvedTarget> entry : live.entrySet()) {
                if (!sameFamily(entry.getKey(), base)) {
                    continue;
                }
                String existing = byMethod
                        ? entry.getValue().methodDescriptor
                        : entry.getValue().classDescriptor;
                if (descriptor.equals(existing)) {
                    return entry.getKey();
                }
            }
        }
        for (int index = 0; ; index++) {
            String key = indexedKey(base, index);
            if (!live.containsKey(key)) {
                return key;
            }
        }
    }

    private static boolean sameFamily(String key, String base) {
        return key.equals(base) || key.startsWith(base + "#");
    }
}
