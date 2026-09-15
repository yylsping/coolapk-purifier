package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** D3: same-topic recommend-list insertion event and its event payload contract. */
final class SameTopicTargetSpec {
    static final String KIND = "sameTopic";

    final String ownerClass;
    final String ownerParentClass;
    final String semanticMethod;
    final String eventClass;
    final String eventHandler;
    final String anchorGetter;
    final String cardGetter;
    final String entityClass;
    final String entityTemplateGetter;
    final String entityTemplate;

    private SameTopicTargetSpec(String ownerClass, String ownerParentClass,
                                String semanticMethod, String eventClass, String eventHandler,
                                String anchorGetter, String cardGetter, String entityClass,
                                String entityTemplateGetter, String entityTemplate) {
        this.ownerClass = ownerClass;
        this.ownerParentClass = ownerParentClass;
        this.semanticMethod = semanticMethod;
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
        this.anchorGetter = anchorGetter;
        this.cardGetter = cardGetter;
        this.entityClass = entityClass;
        this.entityTemplateGetter = entityTemplateGetter;
        this.entityTemplate = entityTemplate;
    }

    String semanticDescriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + semanticMethod
                + "(Ljava/lang/Object;)Z";
    }

    String eventDescriptor() {
        return DescriptorUtils.classDescriptorOf(ownerClass) + "->" + eventHandler
                + "(" + DescriptorUtils.classDescriptorOf(eventClass) + ")V";
    }

    static SameTopicTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        return new SameTopicTargetSpec(
                SpecJson.required(json, "ownerClass", KIND),
                SpecJson.required(json, "ownerParentClass", KIND),
                SpecJson.required(json, "semanticMethod", KIND),
                SpecJson.required(json, "eventClass", KIND),
                SpecJson.required(json, "eventHandler", KIND),
                SpecJson.required(json, "anchorGetter", KIND),
                SpecJson.required(json, "cardGetter", KIND),
                SpecJson.required(json, "entityClass", KIND),
                SpecJson.required(json, "entityTemplateGetter", KIND),
                SpecJson.required(json, "entityTemplate", KIND));
    }
}
