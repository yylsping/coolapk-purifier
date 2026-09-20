package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import io.github.libxposed.api.XposedInterface.Chain;

/**
 * D5 conservative contract: the compose assembler is observe-only (the
 * original ALWAYS runs, so entered/executed counts are equal and nothing
 * like original_skipped can exist), and the single suppression point is the
 * terminal FeedNewTargetRowUI composable, which answers without proceeding
 * only while the feature is enabled.
 */
public final class D5TopicDeviceRecommendDeltaTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptor() {
        TopicDeviceTargetSpec spec = TestManifests.profile().topicDeviceRecommend;
        assertEquals("Ld14;->ޓ(Lcom/coolapk/market/model/Feed;Ld14;"
                        + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;",
                spec.descriptor());
    }

    @Test
    public void uiSpecMatchesBaselineDescriptor() {
        TopicDeviceUiTargetSpec spec = TestManifests.profile().topicDeviceRecommendUi;
        assertEquals("Lqh4;->ԯ(Landroidx/compose/ui/Modifier;"
                        + "Lcom/coolapk/market/model/FeedTarget;"
                        + "Lcom/coolapk/market/view/feed/reply/FeedDetailV13ViewModel;"
                        + "Landroidx/compose/runtime/Composer;I)V",
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

        JSONObject drifted = new JSONObject()
                .put("ownerClass", getClass().getName() + "$ExactShape")
                .put("methodName", "ޓ")
                .put("returnType", "kotlin.Unit")
                .put("parameterTypes", new JSONArray()
                        .put("java.lang.Object").put("java.lang.Object")
                        .put("java.lang.Object").put("int"));
        TopicDeviceTargetSpec parsed = TopicDeviceTargetSpec.parse(drifted);
        // Return-type drift against the actual void method must fail too.
        assertFalse(ExactMethodVerifier.isExactTarget(parsed, exact));
    }

    @Test
    public void assemblerAlwaysProceedsAndCountsStayEqual() throws Throwable {
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        Object original = new Object();
        FakeChain chain = new FakeChain(original);

        assertSame(original, delta.onAssemblerEnter(chain, "test-assembler"));
        assertSame(original, delta.onAssemblerEnter(chain, "test-assembler"));

        assertEquals(2, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d5_target_entered_count=2"));
        assertTrue(summary.contains("d5_original_executed_count=2"));
        assertFalse(summary.contains("original_skipped"));
    }

    @Test
    public void targetUiEnabledSuppressesWithoutProceeding() throws Throwable {
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(new Object());

        Object result = delta.onTargetUi(chain, true, "test-ui");

        assertEquals(0, chain.proceedCalls.get());
        assertNull(result);
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d5_target_ui_observed_count=1"));
        assertTrue(summary.contains("d5_target_ui_suppressed_count=1"));
    }

    @Test
    public void targetUiDisabledProceedsUnchanged() throws Throwable {
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        Object original = new Object();
        FakeChain chain = new FakeChain(original);

        assertSame(original, delta.onTargetUi(chain, false, "test-ui"));

        assertEquals(1, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d5_target_ui_observed_count=1"));
        assertTrue(summary.contains("d5_target_ui_suppressed_count=0"));
    }

    @Test
    public void suppressedResultIsNullForTheVoidComposable() {
        assertNull(D5TopicDeviceRecommendDelta.suppressedResult());
    }

    @Test
    public void missingTargetSpecsYieldTargetMissingWithoutAnyHook() {
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(null, null, getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingUiSpecYieldsTargetMissingWithoutAnyHook() throws Exception {
        // The assembler layer alone is never enough: ui missing fails the
        // whole pair closed and installs nothing.
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(resolvableAssemblerSpec(), null, getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingClassYieldsTargetMissing() {
        // The bundled 16.6.1 owner classes do not exist on the bootstrap
        // class loader, so the lookup must fail closed.
        TargetProfile bundled = TestManifests.profile();
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(bundled.topicDeviceRecommend, bundled.topicDeviceRecommendUi,
                        new ClassLoader(null) {
                        }));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void assemblerContractMismatchYieldsContractMismatch() throws Exception {
        TopicDeviceTargetSpec drifted = assemblerSpec(Shapes.class.getName(),
                "returns", "kotlin.Unit", "java.lang.Object");
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.CONTRACT_MISMATCH,
                delta.install(drifted, resolvableUiSpec(), getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void uiContractMismatchYieldsContractMismatchWithoutAnyHook() throws Exception {
        // Assembler layer valid; the ui layer drifted on the return type.
        // The pair must fail closed before any hook exists.
        TopicDeviceUiTargetSpec driftedUi = uiSpec(Shapes.class.getName(),
                "returns", "kotlin.Unit", "java.lang.Object");
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.CONTRACT_MISMATCH,
                delta.install(resolvableAssemblerSpec(), driftedUi,
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void installationFailureLeavesNoResidualHook() throws Exception {
        // module == null: the first module.hook call throws mid-install. The
        // rollback must leave zero handles behind (both-or-nothing).
        D5TopicDeviceRecommendDelta delta =
                new D5TopicDeviceRecommendDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.INSTALL_FAILED,
                delta.install(resolvableAssemblerSpec(), resolvableUiSpec(),
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    private static TopicDeviceTargetSpec resolvableAssemblerSpec() throws Exception {
        return assemblerSpec(Shapes.class.getName(), "exact", "void", "java.lang.Object");
    }

    private static TopicDeviceUiTargetSpec resolvableUiSpec() throws Exception {
        return uiSpec(Shapes.class.getName(), "exact", "void", "java.lang.Object");
    }

    private static TopicDeviceTargetSpec assemblerSpec(String ownerClass, String methodName,
                                                       String returnType, String... parameters)
            throws Exception {
        return TopicDeviceTargetSpec.parse(specJson(ownerClass, methodName,
                returnType, parameters));
    }

    private static TopicDeviceUiTargetSpec uiSpec(String ownerClass, String methodName,
                                                  String returnType, String... parameters)
            throws Exception {
        return TopicDeviceUiTargetSpec.parse(specJson(ownerClass, methodName,
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

    private static final class FakeChain implements Chain {
        final AtomicInteger proceedCalls = new AtomicInteger();
        private final Object result;

        FakeChain(Object result) {
            this.result = result;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List<Object> getArgs() {
            return Collections.emptyList();
        }

        @Override
        public Object getArg(int index) {
            throw new IndexOutOfBoundsException();
        }

        @Override
        public Object proceed() {
            proceedCalls.incrementAndGet();
            return result;
        }

        @Override
        public Object proceed(Object... args) {
            proceedCalls.incrementAndGet();
            return result;
        }

        @Override
        public Object proceedWith(Object thisObject) {
            proceedCalls.incrementAndGet();
            return result;
        }

        @Override
        public Object proceedWith(Object thisObject, Object... args) {
            proceedCalls.incrementAndGet();
            return result;
        }
    }

    @SuppressWarnings("unused")
    private static final class Shapes {
        public static void exact(Object feed) {
        }

        public static Object returns(Object feed) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final class WrongOwner {
        public static Object ޓ(Object feed, Object owner, Object composer, int flags) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final class ExactShape {
        public static void ޓ(Object feed, Object owner, Object composer, int flags) {
        }
    }
}
