package io.github.yylsping.coolapkpurifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Startup hook-topology decision (BUG-G). Pure and testable: given the
 * persisted feature snapshot at process start and the manifest profile
 * availability, it decides which business hooks and which dynamic bootstrap
 * pieces this process actually installs. Nothing here resolves targets or
 * touches the host.
 *
 * <pre>
 * disabled-at-start manifest feature → InstallResult.DISABLED, never hooked
 * no enabled dynamic-trusted feature → no runtime observer / DexKit bootstrap
 * SPLASH disabled                  → no Instrumentation splash fallback
 * </pre>
 */
final class HookTopology {
    /** libxposed hook ids used by the manifest-managed business hooks. */
    static String businessHookId(PurifierConfig.Feature feature) {
        switch (feature) {
            case DETAIL_SPONSOR:
                return "coolapk-d1-detail-sponsor";
            case REPLY_SPONSOR:
                return "coolapk-d2r-reply-sponsor";
            case SAME_TOPIC_FEED:
                return "coolapk-d3-same-topic-insert-event";
            case TOPIC_DEVICE_RECOMMEND:
                return "coolapk-d5-topic-device-recommend";
            case AUTO_COMMENT:
                return "coolapk-d6-auto-comment";
            case RELATED_DATA:
                return RelatedDataDelta.GETTER_HOOK_ID;
            default:
                return "";
        }
    }

    private final EnumMap<PurifierConfig.Feature, Boolean> enabledAtStart;
    private final EnumMap<PurifierConfig.Feature, InstallResult> installResults;
    private final boolean profileValidated;

    HookTopology(Map<PurifierConfig.Feature, Boolean> snapshot, boolean profileValidated) {
        this.enabledAtStart = new EnumMap<>(PurifierConfig.Feature.class);
        this.installResults = new EnumMap<>(PurifierConfig.Feature.class);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            boolean enabled = Boolean.TRUE.equals(snapshot.get(feature));
            enabledAtStart.put(feature, enabled);
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                installResults.put(feature, !enabled ? InstallResult.DISABLED
                        : profileValidated ? null : InstallResult.UNSUPPORTED_VERSION);
            }
        }
        this.profileValidated = profileValidated;
    }

    boolean isEnabledAtStart(PurifierConfig.Feature feature) {
        return Boolean.TRUE.equals(enabledAtStart.get(feature));
    }

    boolean needsSplash() {
        return isEnabledAtStart(PurifierConfig.Feature.SPLASH);
    }

    boolean needsFeed() {
        return isEnabledAtStart(PurifierConfig.Feature.FEED_SPONSOR);
    }

    /** Any enabled dynamic-trusted feature requires the resolver pipeline. */
    boolean needsDynamicBootstrap() {
        return needsSplash() || needsFeed();
    }

    /** The generic Instrumentation safety net exists only for splash. */
    boolean needsInstrumentationFallback() {
        return needsSplash();
    }

    /** Manifest-managed features this process must attempt to install. */
    boolean shouldInstallManifestFeature(PurifierConfig.Feature feature) {
        return TargetResolutionPolicy.isManifestManaged(feature)
                && isEnabledAtStart(feature) && profileValidated;
    }

    void recordInstallResult(PurifierConfig.Feature feature, InstallResult result) {
        if (TargetResolutionPolicy.isManifestManaged(feature)) {
            installResults.put(feature, result);
        }
    }

    InstallResult installResult(PurifierConfig.Feature feature) {
        return installResults.get(feature);
    }

    /**
     * Machine-readable per-feature terminal summary. Proves that a feature
     * disabled at startup carries no business hook and that an enabled one
     * reports its actual install outcome.
     */
    String summaryLine(HookLedger ledger) {
        StringBuilder sb = new StringBuilder("hookTopology");
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            sb.append(' ').append(feature.key)
                    .append("={enabledAtStart=").append(isEnabledAtStart(feature))
                    .append(" source=").append(TargetResolutionPolicy.sourceFor(feature));
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                InstallResult result = installResults.get(feature);
                sb.append(" install=").append(result == null ? "PENDING" : result);
                String hookId = businessHookId(feature);
                int active = ledger != null && !hookId.isEmpty() && ledger.isActive(hookId) ? 1 : 0;
                sb.append(" hookInstalled=").append(result == InstallResult.INSTALLED
                        || result == InstallResult.PARTIAL
                        || result == InstallResult.ALREADY_INSTALLED)
                        .append(" activeHookCount=").append(active);
            }
            sb.append('}');
        }
        return sb.toString();
    }
}
