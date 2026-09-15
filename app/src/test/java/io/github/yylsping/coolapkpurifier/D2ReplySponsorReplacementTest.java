package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.coolapk.market.model.Entity;

import java.lang.reflect.Method;

import org.junit.Test;

public final class D2ReplySponsorReplacementTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptor() {
        ReplySponsorTargetSpec spec = TestManifests.profile().replySponsor;
        org.junit.Assert.assertEquals("Lfn4;->ވ(Ljava/lang/Object;)V",
                spec.descriptor());
        org.junit.Assert.assertEquals("މ", spec.layoutField);
        org.junit.Assert.assertEquals("feedDetailReplySponsorCard", spec.entityTemplate);
    }

    @Test
    public void acceptsExactPinnedBinderContract() throws Exception {
        ReplySponsorTargetSpec spec = TestManifests.profile().replySponsor;
        Class<?> owner = Class.forName("fn4");
        Method target = owner.getDeclaredMethod("ވ", Object.class);
        assertTrue(D2ReplySponsorReplacement.isExactTarget(
                spec, target, owner.getClassLoader()));
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                spec, WrongOwner.class.getDeclaredMethod("ވ", Object.class),
                owner.getClassLoader()));
    }

    @Test
    public void templateMatchIsExactAndCaseSensitive() throws Exception {
        ReplySponsorTargetSpec spec = TestManifests.profile().replySponsor;
        Method getter = Entity.class.getMethod("getEntityTemplate");
        Entity exact = new Entity("feedDetailReplySponsorCard");
        Entity nearMiss = new Entity("FeedDetailReplySponsorCard");

        assertTrue(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                spec, exact, Entity.class, getter));
        assertFalse(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                spec, nearMiss, Entity.class, getter));
        assertFalse(D2ReplySponsorReplacement.isExactReplySponsorEntity(
                spec, null, Entity.class, getter));
    }

    @Test
    public void viewMutationRequiresBothD2R1ModeAndExactTemplate() {
        assertFalse(D2ReplySponsorReplacement.shouldApplyViewMutation(false, true));
        assertFalse(D2ReplySponsorReplacement.shouldApplyViewMutation(true, false));
        assertTrue(D2ReplySponsorReplacement.shouldApplyViewMutation(true, true));
    }

    private static final class WrongOwner {
        public void ވ(Object value) {
        }
    }
}
