package io.github.yylsping.manifestgen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Typed views over the manifest target specs plus the descriptor rendering
 * that mirrors the app's DescriptorUtils.
 */
final class Specs {
    private Specs() {
    }

    static final String[] FEATURE_ORDER = {
            "detailSponsor", "replySponsor", "sameTopic",
            "topicDeviceRecommend", "autoComment"
    };

    static String typeDescriptorOf(String type) {
        switch (type) {
            case "void":
                return "V";
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "char":
                return "C";
            case "short":
                return "S";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            default:
                return classDescriptorOf(type);
        }
    }

    static String classDescriptorOf(String className) {
        return "L" + className.replace('.', '/') + ";";
    }

    static String classNameOf(String descriptor) {
        if (descriptor != null && descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }

    abstract static class FeatureSpec {
        final String kind;

        FeatureSpec(String kind) {
            this.kind = kind;
        }

        abstract String descriptor();

        /** JSON for the draft manifest, with renamed fields applied. */
        abstract JSONObject toJson();
    }

    /** kind=detailSponsor (D1). */
    static final class DetailSpec extends FeatureSpec {
        final String ownerClass;
        final String methodName;
        final String returnType;

        DetailSpec(JSONObject json) {
            super("detailSponsor");
            this.ownerClass = json.getString("ownerClass");
            this.methodName = json.getString("methodName");
            this.returnType = json.getString("returnType");
        }

        DetailSpec(String ownerClass, String methodName, String returnType) {
            super("detailSponsor");
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.returnType = returnType;
        }

        @Override
        String descriptor() {
            return classDescriptorOf(ownerClass) + "->" + methodName
                    + "()" + typeDescriptorOf(returnType);
        }

        @Override
        JSONObject toJson() {
            return new JSONObject()
                    .put("kind", kind)
                    .put("ownerClass", ownerClass)
                    .put("methodName", methodName)
                    .put("returnType", returnType);
        }
    }

    /** kind=staticAssembler (D5/D6). */
    static final class StaticSpec extends FeatureSpec {
        final String ownerClass;
        final String methodName;
        final List<String> parameterTypes;
        final String returnType;

        StaticSpec(JSONObject json) {
            super("staticAssembler");
            this.ownerClass = json.getString("ownerClass");
            this.methodName = json.getString("methodName");
            this.parameterTypes = toList(json.getJSONArray("parameterTypes"));
            this.returnType = json.getString("returnType");
        }

        StaticSpec(String ownerClass, String methodName,
                   List<String> parameterTypes, String returnType) {
            super("staticAssembler");
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.returnType = returnType;
        }

        @Override
        String descriptor() {
            StringBuilder sb = new StringBuilder();
            sb.append(classDescriptorOf(ownerClass)).append("->")
                    .append(methodName).append('(');
            for (String parameter : parameterTypes) {
                sb.append(typeDescriptorOf(parameter));
            }
            sb.append(')').append(typeDescriptorOf(returnType));
            return sb.toString();
        }

        @Override
        JSONObject toJson() {
            return new JSONObject()
                    .put("kind", kind)
                    .put("ownerClass", ownerClass)
                    .put("methodName", methodName)
                    .put("parameterTypes", new JSONArray(parameterTypes))
                    .put("returnType", returnType);
        }
    }

    /** kind=replySponsor (D2). */
    static final class ReplySpec extends FeatureSpec {
        final String ownerClass;
        final String methodName;
        final String layoutField;
        final String entityClass;
        final String entityTemplateGetter;
        final String entityTemplate;
        final String adHelperClass;
        final String bindingComponentClass;
        final String viewHolderClass;

        ReplySpec(JSONObject json) {
            super("replySponsor");
            this.ownerClass = json.getString("ownerClass");
            this.methodName = json.getString("methodName");
            this.layoutField = json.getString("layoutField");
            this.entityClass = json.getString("entityClass");
            this.entityTemplateGetter = json.getString("entityTemplateGetter");
            this.entityTemplate = json.getString("entityTemplate");
            this.adHelperClass = json.getString("adHelperClass");
            this.bindingComponentClass = json.getString("bindingComponentClass");
            this.viewHolderClass = json.getString("viewHolderClass");
        }

        private ReplySpec(ReplySpec base, String ownerClass, String methodName,
                          String layoutField) {
            super("replySponsor");
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.layoutField = layoutField;
            this.entityClass = base.entityClass;
            this.entityTemplateGetter = base.entityTemplateGetter;
            this.entityTemplate = base.entityTemplate;
            this.adHelperClass = base.adHelperClass;
            this.bindingComponentClass = base.bindingComponentClass;
            this.viewHolderClass = base.viewHolderClass;
        }

        ReplySpec renamed(String ownerClass, String methodName, String layoutField) {
            return new ReplySpec(this, ownerClass, methodName, layoutField);
        }

        @Override
        String descriptor() {
            return classDescriptorOf(ownerClass) + "->" + methodName
                    + "(Ljava/lang/Object;)V layout=" + layoutField
                    + " template=" + entityTemplate;
        }

        @Override
        JSONObject toJson() {
            return new JSONObject()
                    .put("kind", kind)
                    .put("ownerClass", ownerClass)
                    .put("methodName", methodName)
                    .put("layoutField", layoutField)
                    .put("entityClass", entityClass)
                    .put("entityTemplateGetter", entityTemplateGetter)
                    .put("entityTemplate", entityTemplate)
                    .put("adHelperClass", adHelperClass)
                    .put("bindingComponentClass", bindingComponentClass)
                    .put("viewHolderClass", viewHolderClass);
        }
    }

    /** kind=sameTopic (D3). */
    static final class TopicSpec extends FeatureSpec {
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

        TopicSpec(JSONObject json) {
            super("sameTopic");
            this.ownerClass = json.getString("ownerClass");
            this.ownerParentClass = json.getString("ownerParentClass");
            this.semanticMethod = json.getString("semanticMethod");
            this.eventClass = json.getString("eventClass");
            this.eventHandler = json.getString("eventHandler");
            this.anchorGetter = json.getString("anchorGetter");
            this.cardGetter = json.getString("cardGetter");
            this.entityClass = json.getString("entityClass");
            this.entityTemplateGetter = json.getString("entityTemplateGetter");
            this.entityTemplate = json.getString("entityTemplate");
        }

        private TopicSpec(TopicSpec base, String ownerClass, String semanticMethod,
                          String eventClass, String eventHandler,
                          String anchorGetter, String cardGetter) {
            super("sameTopic");
            this.ownerClass = ownerClass;
            this.ownerParentClass = base.ownerParentClass;
            this.semanticMethod = semanticMethod;
            this.eventClass = eventClass;
            this.eventHandler = eventHandler;
            this.anchorGetter = anchorGetter;
            this.cardGetter = cardGetter;
            this.entityClass = base.entityClass;
            this.entityTemplateGetter = base.entityTemplateGetter;
            this.entityTemplate = base.entityTemplate;
        }

        TopicSpec renamed(String ownerClass, String semanticMethod, String eventClass,
                          String eventHandler, String anchorGetter, String cardGetter) {
            return new TopicSpec(this, ownerClass, semanticMethod, eventClass,
                    eventHandler, anchorGetter, cardGetter);
        }

        @Override
        String descriptor() {
            return classDescriptorOf(ownerClass) + "->" + eventHandler + "("
                    + classDescriptorOf(eventClass) + ")V semantic="
                    + semanticMethod + " template=" + entityTemplate;
        }

        @Override
        JSONObject toJson() {
            return new JSONObject()
                    .put("kind", kind)
                    .put("ownerClass", ownerClass)
                    .put("ownerParentClass", ownerParentClass)
                    .put("semanticMethod", semanticMethod)
                    .put("eventClass", eventClass)
                    .put("eventHandler", eventHandler)
                    .put("anchorGetter", anchorGetter)
                    .put("cardGetter", cardGetter)
                    .put("entityClass", entityClass)
                    .put("entityTemplateGetter", entityTemplateGetter)
                    .put("entityTemplate", entityTemplate);
        }
    }

    static FeatureSpec parse(String feature, JSONObject json) {
        switch (feature) {
            case "detailSponsor":
                return new DetailSpec(json);
            case "replySponsor":
                return new ReplySpec(json);
            case "sameTopic":
                return new TopicSpec(json);
            case "topicDeviceRecommend":
            case "autoComment":
                return new StaticSpec(json);
            default:
                throw new IllegalArgumentException("unknown feature " + feature);
        }
    }

    static JSONObject baselineTargets(JSONObject manifest, long versionCode) {
        JSONArray profiles = manifest.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            if (profile.getLong("versionCode") == versionCode) {
                return profile.getJSONObject("targets");
            }
        }
        // Fallback: first validated profile.
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            if ("validated".equals(profile.optString("status"))) {
                return profile.getJSONObject("targets");
            }
        }
        return profiles.getJSONObject(0).getJSONObject("targets");
    }

    static JSONObject baselineProfile(JSONObject manifest) {
        JSONArray profiles = manifest.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            if ("validated".equals(profile.optString("status"))) {
                return profile;
            }
        }
        return profiles.getJSONObject(0);
    }

    private static List<String> toList(JSONArray array) {
        List<String> values = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return Collections.unmodifiableList(values);
    }
}
