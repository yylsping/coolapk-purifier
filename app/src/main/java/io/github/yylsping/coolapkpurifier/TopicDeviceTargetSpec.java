package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** D5: exact topic/device target-row assembler suppression target. */
final class TopicDeviceTargetSpec extends StaticAssemblerTargetSpec {
    static final String KIND = "staticAssembler";

    private TopicDeviceTargetSpec(String ownerClass, String methodName,
                                  java.util.List<String> parameterTypes, String returnType) {
        super(ownerClass, methodName, parameterTypes, returnType);
    }

    @Override
    String kind() {
        return KIND;
    }

    static TopicDeviceTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        String[] fields = new String[3];
        java.util.List<String> parameters = parseCommon(json, KIND, fields);
        return new TopicDeviceTargetSpec(fields[0], fields[1], parameters, fields[2]);
    }
}
