package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Logical cache-v6 (schema 4) migration gate and module-owned backend contract:
 * snapshots written before the embedded splash-decision readiness contract
 * (schema 3 and older) must NEVER be read as valid hits, and every miss
 * carries a structured reason.
 */
public final class ResolutionCacheMigrationTest {
    private static final String IDENTITY_JSON = "{"
            + "\"package\":\"com.coolapk.market\","
            + "\"apkPath\":\"/data/app/base.apk\","
            + "\"apkSize\":12345,"
            + "\"signingHash\":\"sha256:abc\","
            + "\"token\":\"stable-token-1\","
            + "\"versionCode\":2608212,"
            + "\"versionName\":\"16.6.1\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HostDataMutationGuard guard;
    private ModuleStorage.MemoryBlobStore store;

    @Before
    public void setUp() {
        guard = new HostDataMutationGuard();
        store = new ModuleStorage.MemoryBlobStore("testRemote", guard);
    }

    @Test
    public void missingRemoteValueReportsFileMissingWithoutWriting() {
        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertFalse(lookup.isHit());
        assertEquals(ResolutionCache.CacheLookup.FILE_MISSING, lookup.missReason);
        assertTrue(lookup.targets.isEmpty());
        assertEquals(0, store.persistentWriteCount());
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void legacyV4HostFileIsNeverReadOrChanged() throws Exception {
        File legacy = folder.newFile("coolapk_purifier_cache_v4.json");
        byte[] original = schemaTwoRoot().getBytes(StandardCharsets.UTF_8);
        Files.write(legacy.toPath(), original);
        long modified = legacy.lastModified();
        ResolutionCache cache = new ResolutionCache(store);

        assertEquals(ResolutionCache.CacheLookup.FILE_MISSING,
                cache.lookup(identity()).missReason);
        cache.saveTargets(identity(), freshTargets());
        assertEquals(4, new JSONObject(new String(store.read(), StandardCharsets.UTF_8))
                .getInt("schema"));
        assertArrayEquals(original, Files.readAllBytes(legacy.toPath()));
        assertEquals(modified, legacy.lastModified());
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void schemaMismatchIsReported() {
        store.seed(schemaTwoRoot().getBytes(StandardCharsets.UTF_8));
        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertEquals(ResolutionCache.CacheLookup.SCHEMA_MISMATCH, lookup.missReason);
    }

    @Test
    public void legacySchemaThreeSnapshotIsNeverAValidHit() {
        store.seed(schemaTwoRoot().replace("\"schema\":2", "\"schema\":3")
                .getBytes(StandardCharsets.UTF_8));
        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertEquals(ResolutionCache.CacheLookup.SCHEMA_MISMATCH, lookup.missReason);
        assertFalse(lookup.isHit());
    }

    @Test
    public void identityMismatchIsReported() {
        String otherIdentity = IDENTITY_JSON.replace("stable-token-1", "stable-token-other")
                .replace("2608212", "2608213");
        store.seed(("{\"schema\":4,\"entries\":[{"
                + "\"identity\":" + otherIdentity
                + ",\"lastUsedAt\":1,\"targets\":[]}]}")
                .getBytes(StandardCharsets.UTF_8));
        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertEquals(ResolutionCache.CacheLookup.IDENTITY_MISMATCH, lookup.missReason);
        assertEquals(1, lookup.totalEntries);
    }

    @Test
    public void malformedEntriesAreReported() {
        store.seed("{\"schema\":4,\"entries\":[42,\"junk\"]}"
                .getBytes(StandardCharsets.UTF_8));
        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertEquals(ResolutionCache.CacheLookup.ENTRY_MALFORMED, lookup.missReason);
    }

    @Test
    public void saveThenLoadRoundTripsAsHit() {
        ResolutionCache cache = new ResolutionCache(store);
        Map<String, ResolvedTarget> fresh = freshTargets();
        cache.saveTargets(identity(), fresh);

        ResolutionCache.CacheLookup lookup = new ResolutionCache(store).lookup(identity());
        assertTrue(lookup.isHit());
        assertNull(lookup.missReason);
        assertEquals(fresh.keySet(), lookup.targets.keySet());
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void cacheHitPerformsNoPersistentTouch() {
        ResolutionCache cache = new ResolutionCache(store);
        cache.saveTargets(identity(), freshTargets());
        int before = store.persistentWriteCount();
        byte[] beforeBytes = store.read();

        for (int i = 0; i < 5; i++) {
            assertTrue(cache.lookup(identity()).isHit());
        }

        assertEquals(before, store.persistentWriteCount());
        assertArrayEquals(beforeBytes, store.read());
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void recoveryMarkerUsesModuleOwnedBackend() {
        ResolutionCache cache = new ResolutionCache(store);
        assertFalse(cache.isRecoveryAttempted(identity()));
        assertTrue(cache.markRecoveryAttempted(identity()));
        assertTrue(new ResolutionCache(store).isRecoveryAttempted(identity()));
        assertEquals(1, store.persistentWriteCount());
        assertEquals(Long.valueOf(1L),
                guard.snapshot().remoteWriteReasons.get("cacheRecoveryMarker"));
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    private static TargetIdentity identity() {
        try {
            return TargetIdentity.fromJson(new JSONObject(IDENTITY_JSON));
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Map<String, ResolvedTarget> freshTargets() {
        Map<String, ResolvedTarget> fresh = new LinkedHashMap<>();
        fresh.put(TargetResolver.KEY_FEED, new ResolvedTarget(
                TargetResolver.KEY_FEED, "fingerprint_strong",
                "Lcom/coolapk/market/view/ad/EntityAdHelper;",
                "Lcom/coolapk/market/view/ad/EntityAdHelper;->a(Ljava/util/List;Z)"
                        + "Ljava/util/List;"));
        return fresh;
    }

    private static String schemaTwoRoot() {
        return "{\"schema\":2,\"entries\":[{"
                + "\"identity\":" + IDENTITY_JSON + ","
                + "\"lastUsedAt\":1,"
                + "\"targets\":[{"
                + "\"key\":\"feed\","
                + "\"source\":\"fingerprint_strong\","
                + "\"class\":\"Lcom/coolapk/market/view/ad/EntityAdHelper;\","
                + "\"method\":\"Lcom/coolapk/market/view/ad/EntityAdHelper;"
                + "->a(Ljava/util/List;Z)Ljava/util/List;\","
                + "\"at\":1}]}]}";
    }
}
