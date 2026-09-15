package io.github.yylsping.coolapkpurifier;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Seven-feature configuration with a framework/module-owned authoritative store. */
final class PurifierConfig {
    static final String FILE_NAME = "coolapk_purifier_config.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 64 * 1024;

    enum Feature {
        SPLASH("remove_splash_ads", "去除启动/开屏广告和全屏广告", true, false),
        FEED_SPONSOR("remove_feed_sponsor", "去除首页信息流广告与赞助卡片", true, false),
        REPLY_SPONSOR("remove_reply_sponsor", "去除帖子回复区及评论中的赞助内容", true, false),
        AUTO_COMMENT("remove_auto_comment", "去除自动评论提示", false, true),
        TOPIC_DEVICE_RECOMMEND("remove_topic_device_recommend", "去除话题与机型推荐", false, true),
        SAME_TOPIC_FEED("remove_same_topic_feed", "去除同话题动态", false, true),
        DETAIL_SPONSOR("remove_detail_sponsor", "去除帖子内推广", false, true);

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
    private String loadedSource;

    static PurifierConfig load(Context context, ConfigStore store, ModuleLog log) {
        File legacyFile = new File(context.getFilesDir(), FILE_NAME);
        return new PurifierConfig(store, new LegacyFileSource(legacyFile), log);
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

    synchronized String loadedSource() {
        return loadedSource;
    }

    /** Validated snapshot handed to the visible module UI for one-time import. */
    synchronized byte[] serializedSnapshot() {
        return encode();
    }

    synchronized boolean hasNonDefaultSelections() {
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
        byte[] remoteRaw = store.read();
        boolean remoteWasPresent = remoteRaw != null;
        byte[] remote = bounded(remoteRaw);
        if (decode(remote)) {
            loadedSource = "remoteAuthoritative";
            info("config loaded source=" + loadedSource + " backend=" + store.backendName()
                    + " revision=" + revision + " pending=" + pendingKind.value);
            return;
        }

        if (!remoteWasPresent && legacySource != null) {
            byte[] legacy = bounded(legacySource.read());
            if (decode(legacy)) {
                loadedSource = "legacyReadOnly";
                boolean migrated = persist("legacyConfigImport");
                if (migrated) {
                    loadedSource = "legacyImportedToRemote";
                }
                info("config legacy import source=" + legacySource.description()
                        + " migrated=" + migrated + " authoritative=" + loadedSource);
                return;
            }
        }

        resetDefaults();
        loadedSource = remoteWasPresent ? "remoteInvalidDefaults" : "defaults";
        boolean persisted = persist(remoteWasPresent
                ? "configRepairDefaults" : "configCreateDefaults");
        info("config initialized source=" + loadedSource + " persisted=" + persisted
                + " backend=" + store.backendName());
    }

    private void resetDefaults() {
        enabled.clear();
        for (Feature feature : Feature.values()) {
            enabled.put(feature, feature.defaultEnabled);
        }
        pendingKind = PendingKind.DEFAULT;
        revision = 1L;
    }

    private boolean decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.optInt("schema", 0) != SCHEMA) {
                return false;
            }
            JSONObject options = root.optJSONObject("options");
            if (options == null) {
                return false;
            }
            EnumMap<Feature, Boolean> decoded = new EnumMap<>(Feature.class);
            for (Feature feature : Feature.values()) {
                Object raw = options.opt(feature.key);
                if (raw != null && raw != JSONObject.NULL && !(raw instanceof Boolean)) {
                    return false;
                }
                decoded.put(feature, raw instanceof Boolean
                        ? (Boolean) raw : feature.defaultEnabled);
            }
            enabled.clear();
            enabled.putAll(decoded);
            pendingKind = PendingKind.from(root.optString("pendingAdaptation", "none"));
            revision = Math.max(1L, root.optLong("revision", 1L));
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
