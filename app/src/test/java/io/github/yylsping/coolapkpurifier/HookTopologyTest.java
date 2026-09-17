package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

/**
 * Manifest §8.5 / BUG-G: the startup hook topology installs only what the
 * persisted feature snapshot and the manifest profile actually require.
 */
public final class HookTopologyTest {
    private static Map<PurifierConfig.Feature, Boolean> snapshot(boolean value) {
        EnumMap<PurifierConfig.Feature, Boolean> snapshot =
                new EnumMap<>(PurifierConfig.Feature.class);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            snapshot.put(feature, value);
        }
        return snapshot;
    }

    private static Map<PurifierConfig.Feature, Boolean> snapshotWith(
            boolean defaultValue, PurifierConfig.Feature... disabled) {
        Map<PurifierConfig.Feature, Boolean> snapshot = snapshot(defaultValue);
        for (PurifierConfig.Feature feature : disabled) {
            snapshot.put(feature, false);
        }
        return snapshot;
    }

    @Test
    public void allEnabledWithValidatedProfileNeedsEverything() {
        HookTopology topology = new HookTopology(snapshot(true), true);
        assertTrue(topology.needsSplash());
        assertTrue(topology.needsFeed());
        assertTrue(topology.needsDynamicBootstrap());
        assertTrue(topology.needsInstrumentationFallback());
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                assertTrue("should install " + feature,
                        topology.shouldInstallManifestFeature(feature));
                assertNull("pending before install " + feature,
                        topology.installResult(feature));
            }
        }
    }

    @Test
    public void disabledAtStartManifestFeatureIsStructuredDisabled() {
        HookTopology topology = new HookTopology(
                snapshotWith(true, PurifierConfig.Feature.DETAIL_SPONSOR), true);
        assertFalse(topology.shouldInstallManifestFeature(
                PurifierConfig.Feature.DETAIL_SPONSOR));
        assertEquals(InstallResult.DISABLED,
                topology.installResult(PurifierConfig.Feature.DETAIL_SPONSOR));
        // Other manifest features are unaffected.
        assertTrue(topology.shouldInstallManifestFeature(
                PurifierConfig.Feature.AUTO_COMMENT));
        assertNull(topology.installResult(PurifierConfig.Feature.AUTO_COMMENT));
    }

    @Test
    public void unvalidatedProfileFailsClosedAsUnsupportedVersion() {
        HookTopology topology = new HookTopology(snapshot(true), false);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                assertFalse(topology.shouldInstallManifestFeature(feature));
                assertEquals(InstallResult.UNSUPPORTED_VERSION,
                        topology.installResult(feature));
            }
        }
    }

    @Test
    public void splashDisabledRemovesInstrumentationFallbackOnly() {
        HookTopology topology = new HookTopology(
                snapshotWith(true, PurifierConfig.Feature.SPLASH), true);
        assertFalse(topology.needsSplash());
        assertFalse(topology.needsInstrumentationFallback());
        // Feed still needs the dynamic bootstrap pipeline.
        assertTrue(topology.needsFeed());
        assertTrue(topology.needsDynamicBootstrap());
    }

    @Test
    public void allDynamicTrustedDisabledSkipsBootstrap() {
        HookTopology topology = new HookTopology(snapshotWith(true,
                PurifierConfig.Feature.SPLASH, PurifierConfig.Feature.FEED_SPONSOR), true);
        assertFalse(topology.needsDynamicBootstrap());
        assertFalse(topology.needsInstrumentationFallback());
        // Manifest-exact features still install.
        assertTrue(topology.shouldInstallManifestFeature(
                PurifierConfig.Feature.REPLY_SPONSOR));
    }

    @Test
    public void allFeaturesOffHasSettingsOnlyResidualSurface() {
        HookTopology topology = new HookTopology(snapshot(false), true);
        HookLedger ledger = new HookLedger();
        ledger.record(HookLedger.Layer.BUSINESS, "settings",
                "settings-native-initData-test", "settings initData");

        assertFalse(topology.needsDynamicBootstrap());
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                assertFalse(topology.shouldInstallManifestFeature(feature));
                assertEquals(InstallResult.DISABLED, topology.installResult(feature));
                assertFalse(ledger.isActive(HookTopology.businessHookId(feature)));
            }
        }
        assertEquals(java.util.Arrays.asList("settings-native-initData-test"),
                ledger.activeIds(HookLedger.Layer.BUSINESS));

        String status = DynamicTrustedStatus.summaryLine(
                BootstrapState.READY,
                false, false, false, false, false, false, false, false,
                false, 0, false, true, null, "postRetirement");
        assertTrue(status.contains("remove_splash_ads={enabledAtStart=false"));
        assertTrue(status.contains("instrumentationHookPresent=false"));
        assertTrue(status.contains("remove_feed_sponsor={enabledAtStart=false"));
    }

    @Test
    public void missingSnapshotKeysAreTreatedAsDisabled() {
        HookTopology topology = new HookTopology(
                new EnumMap<>(PurifierConfig.Feature.class), true);
        assertFalse(topology.needsDynamicBootstrap());
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                assertEquals(InstallResult.DISABLED, topology.installResult(feature));
            }
        }
    }

    @Test
    public void recordInstallResultOnlyTracksManifestFeatures() {
        HookTopology topology = new HookTopology(snapshot(true), true);
        topology.recordInstallResult(PurifierConfig.Feature.AUTO_COMMENT,
                InstallResult.INSTALLED);
        assertEquals(InstallResult.INSTALLED,
                topology.installResult(PurifierConfig.Feature.AUTO_COMMENT));
        // Dynamic-trusted features are not manifest-managed; recording is ignored.
        topology.recordInstallResult(PurifierConfig.Feature.SPLASH,
                InstallResult.INSTALLED);
        assertNull(topology.installResult(PurifierConfig.Feature.SPLASH));
    }

    @Test
    public void summaryLineProvesDisabledFeatureCarriesNoHook() {
        HookTopology topology = new HookTopology(
                snapshotWith(true, PurifierConfig.Feature.SAME_TOPIC_FEED), true);
        topology.recordInstallResult(PurifierConfig.Feature.AUTO_COMMENT,
                InstallResult.INSTALLED);
        String summary = topology.summaryLine(null);
        assertTrue(summary.startsWith("hookTopology"));
        assertTrue(summary.contains(
                "remove_same_topic_feed={enabledAtStart=false"));
        assertTrue(summary.contains("install=DISABLED"));
        assertTrue(summary.contains("hookInstalled=false"));
        assertTrue(summary.contains(
                "remove_auto_comment={enabledAtStart=true"));
        assertTrue(summary.contains("install=INSTALLED"));
        assertTrue(summary.contains("hookInstalled=true"));
        assertTrue(summary.contains("activeHookCount=0"));
    }

    @Test
    public void businessHookIdsExistForManifestFeaturesOnly() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            String id = HookTopology.businessHookId(feature);
            if (TargetResolutionPolicy.isManifestManaged(feature)) {
                assertFalse("hook id for " + feature, id.isEmpty());
                assertTrue("hook id unique for " + feature, ids.add(id));
            } else {
                assertTrue("no business hook for " + feature, id.isEmpty());
            }
        }
    }
}
