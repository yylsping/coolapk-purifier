package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Purifier configuration with an atomic host-private authoritative store. */
final class PurifierConfig {
    static final String FILE_NAME = "coolapk_purifier_config.json";
    private static final int LEGACY_SCHEMA = 1;
    private static final int SCHEMA = 2;
    private static final int MAX_BYTES = 64 * 1024;

    enum Feature {
        SPLASH("remove_splash_ads", "去除启动/开屏广告和全屏广告", true, false),
        FEED_SPONSOR("remove_feed_sponsor", "去除首页信息流广告与赞助卡片", true, false),
        REPLY_SPONSOR("remove_reply_sponsor", "去除帖子回复区及评论中的赞助内容", true, false),
        AUTO_COMMENT("remove_auto_comment", "去除自动评论提示", false, true),
        TOPIC_DEVICE_RECOMMEND("remove_topic_device_recommend", "去除话题与机型推荐", false, true),
        RELATED_DATA("remove_related_data", "去除帖子相关推荐", false, true),
        SAME_TOPIC_FEED("remove_same_topic_feed", "去除同话题动态", false, true),
        DETAIL_SPONSOR("remove_detail_sponsor", "去除帖子内推广", false, true),
        SEARCH_HOT_WORDS("remove_search_hot_words", "去除热门搜索与热榜热词", false, true),
        SEARCH_HOT_RANKINGS("remove_search_hot_rankings", "去除搜索页热榜", false, true);

        final String key;
        final String title;
        final boolean defaultEnabled;
        final boolean requiresCoolapk15;

        Feature(String key, String title, boolean defaultEnabled,
                boolean requiresCoolapk15) {
            this.key = key;
            this.title = title;
            this.defaultEnabled = defaultEnabled;
            this.requiresCoolapk15 = requiresCoolapk15;
        }
    }

    enum PendingKind {
        NONE("none"),
        DEFAULT("default"),
        SELECTION("selection");

        final String value;

        PendingKind(String value) {
            this.value = value;
        }

        static PendingKind from(String value) {
            for (PendingKind kind : values()) {
                if (kind.value.equals(value)) {
                    return kind;
                }
            }
            return NONE;
        }
    }

    interface LegacySource {
        byte[] read();
        String description();
    }

    private final ConfigStore store;
    private final LegacySource legacySource;
    private final ModuleLog log;
    private final EnumMap<Feature, Boolean> enabled = new EnumMap<>(Feature.class);
    private PendingKind pendingKind;
    private long revision;
    private int hiddenBottomNavigationItems;
    private String loadedSource;
    private boolean rewriteAfterDecode;

    static PurifierConfig load(ConfigStore store,
                               LegacySource migration, ModuleLog log) {
        return new PurifierConfig(store, migration, log);
    }

    PurifierConfig(ConfigStore store, LegacySource legacySource, ModuleLog log) {
        this.store = store;
        this.legacySource = legacySource;
        this.log = log;
        resetDefaults();
        loadInitial();
    }

    synchronized boolean isEnabled(Feature feature) {
        return Boolean.TRUE.equals(enabled.get(feature));
    }

    synchronized boolean isEffectiveEnabled(Feature feature, int coolapkMajor) {
        return isEnabled(feature) && (!feature.requiresCoolapk15 || coolapkMajor >= 15);
    }

    synchronized Map<Feature, Boolean> snapshot() {
        return new EnumMap<>(enabled);
    }

    synchronized PendingKind pendingKind() {
        return pendingKind;
    }

    synchronized long revision() {
        return revision;
    }

    synchronized int hiddenBottomNavigationItems() { return hiddenBottomNavigationItems; }

    synchronized boolean setBottomNavigationHidden(BottomNavigationSelection.Item item, boolean hidden) {
        int previous = hiddenBottomNavigationItems;
        int next = hidden ? previous | item.bit() : previous & ~item.bit();
        if (!BottomNavigationSelection.validMask(next)) return false;
        if (next == previous) return true;
        PendingKind previousPending = pendingKind;
        long previousRevision = revision;
        hiddenBottomNavigationItems = next;
        revision++;
        pendingKind = PendingKind.SELECTION;
        if (persist("configBottomNavigation:" + item.name())) return true;
        hiddenBottomNavigationItems = previous;
        revision = previousRevision;
        pendingKind = previousPending;
        return false;
    }

    synchronized String loadedSource() {
        return loadedSource;
    }

    /** Validated snapshot of the current configuration. */
    synchronized byte[] serializedSnapshot() {
        return encode();
    }

    synchronized boolean hasNonDefaultSelections() {
        if (hiddenBottomNavigationItems != 0) return true;
        for (Feature feature : Feature.values()) {
            if (isEnabled(feature) != feature.defaultEnabled) {
                return true;
            }
        }
        return false;
    }

    /** Returns true only after the complete new snapshot was atomically accepted. */
    synchronized boolean setEnabled(Feature feature, boolean value) {
        boolean previous = isEnabled(feature);
        if (previous == value) {
            return true;
        }
        PendingKind previousPending = pendingKind;
        long previousRevision = revision;
        enabled.put(feature, value);
        revision++;
        pendingKind = PendingKind.SELECTION;
        if (persist("configToggle:" + feature.key)) {
            return true;
        }
        enabled.put(feature, previous);
        pendingKind = previousPending;
        revision = previousRevision;
        return false;
    }

