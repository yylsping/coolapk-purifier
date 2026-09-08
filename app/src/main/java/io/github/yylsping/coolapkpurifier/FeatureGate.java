package io.github.yylsping.coolapkpurifier;

/**
 * Runtime on/off decisions for the restored settings switches. Deltas keep
 * their hooks installed and consult this gate inside the interceptor, so a
 * disabled feature passes the original invocation through untouched. Switch
 * changes are read from the persisted config on every invocation, but the
 * historical semantics still require a host restart for a clean adaptation.
 */
final class FeatureGate {
    private volatile PurifierConfig config;
    private volatile int coolapkMajor;

    void bind(PurifierConfig config, int coolapkMajor) {
        this.coolapkMajor = coolapkMajor;
        this.config = config;
    }

    boolean isEffectiveEnabled(PurifierConfig.Feature feature) {
        PurifierConfig current = config;
        if (current == null) {
            return feature.defaultEnabled
                    && (!feature.requiresCoolapk15 || coolapkMajor >= 15);
        }
        return current.isEffectiveEnabled(feature, coolapkMajor);
    }
}
