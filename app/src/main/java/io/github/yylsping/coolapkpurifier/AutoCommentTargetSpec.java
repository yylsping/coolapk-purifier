package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/** D6: exact auto-comment callback suppression target. */
final class AutoCommentTargetSpec extends StaticAssemblerTargetSpec {
    static final String KIND = "staticAssembler";

    private AutoCommentTargetSpec(String ownerClass, String methodName,
                                  java.util.List<String> parameterTypes, String returnType) {
        super(ownerClass, methodName, parameterTypes, returnType);
    }

    @Override
    String kind() {
        return KIND;
    }

    static AutoCommentTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        String[] fields = new String[3];
        java.util.List<String> parameters = parseCommon(json, KIND, fields);
        return new AutoCommentTargetSpec(fields[0], fields[1], parameters, fields[2]);
    }
}
