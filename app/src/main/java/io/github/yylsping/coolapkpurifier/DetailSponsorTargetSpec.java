package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** D1: detail-sponsor card getter on the AutoValue Feed model. */
final class DetailSponsorTargetSpec {
    static final String KIND = "detailSponsor";

    final String ownerClass;
    final String methodName;
    final String returnType;

    private DetailSponsorTargetSpec(String ownerClass, String methodName, String returnType) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.returnType = returnType;
    }

    String descriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + methodName
                + "()" + DescriptorUtils.classDescriptorOf(returnType);
    }

    static DetailSponsorTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new DetailSponsorTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "methodName", KIND),
                SpecJson.required(json, "returnType", KIND));
    }
}
