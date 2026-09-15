package io.github.yylsping.coolapkpurifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict JSON field access shared by the typed manifest target specs. */
final class SpecJson {
    private SpecJson() {
    }

    static String required(JSONObject json, String key, String kind)
            throws TargetManifest.ManifestException {
        String value = json == null ? null : json.optString(key, "");
        if (value.isEmpty()) {
            throw new TargetManifest.ManifestException(
                    "target kind=" + kind + " missing field=" + key);
        }
        return value;
    }

    static List<String> requiredArray(JSONObject json, String key, String kind)
            throws TargetManifest.ManifestException {
        JSONArray array = json == null ? null : json.optJSONArray(key);
        if (array == null) {
            throw new TargetManifest.ManifestException(
                    "target kind=" + kind + " missing field=" + key);
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (value.isEmpty()) {
                throw new TargetManifest.ManifestException(
                        "target kind=" + kind + " empty field=" + key + "[" + i + "]");
            }
            values.add(value);
        }
        return Collections.unmodifiableList(values);
    }
}
