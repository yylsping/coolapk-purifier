package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** Exact FeedBindGoodsViewHolder binder contract for the content-related section. */
final class RelatedContentUiTargetSpec {
    static final String KIND = "relatedContentUi";

    final String ownerClass;
    final String methodName;
    final String dataClass;
    final String entityTypeGetter;
    final String entityType;
    final String bindingComponentClass;
    final String viewModelClass;
    final String viewHolderClass;

    private RelatedContentUiTargetSpec(String ownerClass, String methodName,
                                       String dataClass, String entityTypeGetter,
                                       String entityType, String bindingComponentClass,
                                       String viewModelClass, String viewHolderClass) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.dataClass = dataClass;
        this.entityTypeGetter = entityTypeGetter;
        this.entityType = entityType;
        this.bindingComponentClass = bindingComponentClass;
        this.viewModelClass = viewModelClass;
        this.viewHolderClass = viewHolderClass;
    }

    String descriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + methodName
                + "(" + DescriptorUtils.classDescriptorOf(dataClass) + ")V";
    }

    static RelatedContentUiTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new RelatedContentUiTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "methodName", KIND),
                SpecJson.required(json, "dataClass", KIND),
                SpecJson.required(json, "entityTypeGetter", KIND),
                SpecJson.required(json, "entityType", KIND),
                SpecJson.required(json, "bindingComponentClass", KIND),
                SpecJson.required(json, "viewModelClass", KIND),
                SpecJson.required(json, "viewHolderClass", KIND));
    }
}
