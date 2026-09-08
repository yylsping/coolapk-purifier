package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Schema 2 migration gate: a 2.1.0-era schema-1 cache (single positional
 * feed entry) must NEVER satisfy the new multi-target coverage READY
 * condition as a cache hit — the new resolver has to run instead.
 */
public final class ResolutionCacheMigrationTest {
    private static final String IDENTITY_JSON = "{"
            + "\"package\":\"com.coolapk.market\","
            + "\"apkPath\":\"/data/app/base.apk\","
            + "\"apkSize\":12345,"
            + "\"signingHash\":\"abc\","
            + "\"token\":\"stable-token-1\","
            + "\"versionCode\":16551,"
            + "\"versionName\":\"16.5.1\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void schemaOneRootAtCurrentPathIsNeverACacheHit() throws Exception {
        File filesDir = folder.newFolder("files1");
        writeRawCache(filesDir, "coolapk_purifier_cache_v4.json",
                schemaOneRootWithSingleFeed());
        TargetIdentity identity = TargetIdentity.fromJson(new JSONObject(IDENTITY_JSON));

        Map<String, ResolvedTarget> loaded =
                new ResolutionCache(filesDir).loadTargets(identity);

        assertTrue(loaded.isEmpty());
    }

    @Test
    public void saveAfterSchemaOneRootRewritesWithSchemaTwo() throws Exception {
        File filesDir = folder.newFolder("files2");
        writeRawCache(filesDir, "coolapk_purifier_cache_v4.json",
                schemaOneRootWithSingleFeed());
        TargetIdentity identity = TargetIdentity.fromJson(new JSONObject(IDENTITY_JSON));
        ResolutionCache cache = new ResolutionCache(filesDir);

        Map<String, ResolvedTarget> fresh = new LinkedHashMap<>();
        fresh.put(TargetResolver.KEY_FEED, new ResolvedTarget(
                TargetResolver.KEY_FEED, "fingerprint_strong",
                "Lcom/coolapk/market/view/ad/EntityAdHelper;",
                "Lcom/coolapk/market/view/ad/EntityAdHelper;->a(Ljava/util/List;Z)Ljava/util/List;"));
        cache.saveTargets(identity, fresh);

        String raw = new String(Files.readAllBytes(
                new File(filesDir, "coolapk_purifier_cache_v4.json").toPath()),
                StandardCharsets.UTF_8);
        assertEquals(2, new JSONObject(raw).getInt("schema"));
        assertEquals(fresh.keySet(),
                new ResolutionCache(filesDir).loadTargets(identity).keySet());
    }

    @Test
    public void legacyV3FileIsIgnoredEntirely() throws Exception {
        File filesDir = folder.newFolder("files3");
        writeRawCache(filesDir, "coolapk_purifier_cache_v3.json",
                schemaOneRootWithSingleFeed());
        TargetIdentity identity = TargetIdentity.fromJson(new JSONObject(IDENTITY_JSON));
        ResolutionCache cache = new ResolutionCache(filesDir);

        assertTrue(cache.loadTargets(identity).isEmpty());
        assertFalse(new File(filesDir, "coolapk_purifier_cache_v4.json").isFile());

        Map<String, ResolvedTarget> fresh = new LinkedHashMap<>();
        fresh.put(TargetResolver.KEY_FEED, new ResolvedTarget(
                TargetResolver.KEY_FEED, "fingerprint_strong", "Lx;", "Lx;->m()V"));
        cache.saveTargets(identity, fresh);

        // The new file carries schema 2; the abandoned v3 file stays untouched.
        String v4 = new String(Files.readAllBytes(
                new File(filesDir, "coolapk_purifier_cache_v4.json").toPath()),
                StandardCharsets.UTF_8);
        assertEquals(2, new JSONObject(v4).getInt("schema"));
        assertEquals(schemaOneRootWithSingleFeed(),
                new String(Files.readAllBytes(
                        new File(filesDir, "coolapk_purifier_cache_v3.json").toPath()),
                        StandardCharsets.UTF_8));
    }

    private static String schemaOneRootWithSingleFeed() {
        return "{\"schema\":1,\"entries\":[{"
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

    private static void writeRawCache(File filesDir, String fileName, String content)
            throws Exception {
        Files.write(new File(filesDir, fileName).toPath(),
                content.getBytes(StandardCharsets.UTF_8));
    }
}
