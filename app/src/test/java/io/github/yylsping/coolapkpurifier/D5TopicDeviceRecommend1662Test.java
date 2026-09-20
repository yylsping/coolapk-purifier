package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;

import com.coolapk.market.model.Feed;
import com.coolapk.market.model.FeedTarget;
import com.coolapk.market.view.feed.reply.FeedDetailV13ViewModel;

import java.lang.reflect.Method;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 16.6.2 (vb4-shaped) D5 topic/device recommend verifier regression: the
 * production ExactMethodVerifier must accept the vb4 contract and reject
 * near-miss shapes that each fail exactly one structural check.
 */
public final class D5TopicDeviceRecommend1662Test {
    private static TopicDeviceTargetSpec productionSpec() {
        TargetProfile profile =
                TestManifests.manifest().validatedProfileFor(TestManifests.COOLAPK_16_6_2);
        if (profile == null) {
            throw new AssertionError("bundled manifest must validate 16.6.2");
        }
        return profile.topicDeviceRecommend;
    }

    private static TopicDeviceTargetSpec specForMethod(String methodName) throws Exception {
        JSONObject json = new JSONObject()
                .put("ownerClass", "vb4")
                .put("methodName", methodName)
                .put("returnType", "kotlin.Unit")
                .put("parameterTypes", new org.json.JSONArray()
                        .put("com.coolapk.market.model.Feed")
                        .put("vb4")
                        .put("androidx.compose.runtime.Composer")
                        .put("int"));
        return TopicDeviceTargetSpec.parse(json);
    }

    @Test
    public void acceptsVb4ShapedProductionContract() throws Exception {
        TopicDeviceTargetSpec spec = productionSpec();
        Class<?> owner = Class.forName("vb4");
        Method exact = owner.getDeclaredMethod("ޓ",
                Feed.class, owner, Composer.class, int.class);
        assertTrue(ExactMethodVerifier.isExactTarget(spec, exact));
    }

    @Test
    public void rejectsOwnerMismatch() throws Exception {
        Method wrong = WrongOwner1662.class.getDeclaredMethod("ޓ",
                Feed.class, WrongOwner1662.class, Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(productionSpec(), wrong));
    }

    @Test
    public void rejectsSecondParameterOwnerSelfMismatch() throws Exception {
        Class<?> owner = Class.forName("vb4");
        Method bad = owner.getDeclaredMethod("ޓselfmismatch",
                Feed.class, Object.class, Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(specForMethod("ޓselfmismatch"), bad));
    }

    @Test
    public void rejectsReturnTypeMismatch() throws Exception {
        Class<?> owner = Class.forName("vb4");
        Method bad = owner.getDeclaredMethod("ޓretmismatch",
                Feed.class, owner, Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(specForMethod("ޓretmismatch"), bad));
    }

    @Test
    public void rejectsStaticModifierMismatch() throws Exception {
        Class<?> owner = Class.forName("vb4");
        Method bad = owner.getDeclaredMethod("ޓinst",
                Feed.class, owner, Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(specForMethod("ޓinst"), bad));
    }

    private static TopicDeviceUiTargetSpec productionUiSpec() {
        TargetProfile profile =
                TestManifests.manifest().validatedProfileFor(TestManifests.COOLAPK_16_6_2);
        if (profile == null) {
            throw new AssertionError("bundled manifest must validate 16.6.2");
        }
        return profile.topicDeviceRecommendUi;
    }

    @Test
    public void acceptsCs4ShapedProductionUiContract() throws Exception {
        TopicDeviceUiTargetSpec spec = productionUiSpec();
        Class<?> owner = Class.forName("cs4");
        Method exact = owner.getDeclaredMethod("ԭ",
                Modifier.class, FeedTarget.class, FeedDetailV13ViewModel.class,
                Composer.class, int.class);
        assertTrue(ExactMethodVerifier.isExactTarget(spec, exact));
    }

    @Test
    public void rejectsUiReturnTypeMismatch() throws Exception {
        Class<?> owner = Class.forName("cs4");
        Method bad = owner.getDeclaredMethod("ԭretmismatch",
                Modifier.class, FeedTarget.class, FeedDetailV13ViewModel.class,
                Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(productionUiSpec(), bad));
    }

    @Test
    public void rejectsUiStaticModifierMismatch() throws Exception {
        Class<?> owner = Class.forName("cs4");
        Method bad = owner.getDeclaredMethod("ԭinst",
                Modifier.class, FeedTarget.class, FeedDetailV13ViewModel.class,
                Composer.class, int.class);
        assertFalse(ExactMethodVerifier.isExactTarget(productionUiSpec(), bad));
    }

    private static final class WrongOwner1662 {
        public static kotlin.Unit ޓ(Feed feed, WrongOwner1662 self, Composer composer,
                                    int flags) {
            return kotlin.Unit.INSTANCE;
        }
    }
}