    synchronized boolean markAdapted() {
        if (pendingKind == PendingKind.NONE) {
            return true;
        }
        PendingKind previous = pendingKind;
        pendingKind = PendingKind.NONE;
        if (persist("configMarkAdapted")) {
            return true;
        }
        pendingKind = previous;
        return false;
    }

    private void loadInitial() {
        byte[] storedRaw = store.read();
        boolean storedWasPresent = storedRaw != null;
        byte[] stored = bounded(storedRaw);
        if (decode(stored)) {
            loadedSource = "storeAuthoritative";
            if (rewriteAfterDecode && persist("configSchemaUpgrade1To2")) {
                loadedSource = "storeMigratedSchema1To2";
            }
            info("config loaded source=" + loadedSource + " backend=" + store.backendName()
                    + " revision=" + revision + " pending=" + pendingKind.value);
            return;
        }

        if (!storedWasPresent && legacySource != null) {
            byte[] legacy = bounded(legacySource.read());
            if (decode(legacy)) {
                loadedSource = "legacyReadOnly";
                boolean migrated = persist("legacyConfigImport");
                if (migrated) {
                    loadedSource = "legacyImportedToStore";
                }
                info("config legacy import source=" + legacySource.description()
                        + " migrated=" + migrated + " authoritative=" + loadedSource);
                return;
            }
        }

        resetDefaults();
        loadedSource = storedWasPresent ? "storeInvalidDefaults" : "defaults";
        boolean persisted = persist(storedWasPresent
                ? "configRepairDefaults" : "configCreateDefaults");
        info("config initialized source=" + loadedSource + " persisted=" + persisted
                + " backend=" + store.backendName());
    }

    private void resetDefaults() {
        hiddenBottomNavigationItems = 0;
        enabled.clear();
        for (Feature feature : Feature.values()) {
            enabled.put(feature, feature.defaultEnabled);
        }
        rewriteAfterDecode = false;
        pendingKind = PendingKind.DEFAULT;
        revision = 1L;
    }

    private boolean decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int sourceSchema = root.optInt("schema", 0);
            if (sourceSchema != LEGACY_SCHEMA && sourceSchema != SCHEMA) {
                return false;
            }
            JSONObject options = root.optJSONObject("options");
            if (options == null) {
                return false;
            }
            EnumMap<Feature, Boolean> decoded = new EnumMap<>(Feature.class);
            Object navigation = root.opt("hiddenBottomNavigationItems");
            if (navigation != null && (!(navigation instanceof Integer)
                    || !BottomNavigationSelection.validMask((Integer) navigation))) return false;
            for (Feature feature : Feature.values()) {
                Object raw = options.opt(feature.key);
                if (raw != null && raw != JSONObject.NULL && !(raw instanceof Boolean)) {
                    return false;
                }
                // Schema 1 predates the deliberate 2.5 reintroduction. A
                // stale historical remove_related_data=true must never be
                // resurrected; the new switch starts false and is honored
                // only after the snapshot has been rewritten as schema 2.
                decoded.put(feature,
                        feature == Feature.RELATED_DATA && sourceSchema == LEGACY_SCHEMA
                                ? feature.defaultEnabled
                                : raw instanceof Boolean
                                        ? (Boolean) raw : feature.defaultEnabled);
            }
            enabled.clear();
            enabled.putAll(decoded);
            hiddenBottomNavigationItems = navigation == null ? 0 : (Integer) navigation;
            pendingKind = PendingKind.from(root.optString("pendingAdaptation", "none"));
            revision = Math.max(1L, root.optLong("revision", 1L));
            rewriteAfterDecode = sourceSchema == LEGACY_SCHEMA;
            return true;
        } catch (Throwable failure) {
            info("config decode failed error=" + failure);
            return false;
        }
    }

    private boolean persist(String reason) {
        try {
            byte[] bytes = encode();
            boolean written = bytes != null && store.write(bytes, reason);
            info("config persisted=" + written + " reason=" + reason
                    + " revision=" + revision + " pending=" + pendingKind.value
                    + " backend=" + store.backendName());
            return written;
        } catch (Throwable failure) {
            info("config persist failed reason=" + reason + " error=" + failure);
            return false;
        }
    }

    private byte[] encode() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA);
            root.put("revision", revision);
            root.put("pendingAdaptation", pendingKind.value);
            root.put("hiddenBottomNavigationItems", hiddenBottomNavigationItems);
            JSONObject options = new JSONObject();
            for (Feature feature : Feature.values()) {
                options.put(feature.key, isEnabled(feature));
            }
            root.put("options", options);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            return bytes.length <= MAX_BYTES ? bytes : null;
        } catch (Throwable failure) {
            info("config encode failed error=" + failure);
            return null;
        }
    }

    private static byte[] bounded(byte[] bytes) {
        return bytes != null && bytes.length <= MAX_BYTES ? bytes : null;
    }

    private void info(String message) {
        if (log != null) {
            log.info(message);
        }
    }

    static final class LegacyFileSource implements LegacySource {
        private final File file;

        LegacyFileSource(File file) {
            this.file = file;
        }

        @Override
        public byte[] read() {
            if (!file.isFile() || file.length() <= 0 || file.length() > MAX_BYTES) {
                return null;
            }
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) {
                        break;
                    }
                    offset += count;
                }
                if (offset == bytes.length) {
                    return bytes;
                }
                byte[] exact = new byte[offset];
                System.arraycopy(bytes, 0, exact, 0, offset);
                return exact;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public String description() {
            return "coolapkFilesDirReadOnly/" + FILE_NAME;
        }
    }
}
