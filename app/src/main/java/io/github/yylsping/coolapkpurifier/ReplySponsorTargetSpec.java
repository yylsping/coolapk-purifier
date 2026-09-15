package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** D2: reply-sponsor ViewHolder binder plus its structural contract. */
final class ReplySponsorTargetSpec {
    static final String KIND = "replySponsor";

    final String ownerClass;
    final String methodName;
    final String layoutField;
    final String entityClass;
    final String entityTemplateGetter;
    final String entityTemplate;
    final String adHelperClass;
    final String bindingComponentClass;
    final String viewHolderClass;

    private ReplySponsorTargetSpec(String ownerClass, String methodName, String layoutField,
                                   String entityClass, String entityTemplateGetter,
                                   String entityTemplate, String adHelperClass,
                                   String bindingComponentClass, String viewHolderClass) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.layoutField = layoutField;
        this.entityClass = entityClass;
        this.entityTemplateGetter = entityTemplateGetter;
        this.entityTemplate = entityTemplate;
        this.adHelperClass = adHelperClass;
        this.bindingComponentClass = bindingComponentClass;
        this.viewHolderClass = viewHolderClass;
    }

    String descriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + methodName
                + "(Ljava/lang/Object;)V";
    }

    static ReplySponsorTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new ReplySponsorTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "methodName", KIND),
                SpecJson.required(json, "layoutField", KIND),
                SpecJson.required(json, "entityClass", KIND),
                SpecJson.required(json, "entityTemplateGetter", KIND),
                SpecJson.required(json, "entityTemplate", KIND),
                SpecJson.required(json, "adHelperClass", KIND),
                SpecJson.required(json, "bindingComponentClass", KIND),
                SpecJson.required(json, "viewHolderClass", KIND));
    }
}
