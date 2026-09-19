package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/**
 * D6 prompt target: the exact terminal UI action inside the host
 * full-visible flow (the addAutoShowFeedCommentView$1 static lambda that
 * shows the quick-comment bar). This is the only D6 suppression point.
 */
final class AutoCommentPromptTargetSpec extends StaticAssemblerTargetSpec {
    static final String KIND = "staticAssembler";

    private AutoCommentPromptTargetSpec(String ownerClass, String methodName,
                                        java.util.List<String> parameterTypes,
                                        String returnType) {
        super(ownerClass, methodName, parameterTypes, returnType);
    }

    @Override
    String kind() {
        return KIND;
    }

    static AutoCommentPromptTargetSpec parse(JSONObject json)
            throws TargetManifest.ManifestException {
        if (json == null) {
            return null;
        }
        String[] fields = new String[3];
        java.util.List<String> parameters = parseCommon(json, KIND, fields);
        return new AutoCommentPromptTargetSpec(fields[0], fields[1], parameters, fields[2]);
    }
}
