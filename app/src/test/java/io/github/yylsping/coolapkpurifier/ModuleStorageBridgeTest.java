package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Contract tests for the no-process-start storage split. */
public final class ModuleStorageBridgeTest {
    @Test
    public void injectedSideReadsFrameworkConfigButCannotWriteIt() {
        FakeReadOnlyBridge bridge = new FakeReadOnlyBridge(true);
        byte[] config = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
        bridge.seed(ModuleStorageContract.CONFIG_KEY, config);
        ModuleStorage storage = ModuleStorage.readOnlyBackedForTest(bridge);

        assertFalse(storage.isRemoteAvailable());
        storage.attachContext(null);
        assertTrue(storage.isRemoteAvailable());
        assertArrayEquals(config, storage.configStore().read());
        assertFalse(storage.configStore().write(config, "configCreateDefaults"));

        HostDataMutationGuard.Snapshot snapshot = storage.mutationGuard().snapshot();
        assertEquals(0L, snapshot.hostPrivateWrites);
        assertEquals(0L, snapshot.remoteWrites);
        assertEquals(0, storage.configStore().persistentWriteCount());
    }

    @Test
    public void resolverCacheUsesReadThroughSnapshotAndProcessOverlay() {
        FakeReadOnlyBridge remote = new FakeReadOnlyBridge(true);
        byte[] prior = "{\"schema\":3,\"entries\":[]}".getBytes(StandardCharsets.UTF_8);
        remote.seed(ModuleStorageContract.CACHE_KEY, prior);
        ModuleStorage connected = ModuleStorage.readOnlyBackedForTest(remote);
        connected.attachContext(null);
        assertArrayEquals(prior, connected.resolverCacheStore().read());

        byte[] replacement = "{\"schema\":3,\"entries\":[{}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(connected.resolverCacheStore().write(replacement, "cacheSaveTargets"));
        assertArrayEquals(replacement, connected.resolverCacheStore().read());
        assertArrayEquals(prior, remote.read(ModuleStorageContract.CACHE_KEY));
        assertEquals("frameworkReadOnly+processMemoryPendingHandoff",
                connected.resolverCacheStore().backendName());
        assertEquals(0, connected.resolverCacheStore().persistentWriteCount());
        assertEquals(0L, connected.mutationGuard().snapshot().remoteWrites);

        ModuleStorage first = ModuleStorage.readOnlyBackedForTest(
                new FakeReadOnlyBridge(false));
        byte[] cache = "{\"schema\":3}".getBytes(StandardCharsets.UTF_8);

        assertTrue(first.resolverCacheStore().write(cache, "cacheSaveTargets"));
        assertArrayEquals(cache, first.resolverCacheStore().read());
        assertEquals("processMemoryPendingHandoff",
                first.resolverCacheStore().backendName());
        assertEquals(0, first.resolverCacheStore().persistentWriteCount());
        assertEquals(0L, first.mutationGuard().snapshot().remoteWrites);

        ModuleStorage nextProcess = ModuleStorage.readOnlyBackedForTest(
                new FakeReadOnlyBridge(false));
        assertNull(nextProcess.resolverCacheStore().read());
    }

    @Test
    public void unavailableFrameworkReadFailsClosedWithoutPersistentWrite() {
        ModuleStorage storage = ModuleStorage.readOnlyBackedForTest(
                new FakeReadOnlyBridge(false));
        storage.attachContext(null);

        assertFalse(storage.isRemoteAvailable());
        assertNull(storage.configStore().read());
        assertFalse(storage.configStore().write(new byte[]{1}, "configCreateDefaults"));
        assertEquals(0L, storage.mutationGuard().snapshot().remoteWrites);
        assertEquals(0L, storage.mutationGuard().snapshot().hostPrivateWrites);
    }

    @Test
    public void foregroundWriterGrammarExposesOnlyFixedConfigAndCacheKeys() {
        assertTrue(ModuleStorageContract.isTrustedActivityCaller(
                CoolapkModule.TARGET_PACKAGE));
        assertFalse(ModuleStorageContract.isTrustedActivityCaller(null));
        assertFalse(ModuleStorageContract.isTrustedActivityCaller("com.example.foreign"));

        assertTrue(ModuleStorageContract.isValidKey(ModuleStorageContract.CONFIG_KEY));
        assertTrue(ModuleStorageContract.isValidKey(ModuleStorageContract.CACHE_KEY));
        assertFalse(ModuleStorageContract.isValidKey("arbitrary"));
        assertEquals(64 * 1024, ModuleStorageContract.maxBytesForKey(
                ModuleStorageContract.CONFIG_KEY));
        assertEquals(CachePolicy.MAX_TOTAL_BYTES, ModuleStorageContract.maxBytesForKey(
                ModuleStorageContract.CACHE_KEY));

        assertTrue(ModuleStorageContract.isValidWriteReason(
                ModuleStorageContract.CONFIG_KEY,
                "configToggle:" + PurifierConfig.Feature.SPLASH.key));
        assertFalse(ModuleStorageContract.isValidWriteReason(
                ModuleStorageContract.CONFIG_KEY, "configToggle:remove_unknown"));
        assertTrue(ModuleStorageContract.isValidWriteReason(
                ModuleStorageContract.CACHE_KEY, "cacheSaveTargets"));
        assertFalse(ModuleStorageContract.isValidWriteReason(
                ModuleStorageContract.CACHE_KEY, "configCreateDefaults"));

        assertTrue(ModuleStorageContract.isValidMethod(ModuleStorageContract.METHOD_PROBE));
        assertTrue(ModuleStorageContract.isValidMethod(ModuleStorageContract.METHOD_READ));
        assertTrue(ModuleStorageContract.isValidMethod(ModuleStorageContract.METHOD_WRITE));
        assertFalse(ModuleStorageContract.isValidMethod("delete"));
        assertFalse(ModuleStorageContract.isValidMethod(null));
    }

    @Test
    public void foregroundCacheHandoffRejectsMalformedOrOversizedSnapshots() {
        assertTrue(ResolutionCache.isValidSnapshot(
                "{\"schema\":4,\"entries\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ResolutionCache.isValidSnapshot(
                "{\"schema\":3,\"entries\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ResolutionCache.isValidSnapshot(
                "{\"schema\":2,\"entries\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ResolutionCache.isValidSnapshot(
                "{not-json".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ResolutionCache.isValidSnapshot(
                new byte[ModuleStorageContract.CACHE_HANDOFF_MAX_BYTES + 1]));
        assertFalse(ResolutionCache.isValidSnapshot(
                "{\"schema\":4,\"entries\":[{},{},{},{},{},{}]}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static final class FakeReadOnlyBridge
            implements ModuleStorage.ReadOnlyRemoteBridge {
        private final boolean connectSuccess;
        private final Map<String, byte[]> values = new HashMap<>();
        private boolean available;

        FakeReadOnlyBridge(boolean connectSuccess) {
            this.connectSuccess = connectSuccess;
        }

        void seed(String key, byte[] value) {
            values.put(key, value.clone());
        }

        @Override
        public ModuleStorage.ProbeResult connect() {
            available = connectSuccess;
            return new ModuleStorage.ProbeResult(available,
                    available ? "fakeReadable" : "fakeUnavailable");
        }

        @Override
        public byte[] read(String key) {
            if (!available) return null;
            byte[] value = values.get(key);
            return value == null ? null : value.clone();
        }

        @Override public boolean isAvailable() { return available; }
        @Override public String backendName() {
            return available ? "fakeFrameworkReadOnly" : "fakeUnavailable";
        }
    }
}
