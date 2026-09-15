package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cross-cutting proof of the ZERO_HOST_PRIVATE_WRITE persistence invariant. */
public final class HostDataMutationGuardTest {
    @Test
    public void configCacheAndDiagnosticsNeverMutateHostPrivateStorage() throws Exception {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        ModuleStorage.MemoryBlobStore configStore =
                new ModuleStorage.MemoryBlobStore("testRemote", guard);
        ModuleStorage.MemoryBlobStore cacheStore =
                new ModuleStorage.MemoryBlobStore("testRemote", guard);
        PurifierConfig config = new PurifierConfig(
                configStore, missingLegacy(), null);

        assertTrue(config.setEnabled(PurifierConfig.Feature.AUTO_COMMENT, true));
        assertTrue(config.markAdapted());

        ResolutionCache cache = new ResolutionCache(cacheStore);
        TargetIdentity identity = identity();
        assertEquals(ResolutionCache.CacheLookup.FILE_MISSING,
                cache.lookup(identity).missReason);
        cache.saveTargets(identity, targets());
        int afterSave = cacheStore.persistentWriteCount();
        assertTrue(cache.lookup(identity).isHit());
        assertEquals(afterSave, cacheStore.persistentWriteCount());
        assertTrue(cache.markRecoveryAttempted(identity));

        ModuleStorage.RingDiagnosticSink sink =
                new ModuleStorage.RingDiagnosticSink(4, null);
        BootstrapTrace trace = new BootstrapTrace(sink);
        trace.mark("start", "one");
        trace.mark("progress", "two");
        trace.freeze("terminal", "done");
        trace.mark("ignored", "after-freeze");

        HostDataMutationGuard.Snapshot snapshot = guard.snapshot();
        assertEquals(0, snapshot.hostPrivateWrites);
        assertEquals(0, snapshot.filesDirWrites);
        assertEquals(0, snapshot.cacheDirWrites);
        assertEquals(0, snapshot.codeCacheWrites);
        assertEquals(0, snapshot.renames);
        assertEquals(0, snapshot.deletes);
        assertEquals(0, snapshot.tempCreates);
        assertTrue(snapshot.remoteWrites >= 5);
        assertEquals(0, sink.persistentWriteCount());
        assertEquals(3, sink.snapshot().size());
        assertTrue(trace.isFrozen());
    }

    @Test
    public void allOffDefaultAndAllOnTopologySnapshotsRequireNoPersistence() {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        ModuleStorage.MemoryBlobStore configStore =
                new ModuleStorage.MemoryBlobStore("testRemote", guard);
        PurifierConfig config = new PurifierConfig(configStore, missingLegacy(), null);
        long writesAfterCreate = guard.snapshot().remoteWrites;

        HookTopology allOff = new HookTopology(snapshot(false), true);
        HookTopology defaults = new HookTopology(defaultSnapshot(), true);
        HookTopology allOn = new HookTopology(snapshot(true), true);

        assertFalse(allOff.needsDynamicBootstrap());
        assertTrue(defaults.needsDynamicBootstrap());
        assertTrue(allOn.needsDynamicBootstrap());
        assertEquals(writesAfterCreate, guard.snapshot().remoteWrites);
        assertEquals(0, guard.snapshot().hostPrivateWrites);
        assertTrue(config.isEnabled(PurifierConfig.Feature.SPLASH));
    }

    @Test
    public void guardClassifiesEveryForbiddenHostMutationKind() {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        for (HostDataMutationGuard.HostMutation mutation
                : HostDataMutationGuard.HostMutation.values()) {
            guard.recordHostMutation(mutation);
        }
        HostDataMutationGuard.Snapshot snapshot = guard.snapshot();
        assertEquals(6, snapshot.hostPrivateWrites);
        assertEquals(1, snapshot.filesDirWrites);
        assertEquals(1, snapshot.cacheDirWrites);
        assertEquals(1, snapshot.codeCacheWrites);
        assertEquals(1, snapshot.renames);
        assertEquals(1, snapshot.deletes);
        assertEquals(1, snapshot.tempCreates);
    }

    private static Map<PurifierConfig.Feature, Boolean> snapshot(boolean value) {
        EnumMap<PurifierConfig.Feature, Boolean> result =
                new EnumMap<>(PurifierConfig.Feature.class);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            result.put(feature, value);
        }
        return result;
    }

    private static Map<PurifierConfig.Feature, Boolean> defaultSnapshot() {
        EnumMap<PurifierConfig.Feature, Boolean> result =
                new EnumMap<>(PurifierConfig.Feature.class);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            result.put(feature, feature.defaultEnabled);
        }
        return result;
    }

    private static PurifierConfig.LegacySource missingLegacy() {
        return new PurifierConfig.LegacySource() {
            @Override public byte[] read() { return null; }
            @Override public String description() { return "missing"; }
        };
    }

    private static TargetIdentity identity() throws Exception {
        JSONObject json = new JSONObject();
        json.put("package", "com.coolapk.market");
        json.put("apkPath", "/data/app/base.apk");
        json.put("apkSize", 123L);
        json.put("signingHash", "sha256:abc");
        json.put("token", "guard-token");
        json.put("versionCode", 2608212L);
        json.put("versionName", "16.6.1");
        return TargetIdentity.fromJson(json);
    }

    private static Map<String, ResolvedTarget> targets() {
        Map<String, ResolvedTarget> result = new LinkedHashMap<>();
        result.put(TargetResolver.KEY_FEED, new ResolvedTarget(
                TargetResolver.KEY_FEED, "test", "Ljava/lang/String;",
                "Ljava/lang/String;->length()I"));
        return result;
    }
}
