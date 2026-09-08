package io.github.yylsping.coolapkpurifier;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * User configuration stored in Coolapk's own files directory.
 *
 * <p>The three legacy protections default to enabled. Issue #2 switches
 * default to disabled and are only effective on Coolapk 15.x and newer. Every
 * change is atomically persisted before the UI reports it as accepted.</p>
 */
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

    private final File file;
    private final CacheAtomicWriter.ReplaceOperation replaceOperation;
    private final ModuleLog log;
    private final EnumMap<Feature, Boolean> enabled = new EnumMap<>(Feature.class);
    private PendingKind pendingKind;
    private long revision;

    static PurifierConfig load(Context context, ModuleLog log) {
        return new PurifierConfig(context.getFilesDir(), CacheAtomicWriter.RENAME_REPLACE, log);
    }

    PurifierConfig(File filesDir, CacheAtomicWriter.ReplaceOperation replaceOperation,
                   ModuleLog log) {
        this.file = new File(filesDir, FILE_NAME);
        this.replaceOperation = replaceOperation;
        this.log = log;
        for (Feature feature : Feature.values()) {
            enabled.put(feature, feature.defaultEnabled);
        }
        if (!read()) {
            pendingKind = PendingKind.DEFAULT;
            revision = 1L;
            persist();
        }
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

    synchronized boolean hasNonDefaultSelections() {
        for (Feature feature : Feature.values()) {
            if (isEnabled(feature) != feature.defaultEnabled) {
                return true;
            }
        }
        return false;
    }

    /** Returns true only after the new value was durably written. */
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
        if (persist()) {
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
        if (persist()) {
            return true;
        }
        pendingKind = previous;
        return false;
    }

    private boolean read() {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_BYTES) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            JSONObject root = new JSONObject(
                    new String(bytes, 0, offset, StandardCharsets.UTF_8));
            if (root.optInt("schema", 0) != SCHEMA) {
                return false;
            }
            JSONObject options = root.optJSONObject("options");
            if (options == null) {
                return false;
            }
            for (Feature feature : Feature.values()) {
                enabled.put(feature, options.optBoolean(feature.key, feature.defaultEnabled));
            }
            pendingKind = PendingKind.from(root.optString("pendingAdaptation", "none"));
            revision = Math.max(1L, root.optLong("revision", 1L));
            return true;
        } catch (Throwable throwable) {
            info("config read failed; recreating defaults error=" + throwable);
            return false;
        }
    }

    private boolean persist() {
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
            boolean written = CacheAtomicWriter.write(file,
                    root.toString().getBytes(StandardCharsets.UTF_8), replaceOperation);
            info("config persisted=" + written + " revision=" + revision
                    + " pending=" + pendingKind.value);
            return written;
        } catch (Throwable throwable) {
            info("config persist failed error=" + throwable);
            return false;
        }
    }

    private void info(String message) {
        if (log != null) {
            log.info(message);
        }
    }
}
