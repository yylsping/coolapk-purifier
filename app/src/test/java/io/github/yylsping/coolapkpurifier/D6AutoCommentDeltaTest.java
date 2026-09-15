package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;

import org.junit.Test;

public final class D6AutoCommentDeltaTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptor() {
        AutoCommentTargetSpec spec = TestManifests.profile().autoComment;
        assertEquals("Lcom/coolapk/market/view/cardlist/component/"
                        + "RecyclerViewItemFullVisibleControllerKt;->Ϳ("
                        + "Lcom/coolapk/market/view/cardlist/EntityListFragment;)V",
                spec.descriptor());
    }

    @Test
    public void reflectiveValidationRejectsWrongOwnerAndParameter() throws Exception {
        AutoCommentTargetSpec spec = TestManifests.profile().autoComment;
        Method wrong = WrongOwner.class.getDeclaredMethod("Ϳ", Object.class);

        assertFalse(ExactMethodVerifier.isExactTarget(spec, wrong));
    }

    @Test
    public void suppressionReturnsNullWithoutNeedingAHostResult() {
        assertNull(D6AutoCommentDelta.suppressedResult());
    }

    private static final class WrongOwner {
        public static void Ϳ(Object fragment) {
        }
    }
}
