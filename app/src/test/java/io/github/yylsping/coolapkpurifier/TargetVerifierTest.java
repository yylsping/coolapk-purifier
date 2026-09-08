package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;

import java.util.List;

import org.junit.Test;

/** Prefix-aware verification for indexed feed#N / splash_base#N targets. */
public final class TargetVerifierTest {
    private static final String HELPER_D =
            "L" + Helper.class.getName().replace('.', '/') + ";";
    private static final String SPLASH_D =
            "L" + TestSplashActivity.class.getName().replace('.', '/') + ";";
    private static final String FEED_METHOD_D =
            HELPER_D + "->transform(Ljava/util/List;Z)Ljava/util/List;";
    private static final String ON_CREATE_D =
            SPLASH_D + "->onCreate(Landroid/os/Bundle;)V";

    @Test
    public void indexedFeedKeyVerifiesLikeBaseKey() {
        ResolvedTarget target = new ResolvedTarget(
                "feed#2", "fingerprint_strong", HELPER_D, FEED_METHOD_D);

        assertNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void feedShapeMismatchIsRejected() {
        ResolvedTarget target = new ResolvedTarget(
                "feed", "fingerprint_strong", HELPER_D,
                HELPER_D + "->notFeed(Ljava/util/List;Z)Ljava/util/List;");

        assertNotNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void indexedSplashKeyWithEmptyMethodDescriptorVerifies() {
        ResolvedTarget target = new ResolvedTarget(
                "splash_base#2", "fingerprint_strong", SPLASH_D, "");

        assertNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void nonSplashKeyWithEmptyMethodDescriptorIsRejected() {
        ResolvedTarget target = new ResolvedTarget(
                "getter.title", "fingerprint_strong", SPLASH_D, "");

        assertNotNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void splashOnCreateShapeIsAccepted() {
        ResolvedTarget target = new ResolvedTarget(
                "splash_base", "fingerprint_strong", SPLASH_D, ON_CREATE_D);

        assertNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void getterKeysStillVerify() {
        String entityD = "L" + EntityAccessorsTest.Entity.class.getName()
                .replace('.', '/') + ";";
        ResolvedTarget target = new ResolvedTarget(
                "getter.entityTemplate", "fingerprint_strong", entityD,
                entityD + "->getEntityTemplate()Ljava/lang/String;");

        assertNull(TargetVerifier.verify(target, getClass().getClassLoader()));
    }

    @Test
    public void feedShapeMatchesTheLegacySignature() throws Exception {
        assertTrue(TargetVerifier.isFeedShape(
                Helper.class.getMethod("transform", List.class, boolean.class)));
        assertEquals(false, TargetVerifier.isFeedShape(
                Helper.class.getMethod("notFeed", List.class, boolean.class)));
    }

    public static final class Helper {
        public List<Object> transform(List<Object> source, boolean flag) {
            return source;
        }

        public java.util.Collection<Object> notFeed(List<Object> source, boolean flag) {
            return source;
        }
    }

    public static final class TestSplashActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }
    }
}
