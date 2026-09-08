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
    public void acceptsOnlyPinnedHostVersion() {
        assertTrue(D1DetailSponsorDelta.isExactHostVersion(2_608_212L));
        assertFalse(D1DetailSponsorDelta.isExactHostVersion(2_608_211L));
    }

    @Test
    public void acceptsExactConcreteGetter() throws Exception {
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getDetailSponsorCard");
        Method wrongOwner = WrongOwner.class.getDeclaredMethod("getDetailSponsorCard");

        assertTrue(D1DetailSponsorDelta.isExactTarget(exact));
        assertFalse(D1DetailSponsorDelta.isExactTarget(wrongOwner));
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
