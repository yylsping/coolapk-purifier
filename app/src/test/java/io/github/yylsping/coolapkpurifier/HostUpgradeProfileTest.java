package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class HostUpgradeProfileTest {
    @Test public void everySupportedVersionPinsItsOwnUpdateTargets() {
        Object[][] expected = {
                {2608212L,"qu1","\u087b","\u0794","\u0789","\u08b7","\u09fc"},
                {2609151L,"t32","\u087b","\u0794","\u0789","\u09fc","\u09fc"},
                {2609291L,"q32","\u087b","\u0795","\u0789","\u09fc","\u09fc"},
                {2610162L,"x62","\u087c","\u0795","\u0789","\u0f88","\u09fc"},
                {2610291L,"y72","\u087c","\u079a","\u0789","\u0f88","\u09fc"},
                {2611121L,"kc2","\u087e","\u079a","\u078d","\u0f88","\u0c80"}};
        for (Object[] row : expected) {
            HostUpgradeTargetSpec spec = TestManifests.manifest().validatedProfileFor((Long) row[0]).hostUpgrade;
            assertNotNull(spec);
            assertEquals(row[1], spec.checkOwner);
            assertEquals(row[2], spec.checkMethod);
            assertEquals(row[3], spec.justMethod);
            assertEquals(row[4], spec.popupMethod);
            assertEquals(row[5], spec.manualCheckMethod);
            assertEquals(row[6], spec.downloadMethod);
        }
    }

    @Test public void missingOptionalUpdateTargetKeepsOtherFeaturesAvailable() throws Exception {
        JSONObject root = new JSONObject(new String(TestManifests.rawBytes(), StandardCharsets.UTF_8));
        root.getJSONArray("profiles").getJSONObject(0).getJSONObject("targets").remove("hostUpgrade");
        TargetProfile profile = TargetManifest.parse(root.toString().getBytes(StandardCharsets.UTF_8))
                .validatedProfileFor(2608212);
        assertNull(profile.hostUpgrade);
        assertNotNull(profile.replySponsor);
        assertNotNull(profile.bottomNavigation);
    }
}
