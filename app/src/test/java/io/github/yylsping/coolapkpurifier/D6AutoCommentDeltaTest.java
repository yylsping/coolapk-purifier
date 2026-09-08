package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;

import org.junit.Test;

public final class D6AutoCommentDeltaTest {
    private static final int STATIC_METHOD = Modifier.PUBLIC | Modifier.STATIC;

    @Test
    public void acceptsOnlyPinnedHostVersion() {
        assertTrue(D6AutoCommentDelta.isExactHostVersion(2_608_212L));
        assertFalse(D6AutoCommentDelta.isExactHostVersion(2_608_211L));
    }

    @Test
    public void acceptsOnlyExactStaticVoidCallbackContract() {
        String owner = "com.coolapk.market.view.cardlist.component."
                + "RecyclerViewItemFullVisibleControllerKt";
        String parameter = "com.coolapk.market.view.cardlist.EntityListFragment";
        assertTrue(D6AutoCommentDelta.isExactTargetContract(owner, "\u037f",
                Collections.singletonList(parameter), "void", STATIC_METHOD));

        assertFalse(D6AutoCommentDelta.isExactTargetContract(owner + "Wrong", "\u037f",
                Collections.singletonList(parameter), "void", STATIC_METHOD));
        assertFalse(D6AutoCommentDelta.isExactTargetContract(owner, "wrong",
                Collections.singletonList(parameter), "void", STATIC_METHOD));
        assertFalse(D6AutoCommentDelta.isExactTargetContract(owner, "\u037f",
                Collections.singletonList("java.lang.Object"), "void", STATIC_METHOD));
        assertFalse(D6AutoCommentDelta.isExactTargetContract(owner, "\u037f",
                Collections.singletonList(parameter), "java.lang.Object", STATIC_METHOD));
        assertFalse(D6AutoCommentDelta.isExactTargetContract(owner, "\u037f",
                Collections.singletonList(parameter), "void", Modifier.PUBLIC));
    }

    @Test
    public void reflectiveValidationRejectsWrongOwnerAndParameter() throws Exception {
        Method wrong = WrongOwner.class.getDeclaredMethod("\u037f", Object.class);

        assertFalse(D6AutoCommentDelta.isExactTarget(wrong));
    }

    @Test
    public void suppressionReturnsNullWithoutNeedingAHostResult() {
        assertNull(D6AutoCommentDelta.suppressedResult());
        assertEquals("Lcom/coolapk/market/view/cardlist/component/"
                        + "RecyclerViewItemFullVisibleControllerKt;->\u037f("
                        + "Lcom/coolapk/market/view/cardlist/EntityListFragment;)V",
                D6AutoCommentDelta.TARGET_DESCRIPTOR);
    }

    private static final class WrongOwner {
        public static void \u037f(Object fragment) {
        }
    }
}
