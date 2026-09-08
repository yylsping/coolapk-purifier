package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.Test;

public final class D5TopicDeviceRecommendDeltaTest {
    private static final int STATIC_METHOD = Modifier.PUBLIC | Modifier.STATIC;

    @Test
    public void acceptsOnlyPinnedHostVersion() {
        assertTrue(D5TopicDeviceRecommendDelta.isExactHostVersion(2_608_212L));
        assertFalse(D5TopicDeviceRecommendDelta.isExactHostVersion(2_608_211L));
    }

    @Test
    public void acceptsOnlyExactStaticAssemblerContract() {
        assertTrue(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d14", "\u0793",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "int"),
                "kotlin.Unit", STATIC_METHOD));

        assertFalse(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d15", "\u0793",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "int"),
                "kotlin.Unit", STATIC_METHOD));
        assertFalse(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d14", "wrong",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "int"),
                "kotlin.Unit", STATIC_METHOD));
        assertFalse(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d14", "\u0793",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "long"),
                "kotlin.Unit", STATIC_METHOD));
        assertFalse(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d14", "\u0793",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "int"),
                "java.lang.Object", STATIC_METHOD));
        assertFalse(D5TopicDeviceRecommendDelta.isExactTargetContract(
                "d14", "\u0793",
                Arrays.asList("com.coolapk.market.model.Feed", "d14",
                        "androidx.compose.runtime.Composer", "int"),
                "kotlin.Unit", Modifier.PUBLIC));
    }

    @Test
    public void reflectiveValidationRejectsAContractWithTheWrongOwnerAndShape()
            throws Exception {
        Method wrong = WrongOwner.class.getDeclaredMethod("\u0793",
                Object.class, Object.class, Object.class, int.class);

        assertFalse(D5TopicDeviceRecommendDelta.isExactTarget(wrong));
    }

    @Test
    public void suppressionReturnsNullWithoutNeedingAHostResult() {
        assertNull(D5TopicDeviceRecommendDelta.suppressedResult());
        assertEquals("Ld14;->\u0793(Lcom/coolapk/market/model/Feed;Ld14;"
                        + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;",
                D5TopicDeviceRecommendDelta.TARGET_DESCRIPTOR);
    }

    private static final class WrongOwner {
        public static Object \u0793(Object feed, Object owner, Object composer, int flags) {
            return null;
        }
    }
}
