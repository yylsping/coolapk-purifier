package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** Exact detail-page iconListCard binder contract for promotion/topic cards. */
final class RelatedIconListUiTargetSpec {
    static final String KIND = "relatedIconListUi";

    final String ownerClass;
    final String methodName;
    final String holderClass;
    final String callbackField;
    final String callbackClass;
    final String fragmentField;
    final String fragmentBaseClass;
    final String hostFragmentClass;
    final String bindingComponentClass;
    final String viewHolderClass;
    final String cardClass;
    final String cardTemplateGetter;
    final String cardTemplate;
    final String entitiesGetter;
    final String entityClass;
    final String entityTypeGetter;
    final String promotionEntityType;
    final String singleRecommendEntityType;

    private RelatedIconListUiTargetSpec(
            String ownerClass, String methodName, String holderClass,
            String callbackField, String callbackClass, String fragmentField,
            String fragmentBaseClass, String hostFragmentClass,
            String bindingComponentClass, String viewHolderClass,
            String cardClass, String cardTemplateGetter, String cardTemplate,
            String entitiesGetter, String entityClass, String entityTypeGetter,
            String promotionEntityType, String singleRecommendEntityType) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.holderClass = holderClass;
        this.callbackField = callbackField;
        this.callbackClass = callbackClass;
        this.fragmentField = fragmentField;
        this.fragmentBaseClass = fragmentBaseClass;
        this.hostFragmentClass = hostFragmentClass;
        this.bindingComponentClass = bindingComponentClass;
        this.viewHolderClass = viewHolderClass;
        this.cardClass = cardClass;
        this.cardTemplateGetter = cardTemplateGetter;
        this.cardTemplate = cardTemplate;
        this.entitiesGetter = entitiesGetter;
        this.entityClass = entityClass;
        this.entityTypeGetter = entityTypeGetter;
        this.promotionEntityType = promotionEntityType;
        this.singleRecommendEntityType = singleRecommendEntityType;
    }

    String descriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + methodName
                + "(Ljava/lang/Object;)V";
    }

    static RelatedIconListUiTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new RelatedIconListUiTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "methodName", KIND),
                SpecJson.required(json, "holderClass", KIND),
                SpecJson.required(json, "callbackField", KIND),
                SpecJson.required(json, "callbackClass", KIND),
                SpecJson.required(json, "fragmentField", KIND),
                SpecJson.required(json, "fragmentBaseClass", KIND),
                SpecJson.required(json, "hostFragmentClass", KIND),
                SpecJson.required(json, "bindingComponentClass", KIND),
                SpecJson.required(json, "viewHolderClass", KIND),
                SpecJson.required(json, "cardClass", KIND),
                SpecJson.required(json, "cardTemplateGetter", KIND),
                SpecJson.required(json, "cardTemplate", KIND),
                SpecJson.required(json, "entitiesGetter", KIND),
                SpecJson.required(json, "entityClass", KIND),
                SpecJson.required(json, "entityTypeGetter", KIND),
                SpecJson.required(json, "promotionEntityType", KIND),
                SpecJson.required(json, "singleRecommendEntityType", KIND));
    }
}
