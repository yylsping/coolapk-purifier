package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Regression for the 16.6.5 runtime DEX adjudication, including Unicode drift. */
public final class TargetManifest1665ProfileTest {
    private static TargetProfile profile() {
        TargetProfile p = TestManifests.manifest()
                .validatedProfileFor(TestManifests.COOLAPK_16_6_5);
        assertNotNull(p);
        return p;
    }

    @Test
    public void exactVersionHasAllFourteenTargetsAndNeighborsStayUnsupported() throws Exception {
        TargetManifest manifest = TestManifests.manifest();
        TargetProfile p = profile();
        assertEquals("16.6.5", p.versionName);
        assertEquals(TargetProfile.Status.VALIDATED, p.status);
        assertNull(manifest.validatedProfileFor(TestManifests.COOLAPK_16_6_5 - 1));
        assertNull(manifest.validatedProfileFor(TestManifests.COOLAPK_16_6_5 + 1));
        org.json.JSONArray profiles = new JSONObject(new String(TestManifests.rawBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject candidate = profiles.getJSONObject(i);
            if (candidate.getLong("versionCode") == TestManifests.COOLAPK_16_6_5) {
                assertEquals(16, candidate.getJSONObject("targets").length());
                return;
            }
        }
        fail("missing 2610162 profile");
    }

    @Test
    public void holderBindersUseNewU0785EntryPoint() {
        TargetProfile p = profile();
        assertEquals("La15;->\u0785(Ljava/lang/Object;)V", p.replySponsor.descriptor());
        assertEquals("\u0789", p.replySponsor.layoutField);
        assertEquals("Lcom/coolapk/market/view/ad/\u0588;->\u0785(Ljava/lang/Object;)V",
                p.detailSponsorUi.descriptor());
        assertEquals("Lcom/coolapk/market/viewholder/\u0794;->\u0785(Ljava/lang/Object;)V",
                p.relatedIconListUi.descriptor());
        assertEquals("Lcom/coolapk/market/view/feed/reply/\u052e;->\u0793"
                + "(Lcom/coolapk/market/model/HolderItem;)V", p.relatedContentUi.descriptor());
        assertEquals("com.coolapk.market.viewholder.\u0620", p.relatedIconListUi.holderClass);
        assertEquals("\u052f", p.relatedIconListUi.callbackField);
        assertEquals("\u052a", p.relatedIconListUi.fragmentField);
    }

    @Test
    public void sameTopicAndComposeTargetsFollowNewCallChain() {
        TargetProfile p = profile();
        assertEquals("Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                + "onInsertRecommendListEvent(Lf47;)V", p.sameTopic.eventDescriptor());
        assertEquals("\u021c", p.sameTopic.semanticMethod);
        assertEquals("Lff4;->\u078e(Lcom/coolapk/market/model/Feed;Lff4;"
                + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;",
                p.topicDeviceRecommend.descriptor());
        assertEquals("Lmv4;->\u052d(Landroidx/compose/ui/Modifier;"
                + "Lcom/coolapk/market/model/FeedTarget;"
                + "Lcom/coolapk/market/view/feed/reply/FeedDetailV13ViewModel;"
                + "Landroidx/compose/runtime/Composer;I)V", p.topicDeviceRecommendUi.descriptor());
    }

    @Test
    public void searchTargetsKeepCardClassificationAndExactConstructors() {
        TargetProfile p = profile();
        assertEquals("Lew;->\u0785(Ljava/lang/Object;)V", p.searchHotCapsuleUi.descriptor());
        assertEquals("Lzye;->\u0785(Ljava/lang/Object;)V", p.searchHotWordsUi.descriptor());
        assertEquals("Lyye;->\u0785(Ljava/lang/Object;)V", p.searchHotRankingsUi.descriptor());
        assertEquals(java.util.Arrays.asList("android.view.View",
                "androidx.databinding.DataBindingComponent", "n87"),
                p.searchHotWordsUi.constructorParams);
        assertEquals(java.util.Arrays.asList("android.view.View",
                "androidx.databinding.DataBindingComponent", "n87", "wx3"),
                p.searchHotRankingsUi.constructorParams);
        assertEquals("contentEntityType=hotSearch", p.searchHotCapsuleUi.urlContains);
        for (SearchUiCardTargetSpec spec : new SearchUiCardTargetSpec[] {
                p.searchHotCapsuleUi, p.searchHotWordsUi, p.searchHotRankingsUi}) {
            assertEquals("com.coolapk.market.model.Card", spec.cardClass);
        }
    }

    @Test
    public void stableModelAndAutoCommentTargetsRemainUnchanged() {
        TargetProfile previous = TestManifests.manifest()
                .validatedProfileFor(TestManifests.COOLAPK_16_6_4);
        TargetProfile p = profile();
        assertEquals(previous.detailSponsor.descriptor(), p.detailSponsor.descriptor());
        assertEquals(previous.relatedData.descriptor(), p.relatedData.descriptor());
        assertEquals(previous.autoComment.descriptor(), p.autoComment.descriptor());
        assertEquals(previous.autoCommentPrompt.descriptor(), p.autoCommentPrompt.descriptor());
    }
}
