package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** Observe-only Feed.getRelatedData() getter contract. */
final class RelatedDataTargetSpec {
    static final String KIND = "relatedData";

    final String ownerClass;
    final String methodName;
    final String returnType;

    private RelatedDataTargetSpec(String ownerClass, String methodName, String returnType) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.returnType = returnType;
    }

    String descriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + methodName
                + "()" + DescriptorUtils.classDescriptorOf(returnType);
    }

    static RelatedDataTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new RelatedDataTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "methodName", KIND),
                SpecJson.required(json, "returnType", KIND));
    }
}
