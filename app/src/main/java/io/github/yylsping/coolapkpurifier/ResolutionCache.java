package io.github.yylsping.coolapkpurifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
 * never larger than {@link CachePolicy#MAX_TOTAL_BYTES}. versionCode is part
 * of the stable identity token and therefore participates in cache validity.
 *
 * <p>Schema 4 (logical cache v6): the identity token covers versionCode, the
 * base/split APK structure and an explicit signer digest (raw DER,
 * unavailable marked explicitly). The schema bump also requires the embedded
 * splash-decision target when that host capability exists, so an Activity-only
 * schema-3 snapshot can never certify complete splash coverage. Older snapshots
 * are deliberately NOT migrated and NOT readable: a miss re-resolves into a
 * bounded process overlay. A validated snapshot can later be persisted only
 * when the user opens the visible module configuration activity, while
 * abandoned host files stay untouched.
 *
 * <p>Every lookup reports a structured outcome ({@link CacheLookup}) so logs
 * can distinguish "no cache" from "cache rejected by identity/format"
 * instead of an unexplained empty result.
 */
final class ResolutionCache {
    static final int RESOLVER_SCHEMA_VERSION = 4;
    private final ResolverCacheStore store;
    private final Object lock = new Object();

    ResolutionCache(ResolverCacheStore store) {
        this.store = store;
    }

    /** Validates a cache snapshot before a cross-process foreground handoff. */
    static boolean isValidSnapshot(byte[] bytes) {
        if (bytes == null || bytes.length == 0
                || bytes.length > ModuleStorageContract.CACHE_HANDOFF_MAX_BYTES) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray entries = root.optJSONArray("entries");
            return root.optInt("schema", 0) == RESOLVER_SCHEMA_VERSION
                    && entries != null
                    && entries.length() <= CachePolicy.MAX_ENTRIES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Structured lookup outcome; missReason is null exactly on a hit. */
    static final class CacheLookup {
        static final String FILE_MISSING = "fileMissing";
        static final String BACKEND_UNAVAILABLE = "backendUnavailable";
        static final String SCHEMA_MISMATCH = "schemaMismatch";
        static final String IDENTITY_MISMATCH = "identityMismatch";
        static final String ENTRY_MALFORMED = "entryMalformed";

        final Map<String, ResolvedTarget> targets;
        final String missReason;
        final int totalEntries;

        private CacheLookup(Map<String, ResolvedTarget> targets, String missReason,
                            int totalEntries) {
            this.targets = targets;
            this.missReason = missReason;
            this.totalEntries = totalEntries;
        }

        static CacheLookup hit(Map<String, ResolvedTarget> targets, int totalEntries) {
            return new CacheLookup(targets, null, totalEntries);
        }

        static CacheLookup miss(String reason, int totalEntries) {
            return new CacheLookup(new LinkedHashMap<>(), reason, totalEntries);
        }

        boolean isHit() {
            return missReason == null;
        }
    }

    CacheLookup lookup(TargetIdentity identity) {
        synchronized (lock) {
            if (!store.isAvailable()) {
                return CacheLookup.miss(CacheLookup.BACKEND_UNAVAILABLE, 0);
            }
            byte[] bytes = readBytes();
            if (bytes == null) {
                return CacheLookup.miss(CacheLookup.FILE_MISSING, 0);
            }
            JSONObject root = decodeJson(bytes);
            if (root == null) {
                return CacheLookup.miss(CacheLookup.ENTRY_MALFORMED, 0);
            }
            if (root.optInt("schema", 0) != RESOLVER_SCHEMA_VERSION) {
                return CacheLookup.miss(CacheLookup.SCHEMA_MISMATCH, 0);
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                return CacheLookup.miss(CacheLookup.ENTRY_MALFORMED, 0);
            }
            int decodable = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject candidate = entries.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                TargetIdentity candidateIdentity =
                        TargetIdentity.fromJson(candidate.optJSONObject("identity"));
                if (candidateIdentity == null || candidateIdentity.token == null
                        || candidateIdentity.token.isEmpty()) {
                    continue;
                }
                decodable++;
                if (!identity.sameTarget(candidateIdentity)) {
                    continue;
                }
                Map<String, ResolvedTarget> targets = decodeTargets(candidate);
                return CacheLookup.hit(new LinkedHashMap<>(targets), entries.length());
            }
            return decodable == 0
                    ? CacheLookup.miss(CacheLookup.ENTRY_MALFORMED, entries.length())
                    : CacheLookup.miss(CacheLookup.IDENTITY_MISMATCH, decodable);
        }
    }

    Map<String, ResolvedTarget> loadTargets(TargetIdentity identity) {
        return new LinkedHashMap<>(lookup(identity).targets);
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
                if (containsToken(recovered, identity.token)) {
                    return true;
                }
                recovered.put(identity.token);
                trimRecoveryMarkers(recovered);
                if (!writeJson(root, identity.token, "cacheRecoveryMarker")) {
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
                writeJson(root, identity.token, "cacheSaveTargets");
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
                boolean removed = false;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject candidate = entries.optJSONObject(i);
                    TargetIdentity candidateIdentity = TargetIdentity.fromJson(
                            candidate == null ? null : candidate.optJSONObject("identity"));
                    if (candidateIdentity != null
                            && identity.token.equals(candidateIdentity.token)) {
                        removed = true;
                        continue;
                    }
                    kept.put(candidate);
                }
                if (!removed) {
                    return;
                }
                root.put("entries", kept);
                writeJson(root, identity.token, "cacheRemoveTargets");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Persists the complete root. Every write unconditionally trims entries
     * to at most 5, then serializes the real UTF-8 root. If root metadata
     * still exceeds the 1 MiB budget, entries are evicted again and the root
     * is re-serialized until both limits hold. The complete value is then
     * atomically accepted by the framework-owned backend; failure leaves its
     * prior value untouched.
     */
    private boolean writeJson(JSONObject root, String protectKey, String reason) {
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
            return store.write(bytes, reason);
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
        return decodeJson(readBytes());
    }

    private byte[] readBytes() {
        try {
            byte[] bytes = store.read();
            return bytes == null || bytes.length == 0
                    || bytes.length > CachePolicy.MAX_TOTAL_BYTES ? null : bytes;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private JSONObject decodeJson(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
