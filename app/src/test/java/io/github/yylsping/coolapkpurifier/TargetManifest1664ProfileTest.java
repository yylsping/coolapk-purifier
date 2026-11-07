package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Bundled 16.6.4 (versionCode 2609291) profile contract: exact descriptors
 * adjudicated from the 16.6.4 runtime dex dump. The 16.6.4 adjudication was
 * hit by a manual Unicode transcription error once (sameTopic semanticMethod
 * U+0814 mis-copied as U+0B94), so every non-ASCII identifier is additionally
 * pinned by codepoint, not just by glyph equality.
 */
public final class TargetManifest1664ProfileTest {
    private static TargetProfile profile1664() {
        TargetProfile profile =
                TestManifests.manifest().validatedProfileFor(TestManifests.COOLAPK_16_6_4);
        if (profile == null) {
            throw new AssertionError("bundled manifest must validate 16.6.4");
        }
        return profile;
    }

    private static void assertSingleCodePoint(int expected, String value) {
        assertEquals("single code point expected", 1, value.length());
        assertEquals(expected, value.codePointAt(0));
    }

    @Test
    public void profileIdentityAndTargetCount() throws Exception {
        TargetProfile profile = profile1664();
        assertEquals(2_609_291L, profile.versionCode);
        assertEquals("16.6.4", profile.versionName);
        assertEquals(TargetProfile.Status.VALIDATED, profile.status);

        JSONObject root = new JSONObject(new String(TestManifests.rawBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        org.json.JSONArray profiles = root.getJSONArray("profiles");
        JSONObject profile1664 = null;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject candidate = profiles.getJSONObject(i);
            if (candidate.getLong("versionCode") == 2_609_291L) {
                profile1664 = candidate;
                break;
            }
        }
        if (profile1664 == null) {
            throw new AssertionError("manifest must carry a 2609291 profile");
        }
        assertEquals("validated", profile1664.getString("status"));
        assertEquals(15, profile1664.getJSONObject("targets").length());
    }

    @Test
    public void searchHotUiDescriptorsAreExact() {
        TargetProfile profile = profile1664();
        SearchUiCardTargetSpec capsule = profile.searchHotCapsuleUi;
        assertEquals("Leu;->ވ(Ljava/lang/Object;)V", capsule.descriptor());
        assertEquals("capsuleListCard", capsule.entityTemplate);
        assertEquals("contentEntityType=hotSearch", capsule.urlContains);
        assertEquals(java.util.Arrays.asList("android.view.View",
                        "androidx.databinding.DataBindingComponent"),
                capsule.constructorParams);
        SearchUiCardTargetSpec words = profile.searchHotWordsUi;
        assertEquals("Lwne;->ވ(Ljava/lang/Object;)V", words.descriptor());
        assertEquals("hotSearch", words.entityTemplate);
        assertEquals("", words.urlContains);
        assertEquals(java.util.Arrays.asList("android.view.View",
                        "androidx.databinding.DataBindingComponent", "e27"),
                words.constructorParams);
        SearchUiCardTargetSpec rankings = profile.searchHotRankingsUi;
        assertEquals("Lvne;->ވ(Ljava/lang/Object;)V", rankings.descriptor());
        assertEquals("searchHotListCard", rankings.entityTemplate);
        assertEquals(java.util.Arrays.asList("android.view.View",
                        "androidx.databinding.DataBindingComponent", "e27", "xt3"),
                rankings.constructorParams);
        for (SearchUiCardTargetSpec spec : new SearchUiCardTargetSpec[]{
                capsule, words, rankings}) {
            assertEquals("java.lang.Object", spec.dataClass);
            assertEquals("com.coolapk.market.model.Card", spec.cardClass);
            assertEquals("getEntityTemplate", spec.entityTemplateGetter);
            assertEquals("getUrl", spec.urlGetter);
            assertEquals("androidx.recyclerview.widget.RecyclerView$ViewHolder",
                    spec.viewHolderClass);
        }
    }

    @Test
    public void replySponsorUsesYw4() {
        ReplySponsorTargetSpec spec = profile1664().replySponsor;
        assertEquals("Lyw4;->ވ(Ljava/lang/Object;)V", spec.descriptor());
        assertEquals("މ", spec.layoutField);
        assertEquals(0x0789, spec.layoutField.codePointAt(0));
        assertEquals("feedDetailReplySponsorCard", spec.entityTemplate);
        assertEquals("com.coolapk.market.model.Entity", spec.entityClass);
        assertEquals("com.coolapk.market.view.ad.EntityAdHelper", spec.adHelperClass);
    }

    @Test
    public void sameTopicUsesYx6AndSemanticMethodU0814() {
        SameTopicTargetSpec spec = profile1664().sameTopic;
        assertEquals("yx6", spec.eventClass);
        assertEquals("Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                        + "onInsertRecommendListEvent(Lyx6;)V",
                spec.eventDescriptor());
        // U+0814 — the value once mis-transcribed as U+0B94 by hand.
        assertSingleCodePoint(0x0814, spec.semanticMethod);
        assertEquals("Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                        + "ࠔ(Ljava/lang/Object;)Z",
                spec.semanticDescriptor());
        assertEquals("feedRecommendListCard", spec.entityTemplate);
        assertEquals("Ϳ", spec.anchorGetter);
        assertEquals("Ԩ", spec.cardGetter);
    }

    @Test
    public void topicDeviceRecommendUsesDb4() {
        assertEquals("Ldb4;->ޓ(Lcom/coolapk/market/model/Feed;Ldb4;"
                        + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;",
                profile1664().topicDeviceRecommend.descriptor());
    }

    @Test
    public void topicDeviceRecommendUiUsesKr4AndMethodU052D() {
        TopicDeviceUiTargetSpec spec = profile1664().topicDeviceRecommendUi;
        assertEquals("Lkr4;->ԭ(Landroidx/compose/ui/Modifier;"
                        + "Lcom/coolapk/market/model/FeedTarget;"
                        + "Lcom/coolapk/market/view/feed/reply/FeedDetailV13ViewModel;"
                        + "Landroidx/compose/runtime/Composer;I)V",
                spec.descriptor());
        assertSingleCodePoint(0x052D, spec.methodName);
    }

    @Test
    public void relatedIconListUiOwnerEndsWithU0794() {
        RelatedIconListUiTargetSpec spec = profile1664().relatedIconListUi;
        assertEquals("Lcom/coolapk/market/viewholder/ޔ;->ވ(Ljava/lang/Object;)V",
                spec.descriptor());
        String owner = spec.ownerClass;
        assertEquals(0x0794, owner.codePointAt(owner.length() - 1));
        assertEquals("com.coolapk.market.viewholder.ؠ", spec.holderClass);
        assertEquals("ԯ", spec.callbackField);
        assertEquals("com.coolapk.market.viewholder.ؠ$Ϳ", spec.callbackClass);
        assertEquals("Ԫ", spec.fragmentField);
        assertEquals("iconListCard", spec.cardTemplate);
        assertEquals("goods", spec.promotionEntityType);
        assertEquals("topic", spec.singleRecommendEntityType);
    }

    @Test
    public void unchangedTargetsMatch1661Exactly() {
        TargetProfile p1661 =
                TestManifests.manifest().validatedProfileFor(TestManifests.COOLAPK_16_6_1);
        if (p1661 == null) {
            throw new AssertionError("bundled manifest must validate 16.6.1");
        }
        TargetProfile p1664 = profile1664();
        assertEquals(p1661.detailSponsor.descriptor(), p1664.detailSponsor.descriptor());
        assertEquals(p1661.detailSponsorUi.descriptor(),
                p1664.detailSponsorUi.descriptor());
        assertEquals(p1661.relatedData.descriptor(), p1664.relatedData.descriptor());
        assertEquals(p1661.relatedContentUi.descriptor(),
                p1664.relatedContentUi.descriptor());
        assertEquals(p1661.autoComment.descriptor(), p1664.autoComment.descriptor());
        assertEquals(p1661.autoCommentPrompt.descriptor(),
                p1664.autoCommentPrompt.descriptor());
    }
}
