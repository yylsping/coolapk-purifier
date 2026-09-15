package io.github.yylsping.manifestgen;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline manifest adapter. Never touches the Android runtime path; reads a
 * local APK and the validated baseline profile, then emits a report and a
 * draft manifest (status=draft, never auto-validated).
 *
 * <pre>
 * generate --apk coolapk.apk --output build/adapter
 *          [--baseline app/src/main/assets/coolapk_target_manifest.json]
 *          [--version-code N] [--version-name X]
 * diff     --old profile-or-manifest.json --new profile-or-manifest.json
 * </pre>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "generate":
                generate(options(args, 1));
                break;
            case "diff":
                Diff.run(options(args, 1));
                break;
            case "probe":
                Probe.run(options(args, 1));
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  generate --apk <coolapk.apk> --output <dir>"
                + " [--baseline <manifest.json>] [--version-code N] [--version-name X]");
        System.out.println("  diff --old <profile.json> --new <profile.json>");
    }

    private static Map<String, String> options(String[] args, int from) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = from; i < args.length - 1; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    private static void generate(Map<String, String> options) throws Exception {
        String apkPath = options.get("--apk");
        String outputPath = options.get("--output");
        if (apkPath == null || outputPath == null) {
            usage();
            return;
        }
        Path baselinePath = Paths.get(options.getOrDefault("--baseline",
                "app/src/main/assets/coolapk_target_manifest.json"));
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(baselinePath), StandardCharsets.UTF_8));
        JSONObject baselineProfile = Specs.baselineProfile(manifest);
        long versionCode = options.containsKey("--version-code")
                ? Long.parseLong(options.get("--version-code"))
                : baselineProfile.getLong("versionCode");
        String versionName = options.getOrDefault("--version-name",
                baselineProfile.optString("versionName", "unknown") + "-draft");

        System.out.println("loading dex index from " + apkPath + " ...");
        DexIndex index = DexIndex.load(new File(apkPath));
        System.out.println("indexed " + index.classCount() + " classes");

        JSONObject targets = Specs.baselineTargets(manifest,
                baselineProfile.getLong("versionCode"));

        StringBuilder report = new StringBuilder();
        report.append("# adapter report\n");
        report.append("apk=").append(apkPath).append('\n');
        report.append("baseline=").append(baselinePath)
                .append(" versionCode=").append(baselineProfile.getLong("versionCode"))
                .append('\n');
        report.append("classes=").append(index.classCount()).append("\n\n");

        JSONObject draftTargets = new JSONObject();
        for (String feature : Specs.FEATURE_ORDER) {
            JSONObject specJson = targets.optJSONObject(feature);
            if (specJson == null) {
                report.append("feature=").append(feature).append('\n');
                report.append("  verdict=MISSING\n");
                report.append("  note=not present in baseline profile\n\n");
                continue;
            }
            Specs.FeatureSpec spec = Specs.parse(feature, specJson);
            Resolve.FeatureResult result =
                    Resolve.forFeature(feature).resolve(spec, index);

            report.append("feature=").append(feature).append('\n');
            report.append("  verdict=").append(result.verdict).append('\n');
            report.append("  candidates=").append(result.candidates.size()).append('\n');
            report.append("  old=").append(result.oldDescriptor).append('\n');
            Resolve.Candidate chosen = result.chosenCandidate();
            report.append("  new=").append(chosen == null ? "-" : chosen.descriptor)
                    .append('\n');
            report.append("  verification=").append(result.verification).append('\n');
            if (chosen != null) {
                for (String evidence : chosen.evidence) {
                    report.append("  evidence=").append(evidence).append('\n');
                }
            }
            report.append('\n');

            if (result.verdict == Resolve.Verdict.UNCHANGED) {
                draftTargets.put(feature, spec.toJson());
            } else if (result.verdict == Resolve.Verdict.CHANGED
                    && chosen != null && chosen.draftSpec != null) {
                draftTargets.put(feature, chosen.draftSpec.toJson());
            }
            // MISSING/AMBIGUOUS: target omitted → the draft stays fail-closed.
        }

        JSONObject draftProfile = new JSONObject()
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("status", "draft")
                .put("targets", draftTargets);
        JSONObject draft = new JSONObject()
                .put("schema", 1)
                .put("profiles", new org.json.JSONArray().put(draftProfile));

        Path outputDir = Paths.get(outputPath);
        Files.createDirectories(outputDir);
        write(outputDir.resolve("adapter-report.txt"), report.toString());
        write(outputDir.resolve("manifest-draft.json"), draft.toString(2) + "\n");
        System.out.println(report);
        System.out.println("wrote " + outputDir.resolve("adapter-report.txt"));
        System.out.println("wrote " + outputDir.resolve("manifest-draft.json"));
    }

    private static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
