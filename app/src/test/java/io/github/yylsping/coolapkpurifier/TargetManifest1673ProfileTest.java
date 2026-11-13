package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class TargetManifest1673ProfileTest {
    private static TargetProfile profile() {
        TargetProfile profile = TestManifests.manifest().validatedProfileFor(2611121);
        assertNotNull(profile);
        return profile;
    }

    @Test public void exactVersionSelectsAllTargetsAndNeverNeighbors() {
        TargetManifest manifest = TestManifests.manifest();
        TargetProfile p = profile();
        assertEquals("16.7.3", p.versionName);
        assertNull(manifest.validatedProfileFor(2611120));
        assertNull(manifest.validatedProfileFor(2611122));
        assertNotNull(p.relatedIconListUi);
        assertNotNull(p.relatedContentUi);
        assertNotNull(p.autoCommentPrompt);
        assertNotNull(p.hostUpgrade);
    }

    @Test public void changedBindersAndComposeTerminalsUse1673Descriptors() {
        TargetProfile p = profile();
        assertEquals("Ll65;->\u0785(Ljava/lang/Object;)V", p.replySponsor.descriptor());
        assertEquals("t97", p.sameTopic.eventClass);
        assertEquals("qk4", p.topicDeviceRecommend.ownerClass);
        assertEquals("x05", p.topicDeviceRecommendUi.ownerClass);
        assertEquals("lw", p.searchHotCapsuleUi.ownerClass);
        assertEquals("y7f", p.searchHotWordsUi.ownerClass);
        assertEquals("x7f", p.searchHotRankingsUi.ownerClass);
        assertEquals(Arrays.asList("android.view.View", "androidx.databinding.DataBindingComponent", "be7", "h34"),
                p.searchHotRankingsUi.constructorParams);
    }

    @Test public void navigationPinsBothNewSharedComponentsAndPreservesModelContract() {
        BottomNavigationTargetSpec nav = profile().bottomNavigation;
        assertTrue(nav.genericGlass);
        assertFalse(nav.legacy());
        assertEquals("\u052c", nav.rendererMethod);
        assertEquals("w09", nav.configClass);
        assertEquals("el", nav.itemClass);
        assertEquals("h19", nav.postSlotClass);
        assertEquals("vt1", nav.glassRendererOwner);
        assertEquals("vt1", nav.slotsOwner);
        assertEquals(18, nav.glassRendererParams.size());
        assertEquals("java.util.List", nav.glassRendererParams.get(1));
        assertEquals("int", nav.glassRendererParams.get(0));
        assertEquals(15, nav.slotsParams.size());
        assertFalse(TestManifests.manifest().validatedProfileFor(2610291).bottomNavigation.genericGlass);
    }

    @Test public void hostUpgradePinsRepositoryAndScalarFactoryAfterObfuscationDrift() {
        HostUpgradeTargetSpec spec = profile().hostUpgrade;
        assertEquals("kc2", spec.checkOwner);
        assertEquals("\u087e", spec.checkMethod);
        assertEquals("rx.\u052a", spec.observableClass);
        assertEquals("\u079a", spec.justMethod);
        assertEquals("\u078d", spec.popupMethod);
        assertEquals("\u0c80", spec.downloadMethod);
    }

    @Test public void malformedHostUpgradeAndUnknownNavigationContractRejectManifest() throws Exception {
        for (boolean navigation : new boolean[]{false, true}) {
            JSONObject root = new JSONObject(new String(TestManifests.rawBytes(), StandardCharsets.UTF_8));
            JSONObject targets = root.getJSONArray("profiles").getJSONObject(root.getJSONArray("profiles").length()-1)
                    .getJSONObject("targets");
            if (navigation) targets.getJSONObject("bottomNavigation").put("contract", "unverified");
            else targets.getJSONObject("hostUpgrade").remove("justMethod");
            try {
                TargetManifest.parse(root.toString().getBytes(StandardCharsets.UTF_8));
                fail("malformed contract must fail closed");
            } catch (TargetManifest.ManifestException expected) { }
        }
    }
}
