package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class TargetManifestBottomNavigationTest {
    @Test public void eachSupportedVersionKeepsItsOwnVerifiedNativeContract() {
        Object[][] cases = {
                {2608212L, "oc", "pc", "jc8", "\u08a2", true},
                {2609151L, "dp8", "dj", "op8", "\u08b9", false},
                {2609291L, "ko8", "dj", "vo8", "\u08be", false},
                {2610162L, "uv8", "uk", "fw8", "\u08b3", false},
                {2610291L, "xw8", "zk", "ix8", "\u08b3", false}
        };
        TargetManifest manifest = TestManifests.manifest();
        for (Object[] row : cases) {
            long version = (Long) row[0];
            BottomNavigationTargetSpec s = manifest.validatedProfileFor(version).bottomNavigation;
            assertNotNull(s);
            assertEquals(row[1], s.configClass);
            assertEquals(row[2], s.itemClass);
            assertEquals(row[3], s.postSlotClass);
            assertEquals(row[4], s.layoutMethod);
            assertEquals(row[5], s.legacy());
            assertEquals(s.legacy() ? 9 : 15, s.rendererParams.size());
            assertEquals(s.configClass, s.rendererParams.get(1));
            assertEquals(s.postSlotClass, s.rendererParams.get(5));
            assertNull(manifest.validatedProfileFor(version - 1));
            assertNull(manifest.validatedProfileFor(version + 1));
        }
    }

    @Test public void legacyPanelHasItsOwnCompleteSignatureAndHeightBearingConfiguration() {
        BottomNavigationTargetSpec s = TestManifests.manifest()
                .validatedProfileFor(2608212L).bottomNavigation;
        assertEquals("zb8", s.rendererOwner);
        assertEquals("\u07a3", s.panelRendererMethod);
        assertEquals("\u052a", s.currentItemGetter);
        assertEquals("\u0529", s.dataListGetter);
        assertEquals("\u052d", s.refreshDataMethod);
        assertEquals(java.util.Arrays.asList("kotlin.jvm.functions.Function0", "kotlin.jvm.functions.Function1",
                "java.util.List", "int", "java.lang.Integer", "lf8", "gf8", "kf8",
                "androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function7",
                "androidx.compose.runtime.Composer", "int", "int"), s.panelRendererParams);
    }

    @Test public void incompleteLegacyPanelFailsClosedButAbsentNavigationRemainsOptional() throws Exception {
        JSONObject all = new JSONObject(new String(TestManifests.rawBytes(), java.nio.charset.StandardCharsets.UTF_8));
        JSONObject target = all.getJSONArray("profiles").getJSONObject(0).getJSONObject("targets");
        target.getJSONObject("bottomNavigation").remove("panelRendererMethod");
        try {
            TargetManifest.parse(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fail("legacy panel contract must be complete");
        } catch (TargetManifest.ManifestException expected) { }
        target.remove("bottomNavigation");
        TargetManifest parsed = TargetManifest.parse(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(parsed.validatedProfileFor(2608212L).bottomNavigation);
        assertNotNull(parsed.validatedProfileFor(2610291L).bottomNavigation);
    }
}
