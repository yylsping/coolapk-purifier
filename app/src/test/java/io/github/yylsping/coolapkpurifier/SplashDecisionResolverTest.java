package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import org.junit.Test;

import java.lang.reflect.Method;

public final class SplashDecisionResolverTest {
    public static final class AdSource implements Parcelable {
        public AdSource(String a, String b, String c) {
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
        }
    }

    public static final class Decisions {
        public static final boolean exact(Context context, AdSource source, String tag) {
            return true;
        }

        public static boolean nonFinal(Context context, AdSource source, String tag) {
            return true;
        }

        public final boolean instance(Context context, AdSource source, String tag) {
            return true;
        }

        public static final Boolean boxed(Context context, AdSource source, String tag) {
            return true;
        }

        public static final boolean generic(Context context, Object source, String tag) {
            return true;
        }

        public static final native boolean nativeMethod(
                Context context, AdSource source, String tag);
    }

    @Test
    public void strictShapeAcceptsOnlyPrimitiveStaticFinalBusinessContract()
            throws Exception {
        assertTrue(SplashDecisionResolver.decisionShape(method("exact")));
        assertFalse(SplashDecisionResolver.decisionShape(method("nonFinal")));
        assertFalse(SplashDecisionResolver.decisionShape(method("instance")));
        assertFalse(SplashDecisionResolver.decisionShape(method("boxed")));
        assertFalse(SplashDecisionResolver.decisionShape(method("nativeMethod")));
        assertFalse(SplashDecisionResolver.decisionShape(Decisions.class.getDeclaredMethod(
                "generic", Context.class, Object.class, String.class)));
    }

    @Test
    public void cachedTargetRequiresExactSemanticSourceAndDescriptors() throws Exception {
        String descriptor = org.luckypray.dexkit.util.DexSignUtil.getDescriptor(method("exact"));
        String owner = DescriptorUtils.classDescriptorOf(Decisions.class);
        ClassLoader loader = getClass().getClassLoader();
        ResolvedTarget exact = new ResolvedTarget(
                TargetResolver.KEY_SPLASH_DECISION,
                SplashDecisionResolver.SOURCE,
                owner,
                descriptor);

        assertTrue(SplashDecisionResolver.verify(exact, loader));
        assertNull(TargetVerifier.verify(exact, loader));
        assertFalse(SplashDecisionResolver.verify(new ResolvedTarget(
                exact.key, "legacy_name", owner, descriptor), loader));
        assertNotNull(TargetVerifier.verify(new ResolvedTarget(
                exact.key, exact.source, DescriptorUtils.classDescriptorOf(AdSource.class),
                descriptor), loader));
        assertNotNull(TargetVerifier.verify(new ResolvedTarget(
                exact.key, exact.source, owner, ""), loader));
    }

    @Test
    public void absentOrUncertainFragmentCapabilityIsConservative() {
        assertFalse(SplashDecisionResolver.hasEmbeddedHost(getClass().getClassLoader()));
        ClassLoader broken = new ClassLoader() {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new NoClassDefFoundError("staged dependency");
            }
        };
        assertTrue(SplashDecisionResolver.hasEmbeddedHost(broken));
        assertNull(SplashDecisionResolver.resolve(
                null, getClass().getClassLoader(), new ModuleLog(null)));
    }

    private Method method(String name) throws Exception {
        return Decisions.class.getDeclaredMethod(
                name, Context.class, AdSource.class, String.class);
    }
}
