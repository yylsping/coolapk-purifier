package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.coolapk.market.model.Entity;

import java.lang.reflect.Method;

import org.junit.Test;

public final class D2ReplySponsorReplacementTest {
    @Test
    public void acceptsExactPinnedBinderContract() throws Exception {
        Class<?> owner = Class.forName("fn4");
        Method target = owner.getDeclaredMethod("\u0788", Object.class);
        assertTrue(D2ReplySponsorReplacement.isExactTarget(
                target, owner.getClassLoader()));
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                WrongOwner.class.getDeclaredMethod("\u0788", Object.class),
                owner.getClassLoader()));
    }

    @Test
    public void templateMatchIsExactAndCaseSensitive() throws Exception {
        Method getter = Entity.class.getMethod("getEntityTemplate");
        Entity exact = new Entity("feedDetailReplySponsorCard");
        Entity nearMiss = new Entity("FeedDetailReplySponsorCard");

        assertTrue(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                exact, Entity.class, getter));
        assertFalse(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                nearMiss, Entity.class, getter));
        assertFalse(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                null, Entity.class, getter));
    }

    @Test
    public void viewMutationRequiresBothD2R1ModeAndExactTemplate() {
        assertFalse(D2ReplySponsorReplacement.shouldApplyViewMutation(false, true));
        assertFalse(D2ReplySponsorReplacement.shouldApplyViewMutation(true, false));
        assertTrue(D2ReplySponsorReplacement.shouldApplyViewMutation(true, true));
    }

    private static final class WrongOwner {
        public void \u0788(Object value) {
        }
    }
}
