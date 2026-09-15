package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

import java.util.List;

/**
 * Shared shape of the exact static-assembler targets (D5 topic/device
 * recommend, D6 auto comment): a static, non-abstract method with an exact
 * parameter list and return type.
 */
abstract class StaticAssemblerTargetSpec {
    final String ownerClass;
    final String methodName;
    final List<String> parameterTypes;
    final String returnType;

    StaticAssemblerTargetSpec(String ownerClass, String methodName,
                              List<String> parameterTypes, String returnType) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
    }

    abstract String kind();

    String descriptor() {
        StringBuilder sb = new StringBuilder();
        sb.append(DescriptorUtils.classDescriptorOf(ownerClass)).append("->")
                .append(methodName).append('(');
        for (String parameter : parameterTypes) {
            sb.append(DescriptorUtils.typeDescriptorOf(parameter));
        }
        sb.append(')').append(DescriptorUtils.typeDescriptorOf(returnType));
        return sb.toString();
    }

    static List<String> parseCommon(JSONObject json, String kind, String[] out)
            throws TargetManifest.ManifestException {
        out[0] = SpecJson.required(json, "ownerClass", kind);
        out[1] = SpecJson.required(json, "methodName", kind);
        out[2] = SpecJson.required(json, "returnType", kind);
        return SpecJson.requiredArray(json, "parameterTypes", kind);
    }
}
