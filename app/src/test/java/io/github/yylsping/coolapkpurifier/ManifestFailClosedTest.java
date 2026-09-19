package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Manifest §8.4: every contract drift must end with no hook installed and a
 * structured, assertable result — never a best-effort hook. D6 is a
 * both-or-nothing pair: any single-layer failure leaves zero hooks behind.
 */
public final class ManifestFailClosedTest {
    private static AutoCommentTargetSpec spec(String ownerClass, String methodName,
                                              String returnType, String... parameters)
            throws Exception {
        return AutoCommentTargetSpec.parse(specJson(ownerClass, methodName,
                returnType, parameters));
    }

    private static AutoCommentPromptTargetSpec promptSpec(String ownerClass,
                                                          String methodName,
                                                          String returnType,
                                                          String... parameters)
            throws Exception {
        return AutoCommentPromptTargetSpec.parse(specJson(ownerClass, methodName,
                returnType, parameters));
    }

    private static JSONObject specJson(String ownerClass, String methodName,
                                       String returnType, String... parameters)
            throws Exception {
        JSONArray parameterTypes = new JSONArray();
        for (String parameter : parameters) {
            parameterTypes.put(parameter);
        }
        return new JSONObject()
                .put("ownerClass", ownerClass)
                .put("methodName", methodName)
                .put("returnType", returnType)
                .put("parameterTypes", parameterTypes);
    }

    /** A controller-shaped spec that actually resolves and verifies on the JVM. */
    private static AutoCommentTargetSpec resolvableControllerSpec() throws Exception {
        return spec(Shapes.class.getName(), "exact", "void", "java.lang.Object");
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
                delta.install(null, null, getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingPromptSpecYieldsTargetMissingWithoutAnyHook() throws Exception {
        // The controller layer alone is never enough: prompt missing fails the
        // whole pair closed and installs nothing.
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(resolvableControllerSpec(), null,
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingClassYieldsTargetMissing() {
        // The bundled 16.6.1 owner classes do not exist on the bootstrap
        // class loader, so the lookup must fail closed.
        TargetProfile bundled = TestManifests.profile();
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(bundled.autoComment, bundled.autoCommentPrompt,
                        new ClassLoader(null) {
                        }));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void controllerContractMismatchYieldsContractMismatch() {
        try {
            AutoCommentTargetSpec drifted = spec(Shapes.class.getName(), "returns",
                    "kotlin.Unit", "java.lang.Object");
            D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
            assertEquals(InstallResult.CONTRACT_MISMATCH,
                    delta.install(drifted, TestManifests.profile().autoCommentPrompt,
                            getClass().getClassLoader()));
            assertFalse(delta.anyHookInstalled());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void promptContractMismatchYieldsContractMismatchWithoutAnyHook() {
        try {
            // Controller layer valid; the prompt layer drifted on the return
            // type. The pair must fail closed before any hook exists.
            AutoCommentPromptTargetSpec driftedPrompt = promptSpec(
                    Shapes.class.getName(), "returns", "kotlin.Unit", "java.lang.Object");
            D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
            assertEquals(InstallResult.CONTRACT_MISMATCH,
                    delta.install(resolvableControllerSpec(), driftedPrompt,
                            getClass().getClassLoader()));
            assertFalse(delta.anyHookInstalled());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void installationFailureLeavesNoResidualHook() throws Exception {
        // module == null: the first module.hook call throws mid-install. The
        // rollback must leave zero handles behind (both-or-nothing).
        AutoCommentPromptTargetSpec prompt = promptSpec(Shapes.class.getName(),
                "returns", "java.lang.Object", "java.lang.Object");
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.INSTALL_FAILED,
                delta.install(resolvableControllerSpec(), prompt,
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
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
