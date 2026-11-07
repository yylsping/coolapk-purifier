package io.github.yylsping.coolapkpurifier;

/** Fixed snapshot grammar; old framework names are retained only for migration. */
final class ModuleStorageContract {
    static final String REMOTE_GROUP = "coolapk_purifier_storage_v1";
    static final String CONFIG_KEY = "configuration_json";
    static final String CACHE_KEY = "resolver_cache_v5_json";
    static final int CONFIG_MAX_BYTES = 64 * 1024;
    static final int CACHE_MAX_BYTES = 1024 * 1024;

    private ModuleStorageContract() {
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
            for (BottomNavigationSelection.Item item : BottomNavigationSelection.Item.values()) {
                if (("configBottomNavigation:" + item.name()).equals(reason)) return true;
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
