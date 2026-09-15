package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Manifest §8.4: every contract drift must end with no hook installed and a
 * structured, assertable result — never a best-effort hook.
 */
public final class ManifestFailClosedTest {
    private static AutoCommentTargetSpec spec(String ownerClass, String methodName,
                                              String returnType, String... parameters)
            throws Exception {
        JSONArray parameterTypes = new JSONArray();
        for (String parameter : parameters) {
            parameterTypes.put(parameter);
        }
        return AutoCommentTargetSpec.parse(new JSONObject()
                .put("ownerClass", ownerClass)
                .put("methodName", methodName)
                .put("returnType", returnType)
                .put("parameterTypes", parameterTypes));
    }

    @Test
    public void verifierRejectsNullInputs() throws Exception {
        Method method = Shapes.class.getDeclaredMethod("exact", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(null, method));
        assertFalse(ExactMethodVerifier.isExactTarget(
                TestManifests.profile().autoComment, null));
    }

    @Test
    public void parameterDriftRejected() throws Exception {
        AutoCommentTargetSpec spec = spec(Shapes.class.getName(), "exact",
                "void", "java.lang.Object", "java.lang.Object");
        Method twoParameters = Shapes.class.getDeclaredMethod("exact",
                Object.class, Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(
                TestManifests.profile().autoComment, twoParameters));
        // A spec drifted to two parameters also fails against the one-parameter method.
        Method oneParameter = Shapes.class.getDeclaredMethod("exact", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, oneParameter));
    }

    @Test
    public void returnTypeDriftRejected() throws Exception {
        AutoCommentTargetSpec spec = spec(Shapes.class.getName(), "returns",
                "kotlin.Unit", "java.lang.Object");
        Method method = Shapes.class.getDeclaredMethod("returns", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, method));
    }

    @Test
    public void nonStaticModifierDriftRejected() throws Exception {
        AutoCommentTargetSpec spec = spec(Shapes.class.getName(), "instance",
                "void", "java.lang.Object");
        Method method = Shapes.class.getDeclaredMethod("instance", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, method));
    }

    @Test
    public void methodNameDriftRejected() throws Exception {
        AutoCommentTargetSpec spec = spec(Shapes.class.getName(), "renamed",
                "void", "java.lang.Object");
        Method method = Shapes.class.getDeclaredMethod("exact", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, method));
    }

    @Test
    public void ownerClassDriftRejected() throws Exception {
        AutoCommentTargetSpec spec = spec(Shapes.class.getName(), "exact",
                "void", "java.lang.Object");
        Method otherOwner = OtherOwner.class.getDeclaredMethod("exact", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, otherOwner));
    }

    @Test
    public void missingTargetSpecYieldsTargetMissing() {
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(null, getClass().getClassLoader()));
    }

    @Test
    public void missingClassYieldsTargetMissing() {
        // The bundled 16.6.1 owner class does not exist on the bootstrap
        // class loader, so the lookup must fail closed.
        AutoCommentTargetSpec bundled = TestManifests.profile().autoComment;
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(bundled, new ClassLoader(null) {
                }));
    }

    @Test
    public void contractMismatchYieldsContractMismatch() {
        try {
            AutoCommentTargetSpec drifted = spec(Shapes.class.getName(), "returns",
                    "kotlin.Unit", "java.lang.Object");
            D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
            assertEquals(InstallResult.CONTRACT_MISMATCH,
                    delta.install(drifted, getClass().getClassLoader()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unused")
    private static final class Shapes {
        public static void exact(Object feed) {
        }

        public static void exact(Object feed, Object extra) {
        }

        public static Object returns(Object feed) {
            return null;
        }

        public void instance(Object feed) {
        }
    }

    @SuppressWarnings("unused")
    private static final class OtherOwner {
        public static void exact(Object feed) {
        }
    }
}
