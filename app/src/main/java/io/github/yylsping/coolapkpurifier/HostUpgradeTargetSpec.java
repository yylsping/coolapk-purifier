package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** Version-pinned entry points for Coolapk's own upgrade flow. */
final class HostUpgradeTargetSpec {
    final String checkOwner;
    final String checkMethod;
    final String observableClass;
    final String justMethod;
    final String popupMethod;
    final String manualCheckMethod;
    final String downloadMethod;

    private HostUpgradeTargetSpec(JSONObject json) throws TargetManifest.ManifestException {
        if (!"hostUpgrade".equals(SpecJson.required(json, "kind", "hostUpgrade"))) {
            throw new TargetManifest.ManifestException("invalid hostUpgrade kind");
        }
        checkOwner = SpecJson.required(json, "checkOwner", "hostUpgrade");
        checkMethod = SpecJson.required(json, "checkMethod", "hostUpgrade");
        observableClass = SpecJson.required(json, "observableClass", "hostUpgrade");
        justMethod = SpecJson.required(json, "justMethod", "hostUpgrade");
        popupMethod = SpecJson.required(json, "popupMethod", "hostUpgrade");
        manualCheckMethod = SpecJson.required(json, "manualCheckMethod", "hostUpgrade");
        downloadMethod = SpecJson.required(json, "downloadMethod", "hostUpgrade");
    }

    static HostUpgradeTargetSpec parse(JSONObject json) throws TargetManifest.ManifestException {
        return json == null ? null : new HostUpgradeTargetSpec(json);
    }
}
