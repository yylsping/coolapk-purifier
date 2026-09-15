package io.github.yylsping.coolapkpurifier;

/**
 * Resolution source policy per feature. Answers only WHERE a feature's
 * target may come from; it never resolves anything itself.
 *
 * <pre>
 * SPLASH / FEED_SPONSOR        → DYNAMIC_TRUSTED (mature runtime resolvers)
 * REPLY_SPONSOR … DETAIL_SPONSOR → MANIFEST_EXACT (version-pinned profile)
 * unknown host version         → UNSUPPORTED for MANIFEST_EXACT features
 * </pre>
 */
final class TargetResolutionPolicy {
    enum Source {
        MANIFEST_EXACT,
        DYNAMIC_TRUSTED,
        UNSUPPORTED
    }

    private TargetResolutionPolicy() {
    }

    static Source sourceFor(PurifierConfig.Feature feature) {
        switch (feature) {
            case SPLASH:
            case FEED_SPONSOR:
                return Source.DYNAMIC_TRUSTED;
            default:
                return Source.MANIFEST_EXACT;
        }
    }

    static boolean isManifestManaged(PurifierConfig.Feature feature) {
        return sourceFor(feature) == Source.MANIFEST_EXACT;
    }

    static boolean isDynamicTrusted(PurifierConfig.Feature feature) {
        return sourceFor(feature) == Source.DYNAMIC_TRUSTED;
    }
}
