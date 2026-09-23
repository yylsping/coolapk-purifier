package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;

/**
 * One host-version profile: every target spec for a single Coolapk
 * versionCode. A target that fails to parse rejects the whole profile —
 * a partially valid profile must never reach the install path.
 */
final class TargetProfile {
    enum Status {
        VALIDATED("validated"),
        DRAFT("draft");

        final String jsonValue;

        Status(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        static Status from(String value) throws TargetManifest.ManifestException {
            for (Status status : values()) {
                if (status.jsonValue.equals(value)) {
                    return status;
                }
            }
            throw new TargetManifest.ManifestException("unknown profile status=" + value);
        }
    }

    final long versionCode;
    final String versionName;
    final Status status;
    final DetailSponsorTargetSpec detailSponsor;
    final DetailSponsorUiTargetSpec detailSponsorUi;
    final ReplySponsorTargetSpec replySponsor;
    final SameTopicTargetSpec sameTopic;
    final TopicDeviceTargetSpec topicDeviceRecommend;
    final TopicDeviceUiTargetSpec topicDeviceRecommendUi;
    final AutoCommentTargetSpec autoComment;
    final AutoCommentPromptTargetSpec autoCommentPrompt;
    final RelatedDataTargetSpec relatedData;
    final RelatedIconListUiTargetSpec relatedIconListUi;
    final RelatedContentUiTargetSpec relatedContentUi;

    private TargetProfile(long versionCode, String versionName, Status status,
                          DetailSponsorTargetSpec detailSponsor,
                          DetailSponsorUiTargetSpec detailSponsorUi,
                          ReplySponsorTargetSpec replySponsor,
                          SameTopicTargetSpec sameTopic,
                          TopicDeviceTargetSpec topicDeviceRecommend,
                          TopicDeviceUiTargetSpec topicDeviceRecommendUi,
                          AutoCommentTargetSpec autoComment,
                          AutoCommentPromptTargetSpec autoCommentPrompt,
                          RelatedDataTargetSpec relatedData,
                          RelatedIconListUiTargetSpec relatedIconListUi,
                          RelatedContentUiTargetSpec relatedContentUi) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.status = status;
        this.detailSponsor = detailSponsor;
        this.detailSponsorUi = detailSponsorUi;
        this.replySponsor = replySponsor;
        this.sameTopic = sameTopic;
        this.topicDeviceRecommend = topicDeviceRecommend;
        this.topicDeviceRecommendUi = topicDeviceRecommendUi;
        this.autoComment = autoComment;
        this.autoCommentPrompt = autoCommentPrompt;
        this.relatedData = relatedData;
        this.relatedIconListUi = relatedIconListUi;
        this.relatedContentUi = relatedContentUi;
    }

    static TargetProfile parse(JSONObject json) throws TargetManifest.ManifestException {
        if (!json.has("versionCode")) {
            throw new TargetManifest.ManifestException("profile versionCode missing");
        }
        long versionCode = json.optLong("versionCode", -1L);
        if (versionCode <= 0) {
            throw new TargetManifest.ManifestException("profile versionCode invalid");
        }
        String versionName = json.optString("versionName", "unknown");
        Status status = Status.from(json.optString("status", ""));
        JSONObject targets = json.optJSONObject("targets");
        if (targets == null) {
            throw new TargetManifest.ManifestException(
                    "profile " + versionCode + " targets missing");
        }
        // Fail-closed structure: unknown target keys reject the whole profile
        // instead of being silently ignored.
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(
                "detailSponsor", "detailSponsorUi", "replySponsor", "sameTopic",
                "topicDeviceRecommend", "topicDeviceRecommendUi",
                "autoComment", "autoCommentPrompt", "relatedData",
                "relatedIconListUi", "relatedContentUi"));
        for (java.util.Iterator<String> keys = targets.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!known.contains(key)) {
                throw new TargetManifest.ManifestException(
                        "profile " + versionCode + " unknown target key=" + key);
            }
        }
        TargetProfile profile = new TargetProfile(versionCode, versionName, status,
                DetailSponsorTargetSpec.parse(targets.optJSONObject("detailSponsor")),
                DetailSponsorUiTargetSpec.parse(targets.optJSONObject("detailSponsorUi")),
                ReplySponsorTargetSpec.parse(targets.optJSONObject("replySponsor")),
                SameTopicTargetSpec.parse(targets.optJSONObject("sameTopic")),
                TopicDeviceTargetSpec.parse(targets.optJSONObject("topicDeviceRecommend")),
                TopicDeviceUiTargetSpec.parse(targets.optJSONObject("topicDeviceRecommendUi")),
                AutoCommentTargetSpec.parse(targets.optJSONObject("autoComment")),
                AutoCommentPromptTargetSpec.parse(targets.optJSONObject("autoCommentPrompt")),
                RelatedDataTargetSpec.parse(targets.optJSONObject("relatedData")),
                RelatedIconListUiTargetSpec.parse(targets.optJSONObject("relatedIconListUi")),
                RelatedContentUiTargetSpec.parse(targets.optJSONObject("relatedContentUi")));
        if (status == Status.VALIDATED) {
            // A validated profile is a production contract: every
            // manifest-managed target must exist and be fully typed. Missing
            // or empty targets make the whole manifest fail closed.
            profile.requireComplete(profile.detailSponsor, "detailSponsor");
            profile.requireComplete(profile.detailSponsorUi, "detailSponsorUi");
            profile.requireComplete(profile.replySponsor, "replySponsor");
            profile.requireComplete(profile.sameTopic, "sameTopic");
            profile.requireComplete(profile.topicDeviceRecommend, "topicDeviceRecommend");
            profile.requireComplete(profile.topicDeviceRecommendUi, "topicDeviceRecommendUi");
            profile.requireComplete(profile.autoComment, "autoComment");
            profile.requireComplete(profile.autoCommentPrompt, "autoCommentPrompt");
            // The observe-only getter is mandatory. UI subtype targets are
            // intentionally independent: a validated profile may represent
            // honest PARTIAL coverage by omitting an unavailable subtype.
            profile.requireComplete(profile.relatedData, "relatedData");
        }
        return profile;
    }

    private void requireComplete(Object spec, String kind)
            throws TargetManifest.ManifestException {
        if (spec == null) {
            throw new TargetManifest.ManifestException(
                    "validated profile " + versionCode + " missing target=" + kind);
        }
    }
}
