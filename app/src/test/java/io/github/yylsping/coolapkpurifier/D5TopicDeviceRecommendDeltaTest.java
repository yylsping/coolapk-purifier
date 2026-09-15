package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class D5TopicDeviceRecommendDeltaTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptor() {
        TopicDeviceTargetSpec spec = TestManifests.profile().topicDeviceRecommend;
        assertEquals("Ld14;->ޓ(Lcom/coolapk/market/model/Feed;Ld14;"
                        + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;",
                spec.descriptor());
    }

    @Test
    public void reflectiveValidationRejectsAContractWithTheWrongOwnerAndShape()
            throws Exception {
        TopicDeviceTargetSpec spec = TestManifests.profile().topicDeviceRecommend;
        Method wrong = WrongOwner.class.getDeclaredMethod("ޓ",
                Object.class, Object.class, Object.class, int.class);

        assertFalse(ExactMethodVerifier.isExactTarget(spec, wrong));
    }

    @Test
    public void driftedSpecNeverVerifiesAgainstTheRealShape() throws Exception {
        TopicDeviceTargetSpec spec = TestManifests.profile().topicDeviceRecommend;
        Method exact = ExactShape.class.getDeclaredMethod("ޓ",
                Object.class, Object.class, Object.class, int.class);
        // Owner drift: the real method shape matches everything except the
        // manifest owner class, and must be rejected.
        assertFalse(ExactMethodVerifier.isExactTarget(spec, exact));

        org.json.JSONObject drifted = new org.json.JSONObject()
                .put("ownerClass", getClass().getName() + "$ExactShape")
                .put("methodName", "ޓ")
                .put("returnType", "kotlin.Unit")
                .put("parameterTypes", new org.json.JSONArray()
                        .put("java.lang.Object").put("java.lang.Object")
                        .put("java.lang.Object").put("int"));
        TopicDeviceTargetSpec parsed = TopicDeviceTargetSpec.parse(drifted);
        // Return-type drift against the actual void method must fail too.
        assertFalse(ExactMethodVerifier.isExactTarget(parsed, exact));
    }

    @Test
    public void suppressionReturnsNullWithoutNeedingAHostResult() {
        assertNull(D5TopicDeviceRecommendDelta.suppressedResult());
    }

    private static final class WrongOwner {
        public static Object ޓ(Object feed, Object owner, Object composer, int flags) {
            return null;
        }
    }

    private static final class ExactShape {
        public static void ޓ(Object feed, Object owner, Object composer, int flags) {
        }
    }
}
