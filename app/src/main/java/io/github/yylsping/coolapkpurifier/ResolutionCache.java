package io.github.yylsping.coolapkpurifier;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-version resolver cache.
 *
 * <p>Stores at most {@link CachePolicy#MAX_ENTRIES} successful identities and
 * the complete serialized JSON (including metadata and recovery markers) is
 * never larger than {@link CachePolicy#MAX_TOTAL_BYTES}. versionCode never
 * participates in cache identity or validity.
 *
 * <p>Schema 2 (file v4): introduced with the multi-target coverage semantics.
 * Schema 1 (file v3) was written by 2.1.0/2.1.1 with positional single-feed
 * entries; those caches describe resolver coverage that no longer matches the
 * multi-target READY condition, so they are deliberately NOT migrated and NOT
 * readable. The first launch after the upgrade simply re-resolves and
 * repopulates the new file; the abandoned v3 file is left in place untouched.
 */
final class ResolutionCache {
    static final int RESOLVER_SCHEMA_VERSION = 2;
    private static final String FILE_NAME = "coolapk_purifier_cache_v4.json";

    private final File file;
    private final Object lock = new Object();

    ResolutionCache(Context appContext) {
        this(appContext.getFilesDir());
    }

    ResolutionCache(File filesDir) {
        this.file = new File(filesDir, FILE_NAME);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        if (temp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    Map<String, ResolvedTarget> loadTargets(TargetIdentity identity) {
        CacheEntry entry = loadEntry(identity);
        return entry == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.targets);
    }

    boolean isRecoveryAttempted(TargetIdentity identity) {
        synchronized (lock) {
            JSONObject root = readJson();
            if (root == null) {
                return false;
            }
            JSONArray recovered = root.optJSONArray("recoveryAttempted");
            if (recovered == null) {
                return false;
            }
            for (int i = 0; i < recovered.length(); i++) {
                if (identity.token.equals(recovered.optString(i, null))) {
                    return true;
                }
            }
            return false;
        }
    }

    boolean markRecoveryAttempted(TargetIdentity identity) {
        synchronized (lock) {
            try {
                JSONObject root = readJson();
                if (root == null || root.optInt("schema", 0) != RESOLVER_SCHEMA_VERSION) {
                    root = new JSONObject();
                    root.put("schema", RESOLVER_SCHEMA_VERSION);
                    root.put("entries", new JSONArray());
                }
                JSONArray recovered = root.optJSONArray("recoveryAttempted");
                if (recovered == null) {
                    recovered = new JSONArray();
                    root.put("recoveryAttempted", recovered);
                }
                if (!containsToken(recovered, identity.token)) {
                    recovered.put(identity.token);
                    trimRecoveryMarkers(recovered);
                }
                if (!writeJson(root, identity.token)) {
                    return false;
                }
                JSONObject reread = readJson();
                if (reread == null) {
                    return false;
                }
                JSONArray persisted = reread.optJSONArray("recoveryAttempted");
                return persisted != null && containsToken(persisted, identity.token);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    void saveTargets(TargetIdentity identity, Map<String, ResolvedTarget> targets) {
        synchronized (lock) {
            try {
                JSONObject root = readJson();
                if (root == null || root.optInt("schema", 0) != RESOLVER_SCHEMA_VERSION) {
                    root = new JSONObject();
                    root.put("schema", RESOLVER_SCHEMA_VERSION);
                    root.put("entries", new JSONArray());
                }
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) {
                    entries = new JSONArray();
                    root.put("entries", entries);
                }
                JSONObject replacement =
                        encodeEntry(identity, targets, System.currentTimeMillis());
                JSONArray merged = new JSONArray();
                merged.put(replacement);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject candidate = entries.optJSONObject(i);
                    TargetIdentity candidateIdentity = TargetIdentity.fromJson(
                            candidate == null ? null : candidate.optJSONObject("identity"));
                    if (candidateIdentity != null
                            && identity.token.equals(candidateIdentity.token)) {
                        continue;
                    }
                    merged.put(candidate);
                }
                root.put("entries", merged);
                trimRecoveryMarkers(root.optJSONArray("recoveryAttempted"));
                writeJson(root, identity.token);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Invalidates only the current identity; other entries stay untouched. */
    void removeTargets(TargetIdentity identity) {
        synchronized (lock) {
            try {
                JSONObject root = readJson();
                if (root == null) {
                    return;
                }
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) {
                    return;
                }
                JSONArray kept = new JSONArray();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject candidate = entries.optJSONObject(i);
                    TargetIdentity candidateIdentity = TargetIdentity.fromJson(
                            candidate == null ? null : candidate.optJSONObject("identity"));
                    if (candidateIdentity != null
                            && identity.token.equals(candidateIdentity.token)) {
                        continue;
                    }
                    kept.put(candidate);
                }
                root.put("entries", kept);
                writeJson(root, identity.token);
            } catch (Throwable ignored) {
            }
        }
    }

    private CacheEntry loadEntry(TargetIdentity identity) {
        synchronized (lock) {
            JSONObject root = readJson();
            if (root == null || root.optInt("schema", 0) != RESOLVER_SCHEMA_VERSION) {
                return null;
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                return null;
            }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject candidate = entries.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                TargetIdentity candidateIdentity =
                        TargetIdentity.fromJson(candidate.optJSONObject("identity"));
                if (candidateIdentity == null || !identity.sameTarget(candidateIdentity)) {
                    continue;
                }
                Map<String, ResolvedTarget> targets = decodeTargets(candidate);
                long lastUsedAt = candidate.optLong("lastUsedAt", 0L);
                touch(identity, lastUsedAt);
                return new CacheEntry(targets, lastUsedAt);
            }
            return null;
        }
    }

    private void touch(TargetIdentity identity, long oldLastUsedAt) {
        try {
            JSONObject root = readJson();
            if (root == null) {
                return;
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                return;
            }
            boolean changed = false;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject candidate = entries.optJSONObject(i);
                TargetIdentity candidateIdentity = TargetIdentity.fromJson(
                        candidate == null ? null : candidate.optJSONObject("identity"));
                if (candidateIdentity != null && identity.token.equals(candidateIdentity.token)
                        && oldLastUsedAt == candidate.optLong("lastUsedAt", 0L)) {
                    candidate.put("lastUsedAt", System.currentTimeMillis());
                    changed = true;
                    break;
                }
            }
            if (changed) {
                writeJson(root, identity.token);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Persists the complete root. Every write unconditionally trims entries
     * to at most 5, then serializes the real UTF-8 root. If root metadata
     * still exceeds the 1 MiB budget, entries are evicted again and the root
     * is re-serialized until both limits hold. Finally the temp file replaces
     * the destination with a single atomic rename; failure leaves the old
     * destination untouched.
     */
    private boolean writeJson(JSONObject root, String protectKey) {
        try {
            trimRecoveryMarkers(root.optJSONArray("recoveryAttempted"));
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                entries = new JSONArray();
                root.put("entries", entries);
            }
            // Hard entry-count limit applies on every write, not only when the
            // serialized root exceeds the byte budget.
            entries = evictForSize(entries, protectKey);
            root.put("entries", entries);

            byte[] bytes = serialize(root);
            if (bytes == null) {
                // Serialization failure never heals by evicting more entries;
                // retrying would loop forever.
                return false;
            }
            String currentProtect = protectKey;
            while (bytes.length > CachePolicy.MAX_TOTAL_BYTES) {
                entries = evictForSize(root.optJSONArray("entries"), currentProtect);
                root.put("entries", entries);
                byte[] candidate = serialize(root);
                if (candidate == null || candidate.length >= bytes.length) {
                    // No progress possible while metadata alone exceeds the
                    // hard budget.
                    return false;
                }
                bytes = candidate;
                currentProtect = null;
                if (entries.length() == 0 && bytes.length > CachePolicy.MAX_TOTAL_BYTES) {
                    return false;
                }
            }
            return CacheAtomicWriter.write(file, bytes, CacheAtomicWriter.RENAME_REPLACE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private byte[] serialize(JSONObject root) {
        try {
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private JSONArray evictForSize(JSONArray entries, String protectKey) throws JSONException {
        if (entries == null) {
            return new JSONArray();
        }
        List<CachePolicy.Record> records = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String key = entry.optJSONObject("identity") == null ? ""
                    : entry.optJSONObject("identity").optString("token", "");
            byte[] entryBytes = entry.toString().getBytes(StandardCharsets.UTF_8);
            records.add(new CachePolicy.Record(key,
                    entry.optLong("lastUsedAt", 0L), entryBytes.length));
        }
        List<CachePolicy.Record> kept = CachePolicy.evict(records, protectKey);
        JSONArray result = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String key = entry == null || entry.optJSONObject("identity") == null ? ""
                    : entry.optJSONObject("identity").optString("token", "");
            for (CachePolicy.Record record : kept) {
                if (record.key.equals(key)) {
                    result.put(entry);
                    break;
                }
            }
        }
        return result;
    }

    private void trimRecoveryMarkers(JSONArray recovered) {
        if (recovered == null) {
            return;
        }
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < recovered.length(); i++) {
            markers.add(recovered.optString(i, ""));
        }
        markers = RecoveryPolicy.trim(markers);
        while (recovered.length() > 0) {
            recovered.remove(0);
        }
        for (String marker : markers) {
            recovered.put(marker);
        }
    }

    private static boolean containsToken(JSONArray array, String token) {
        for (int i = 0; i < array.length(); i++) {
            if (token.equals(array.optString(i, null))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject encodeEntry(TargetIdentity identity,
                                   Map<String, ResolvedTarget> targets,
                                   long lastUsedAt) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("identity", identity.toJson());
        entry.put("lastUsedAt", lastUsedAt);
        JSONArray array = new JSONArray();
        for (ResolvedTarget target : targets.values()) {
            array.put(target.toJson());
        }
        entry.put("targets", array);
        return entry;
    }

    private Map<String, ResolvedTarget> decodeTargets(JSONObject entry) {
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        JSONArray array = entry == null ? null : entry.optJSONArray("targets");
        if (array == null) {
            return targets;
        }
        for (int i = 0; i < array.length(); i++) {
            ResolvedTarget target = ResolvedTarget.fromJson(array.optJSONObject(i));
            if (target != null && target.key != null && !target.key.isEmpty()) {
                targets.put(target.key, target);
            }
        }
        return targets;
    }

    private JSONObject readJson() {
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(
                    file.length(), CachePolicy.MAX_TOTAL_BYTES + 1L)];
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == 0) {
                return null;
            }
            return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class CacheEntry {
        final Map<String, ResolvedTarget> targets;
        final long lastUsedAt;

        CacheEntry(Map<String, ResolvedTarget> targets, long lastUsedAt) {
            this.targets = targets;
            this.lastUsedAt = lastUsedAt;
        }
    }
}
