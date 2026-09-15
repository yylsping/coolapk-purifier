package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.coolapk.market.model.$$AutoValue_Feed;
import com.coolapk.market.model.Entity;

import java.lang.reflect.Method;

import org.junit.Test;

public final class D1DetailSponsorDeltaTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptor() {
        DetailSponsorTargetSpec spec = TestManifests.profile().detailSponsor;
        org.junit.Assert.assertEquals("Lcom/coolapk/market/model/$$AutoValue_Feed;->"
                        + "getDetailSponsorCard()Lcom/coolapk/market/model/Entity;",
                spec.descriptor());
    }

    @Test
    public void acceptsExactConcreteGetter() throws Exception {
        DetailSponsorTargetSpec spec = TestManifests.profile().detailSponsor;
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getDetailSponsorCard");
        Method wrongOwner = WrongOwner.class.getDeclaredMethod("getDetailSponsorCard");

        assertTrue(D1DetailSponsorDelta.isExactTarget(spec, exact));
        assertFalse(D1DetailSponsorDelta.isExactTarget(spec, wrongOwner));
    }

    @Test
    public void tamperedSpecNeverVerifies() throws Exception {
        org.json.JSONObject json = new org.json.JSONObject()
                .put("ownerClass", "com.coolapk.market.model.Entity")
                .put("methodName", "getDetailSponsorCard")
                .put("returnType", "com.coolapk.market.model.Entity");
        DetailSponsorTargetSpec tampered = DetailSponsorTargetSpec.parse(json);
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getDetailSponsorCard");
        assertFalse(D1DetailSponsorDelta.isExactTarget(tampered, exact));
    }

    @Test
    public void disabledAndNullPathsPreserveOriginal() {
        Entity original = new Entity();

        assertSame(original, D1DetailSponsorDelta.applyDelta(false, original));
        assertNull(D1DetailSponsorDelta.applyDelta(true, null));
        assertNull(D1DetailSponsorDelta.applyDelta(true, original));
    }

    private static final class WrongOwner {
        public Entity getDetailSponsorCard() {
            return new Entity();
        }
    }
}
