package io.github.yylsping.manifestgen;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Manifest diff (manifest §11): old validated profile vs new draft/profile,
 * per feature UNCHANGED / CHANGED / MISSING / NEW. Pure JVM, no APK needed.
 */
final class Diff {
    private Diff() {
    }

    static void run(java.util.Map<String, String> options) throws Exception {
        String oldPath = options.get("--old");
        String newPath = options.get("--new");
        if (oldPath == null || newPath == null) {
            System.out.println("usage: diff --old <profile.json> --new <profile.json>");
            return;
        }
        JSONObject oldTargets = targetsOf(readJson(oldPath));
        JSONObject newTargets = targetsOf(readJson(newPath));

        Set<String> features = new LinkedHashSet<>();
        for (String feature : Specs.FEATURE_ORDER) {
            features.add(feature);
        }
        oldTargets.keySet().forEach(features::add);
        newTargets.keySet().forEach(features::add);

        StringBuilder out = new StringBuilder("# manifest diff\n");
        out.append("old=").append(oldPath).append('\n');
        out.append("new=").append(newPath).append("\n\n");
        int changed = 0;
        for (String feature : features) {
            JSONObject oldSpec = oldTargets.optJSONObject(feature);
            JSONObject newSpec = newTargets.optJSONObject(feature);
            String verdict;
            if (oldSpec == null && newSpec == null) {
                continue;
            } else if (oldSpec == null) {
                verdict = "NEW";
                changed++;
            } else if (newSpec == null) {
                verdict = "MISSING";
                changed++;
            } else if (oldSpec.similar(newSpec)) {
                verdict = "UNCHANGED";
            } else {
                verdict = "CHANGED";
                changed++;
            }
            out.append(String.format("%-24s %s%n", feature, verdict));
            if ("CHANGED".equals(verdict)) {
                for (String key : unionKeys(oldSpec, newSpec)) {
                    String oldValue = oldSpec.optString(key, "-");
                    String newValue = newSpec.optString(key, "-");
                    if (!oldValue.equals(newValue)) {
                        out.append("    ").append(key).append(": ")
                                .append(oldValue).append(" -> ").append(newValue)
                                .append('\n');
                    }
                }
            }
        }
        out.append("\nsummary: ").append(changed).append(" feature(s) differ\n");
        System.out.print(out);
    }

    private static Set<String> unionKeys(JSONObject a, JSONObject b) {
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    private static JSONObject readJson(String path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get(path)),
                StandardCharsets.UTF_8));
    }

    /** Accepts a full manifest, a single profile, or a raw targets object. */
    private static JSONObject targetsOf(JSONObject json) {
        if (json.has("profiles")) {
            return Specs.baselineProfile(json).getJSONObject("targets");
        }
        if (json.has("targets")) {
            return json.getJSONObject("targets");
        }
        return json;
    }
}
