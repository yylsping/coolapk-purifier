package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Manifest §8.1: parser fail-closed coverage. */
public final class TargetManifestParserTest {
    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String manifestJson(String... profiles) {
        return "{\"schema\":1,\"profiles\":[" + String.join(",", profiles) + "]}";
    }

    private static String profileJson(long versionCode, String status) {
        return "{\"versionCode\":" + versionCode + ",\"versionName\":\"x\""
                + ",\"status\":\"" + status + "\",\"targets\":{}}";
    }

    private static void assertRejected(byte[] json) {
        try {
            TargetManifest.parse(json);
            fail("manifest must be rejected: "
                    + (json == null ? "null" : new String(json, StandardCharsets.UTF_8)));
        } catch (TargetManifest.ManifestException expected) {
            // fail-closed: the whole manifest is unusable, never a partial one
        }
    }

    /** The bundled 16.6.1 validated profile JSON, for tests needing a complete one. */
    private static String bundledValidatedProfileJson() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(
                new String(TestManifests.rawBytes(), StandardCharsets.UTF_8));
        org.json.JSONArray profiles = root.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            org.json.JSONObject profile = profiles.getJSONObject(i);
            if (profile.optLong("versionCode") == TestManifests.COOLAPK_16_6_1
                    && "validated".equals(profile.optString("status"))) {
                return profile.toString();
            }
        }
        throw new AssertionError("bundled validated 16.6.1 profile not found");
    }

    @Test
    public void bundledManifestParsesAsSchemaOne() {
        TargetManifest manifest = TestManifests.manifest();
        assertEquals(1, manifest.schema);
        assertFalse(manifest.profiles.isEmpty());
    }

    @Test
    public void schemaOneWithMinimalDraftProfileParses() throws Exception {
        TargetManifest manifest = TargetManifest.parse(
                bytes(manifestJson(profileJson(2608212L, "draft"))));
        assertEquals(1, manifest.profiles.size());
        assertEquals(2608212L, manifest.profiles.get(0).versionCode);
    }

    @Test
    public void validatedProfileWithEmptyTargetsRejected() {
        // §6: status=validated with missing/empty targets is not a usable
        // production profile; the whole manifest fails closed.
        assertRejected(bytes(manifestJson(profileJson(2608212L, "validated"))));
    }

    @Test
    public void validatedProfileMissingOneTargetRejected() throws Exception {
        org.json.JSONObject profile = new org.json.JSONObject(bundledValidatedProfileJson());
        profile.getJSONObject("targets").remove("autoComment");
        assertRejected(bytes(manifestJson(profile.toString())));
    }

    @Test
    public void validatedProfileMissingAutoCommentPromptRejected() throws Exception {
        // D6 is a pair: the prompt suppression point is as mandatory as the
        // controller target in a validated profile.
        org.json.JSONObject profile = new org.json.JSONObject(bundledValidatedProfileJson());
        profile.getJSONObject("targets").remove("autoCommentPrompt");
        assertRejected(bytes(manifestJson(profile.toString())));
    }

    @Test
    public void validatedProfileMissingTopicDeviceRecommendUiRejected() throws Exception {
        // D5 is a pair: the terminal UI suppression point is as mandatory as
        // the observe-only assembler target in a validated profile.
        org.json.JSONObject profile = new org.json.JSONObject(bundledValidatedProfileJson());
        profile.getJSONObject("targets").remove("topicDeviceRecommendUi");
        assertRejected(bytes(manifestJson(profile.toString())));
    }

    @Test
    public void validatedProfileMissingDetailSponsorUiRejected() throws Exception {
        // D1 is a pair: the exact binder-hide point is as mandatory as the
        // observe-only getter target in a validated profile.
        org.json.JSONObject profile = new org.json.JSONObject(bundledValidatedProfileJson());
        profile.getJSONObject("targets").remove("detailSponsorUi");
        assertRejected(bytes(manifestJson(profile.toString())));
    }

    @Test
    public void unknownTargetKeyRejected() throws Exception {
        org.json.JSONObject profile = new org.json.JSONObject(bundledValidatedProfileJson());
        profile.getJSONObject("targets").put("mysteryTarget", new org.json.JSONObject());
        assertRejected(bytes(manifestJson(profile.toString())));
        // Unknown keys fail closed for draft profiles too.
        org.json.JSONObject draft = new org.json.JSONObject(
                profileJson(2608212L, "draft"));
        draft.getJSONObject("targets").put("mysteryTarget", new org.json.JSONObject());
        assertRejected(bytes(manifestJson(draft.toString())));
    }

    @Test
    public void bundledValidated1661ProfileHasAllTargets() {
        TargetProfile profile = TestManifests.profile();
        assertEquals(TargetProfile.Status.VALIDATED, profile.status);
        assertTrue(profile.detailSponsor != null);
        assertTrue(profile.detailSponsorUi != null);
        assertTrue(profile.replySponsor != null);
        assertTrue(profile.sameTopic != null);
        assertTrue(profile.topicDeviceRecommend != null);
        assertTrue(profile.topicDeviceRecommendUi != null);
        assertTrue(profile.autoComment != null);
        assertTrue(profile.autoCommentPrompt != null);
        assertTrue(profile.relatedData != null);
        assertTrue(profile.relatedIconListUi != null);
        assertTrue(profile.relatedContentUi != null);
    }

    @Test
    public void validatedProfileRequiresGetterButAllowsIndependentUiPartial() throws Exception {
        org.json.JSONObject missingGetter =
                new org.json.JSONObject(bundledValidatedProfileJson());
        missingGetter.getJSONObject("targets").remove("relatedData");
        assertRejected(bytes(manifestJson(missingGetter.toString())));

        org.json.JSONObject partial =
                new org.json.JSONObject(bundledValidatedProfileJson());
        partial.getJSONObject("targets").remove("relatedIconListUi");
        TargetManifest parsed = TargetManifest.parse(
                bytes(manifestJson(partial.toString())));
        TargetProfile profile = parsed.validatedProfileFor(TestManifests.COOLAPK_16_6_1);
        assertTrue(profile != null);
        assertNull(profile.relatedIconListUi);
        assertTrue(profile.relatedContentUi != null);
    }

    @Test
    public void emptyOrNullInputRejected() {
        assertRejected(null);
        assertRejected(new byte[0]);
    }

    @Test
    public void malformedJsonRejected() {
        assertRejected(bytes("{not json"));
        assertRejected(bytes("[1,2,3]"));
    }

    @Test
    public void unknownSchemaRejected() {
        assertRejected(bytes("{\"schema\":2,\"profiles\":[]}"));
        assertRejected(bytes("{\"schema\":0,\"profiles\":[]}"));
        assertRejected(bytes("{\"profiles\":[]}"));
    }

    @Test
    public void profilesMissingRejected() {
        assertRejected(bytes("{\"schema\":1}"));
    }

    @Test
    public void profileEntryNotObjectRejected() {
        assertRejected(bytes("{\"schema\":1,\"profiles\":[42]}"));
    }

    @Test
    public void versionCodeMissingOrInvalidRejected() {
        assertRejected(bytes(manifestJson(
                "{\"versionName\":\"x\",\"status\":\"validated\",\"targets\":{}}")));
        assertRejected(bytes(manifestJson(profileJson(0L, "validated"))));
        assertRejected(bytes(manifestJson(profileJson(-5L, "validated"))));
    }

    @Test
    public void duplicateVersionCodeRejected() {
        assertRejected(bytes(manifestJson(
                profileJson(2608212L, "validated"),
                profileJson(2608212L, "draft"))));
    }

    @Test
    public void unknownStatusRejected() {
        assertRejected(bytes(manifestJson(profileJson(2608212L, "beta"))));
        assertRejected(bytes(manifestJson(profileJson(2608212L, ""))));
    }

    @Test
    public void targetsMissingRejected() {
        assertRejected(bytes(manifestJson(
                "{\"versionCode\":2608212,\"status\":\"validated\"}")));
    }

    @Test
    public void targetMissingRequiredFieldRejected() {
        // detailSponsor present but without ownerClass must not silently
        // become a valid target.
        assertRejected(bytes("{\"schema\":1,\"profiles\":[{"
                + "\"versionCode\":2608212,\"status\":\"validated\",\"targets\":{"
                + "\"detailSponsor\":{\"methodName\":\"a\",\"returnType\":\"b\"}"
                + "}}]}"));
    }

    @Test
    public void staticAssemblerTargetMissingParameterTypesRejected() {
        assertRejected(bytes("{\"schema\":1,\"profiles\":[{"
                + "\"versionCode\":2608212,\"status\":\"validated\",\"targets\":{"
                + "\"topicDeviceRecommend\":{\"ownerClass\":\"a\",\"methodName\":\"b\""
                + ",\"returnType\":\"c\"}"
                + "}}]}"));
    }

    @Test
    public void staticAssemblerTargetEmptyParameterEntryRejected() {
        assertRejected(bytes("{\"schema\":1,\"profiles\":[{"
                + "\"versionCode\":2608212,\"status\":\"validated\",\"targets\":{"
                + "\"topicDeviceRecommend\":{\"ownerClass\":\"a\",\"methodName\":\"b\""
                + ",\"returnType\":\"c\",\"parameterTypes\":[\"\"]}"
                + "}}]}"));
    }

    @Test
    public void draftProfileNeverReachesInstallPath() throws Exception {
        TargetManifest manifest = TargetManifest.parse(
                bytes(manifestJson(profileJson(2608212L, "draft"))));
        // draft parses for diagnostics but is never a validated profile.
        assertNull(manifest.validatedProfileFor(2608212L));
        assertEquals("draft", manifest.profileStatus(2608212L));
        assertEquals("missing", manifest.profileStatus(1L));
    }

    @Test
    public void validatedProfileSelectedAlongsideDraft() throws Exception {
        TargetManifest manifest = TargetManifest.parse(bytes(manifestJson(
                bundledValidatedProfileJson(),
                profileJson(2608213L, "draft"))));
        assertTrue(manifest.validatedProfileFor(2608212L) != null);
        assertNull(manifest.validatedProfileFor(2608213L));
    }
}
