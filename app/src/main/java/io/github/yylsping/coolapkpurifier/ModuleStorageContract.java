package io.github.yylsping.coolapkpurifier;

/** Fixed grammar for module-owned RemotePreferences operations. */
final class ModuleStorageContract {
    static final String METHOD_PROBE = "probe";
    static final String METHOD_READ = "read";
    static final String METHOD_WRITE = "write";

    static final String REMOTE_GROUP = "coolapk_purifier_storage_v1";
    static final String CONFIG_KEY = "configuration_json";
    static final String CACHE_KEY = "resolver_cache_v5_json";
    static final String PROBE_KEY = "storage_capability";
    static final String PROBE_VALUE = "api102-foreground-remote-preferences-v2";

    static final String EXTRA_INITIAL_CONFIG =
            BuildConfig.APPLICATION_ID + ".extra.INITIAL_CONFIG";
    static final String EXTRA_COOLAPK_MAJOR =
            BuildConfig.APPLICATION_ID + ".extra.COOLAPK_MAJOR";
    static final String EXTRA_INITIAL_CACHE =
            BuildConfig.APPLICATION_ID + ".extra.INITIAL_CACHE";

    static final String EXTRA_METHOD = "method";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_VALUE = "value";
    static final String EXTRA_REASON = "reason";
    static final String RESULT_SUCCESS = "success";
    static final String RESULT_PRESENT = "present";
    static final String RESULT_WROTE = "wrote";
    static final String RESULT_DETAIL = "detail";

    static final int CONFIG_MAX_BYTES = 64 * 1024;
    static final int CACHE_MAX_BYTES = 1024 * 1024;
    static final int CACHE_HANDOFF_MAX_BYTES = 256 * 1024;

    private ModuleStorageContract() {
    }

    static boolean isValidMethod(String method) {
        return METHOD_PROBE.equals(method)
                || METHOD_READ.equals(method)
                || METHOD_WRITE.equals(method);
    }

    static boolean isTrustedActivityCaller(String callingPackage) {
        return CoolapkModule.TARGET_PACKAGE.equals(callingPackage);
    }

    static boolean isValidKey(String key) {
        return CONFIG_KEY.equals(key) || CACHE_KEY.equals(key);
    }

    static int maxBytesForKey(String key) {
        if (CONFIG_KEY.equals(key)) {
            return CONFIG_MAX_BYTES;
        }
        if (CACHE_KEY.equals(key)) {
            return CACHE_MAX_BYTES;
        }
        return -1;
    }

    static boolean isValidWriteReason(String key, String reason) {
        if (reason == null || reason.length() > 96) {
            return false;
        }
        if (CONFIG_KEY.equals(key)) {
            if ("configCreateDefaults".equals(reason)
                    || "configRepairDefaults".equals(reason)
                    || "legacyConfigImport".equals(reason)
                    || "configSchemaUpgrade1To2".equals(reason)
                    || "configMarkAdapted".equals(reason)) {
                return true;
            }
            for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
                if (("configToggle:" + feature.key).equals(reason)) {
                    return true;
                }
            }
            return false;
        }
        if (CACHE_KEY.equals(key)) {
            return "cacheRecoveryMarker".equals(reason)
                    || "cacheSaveTargets".equals(reason)
                    || "cacheRemoveTargets".equals(reason);
        }
        return false;
    }
}
