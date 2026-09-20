package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/**
 * D5 terminal UI target: the exact FeedNewTargetRowUI composable
 * (Modifier, FeedTarget, FeedDetailV13ViewModel, Composer, int) -> void.
 * This is the only D5 suppression point; the outer assembler is observe-only.
 */
final class TopicDeviceUiTargetSpec extends StaticAssemblerTargetSpec {
    static final String KIND = "staticAssembler";

    private TopicDeviceUiTargetSpec(String ownerClass, String methodName,
                                    java.util.List<String> parameterTypes, String returnType) {
        super(ownerClass, methodName, parameterTypes, returnType);
    }

    @Override
    String kind() {
        return KIND;
    }

    static TopicDeviceUiTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        String[] fields = new String[3];
        java.util.List<String> parameters = parseCommon(json, KIND, fields);
        return new TopicDeviceUiTargetSpec(fields[0], fields[1], parameters, fields[2]);
    }
}
