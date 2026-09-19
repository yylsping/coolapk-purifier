package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.github.libxposed.api.XposedInterface.Chain;

/**
 * D6 conservative contract: the controller callback is observe-only (the
 * original ALWAYS runs, so entered/executed counts are equal and nothing like
 * original_skipped can exist), and the single suppression point is the
 * terminal prompt-UI lambda, which answers Unit.INSTANCE without proceeding
 * only while the feature is enabled.
 */
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
    public void promptSpecMatchesBaselineDescriptor() {
        AutoCommentPromptTargetSpec spec = TestManifests.profile().autoCommentPrompt;
        assertEquals("Lcom/coolapk/market/view/cardlist/component/"
                        + "RecyclerViewItemFullVisibleControllerKt"
                        + "$addAutoShowFeedCommentView$1;->Ԫ("
                        + "Lcom/coolapk/market/view/cardlist/EntityListFragment;"
                        + "Lkotlin/ranges/IntRange;)Lkotlin/Unit;",
                spec.descriptor());
    }

    @Test
    public void reflectiveValidationRejectsWrongOwnerAndParameter() throws Exception {
        AutoCommentTargetSpec spec = TestManifests.profile().autoComment;
        Method wrong = WrongOwner.class.getDeclaredMethod("Ϳ", Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(spec, wrong));

        AutoCommentPromptTargetSpec prompt = TestManifests.profile().autoCommentPrompt;
        Method wrongPrompt = WrongOwner.class.getDeclaredMethod("Ԫ", Object.class, Object.class);
        assertFalse(ExactMethodVerifier.isExactTarget(prompt, wrongPrompt));
    }

    @Test
    public void controllerAlwaysProceedsAndCountsStayEqual() throws Throwable {
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        Object original = new Object();
        FakeChain chain = new FakeChain(original);

        assertSame(original, delta.onControllerEnter(chain, "test-controller"));
        assertSame(original, delta.onControllerEnter(chain, "test-controller"));

        assertEquals(2, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d6_controller_entered_count=2"));
        assertTrue(summary.contains("d6_original_executed_count=2"));
        assertFalse(summary.contains("original_skipped"));
    }

    @Test
    public void promptEnabledSuppressesWithoutProceeding() throws Throwable {
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(new Object());

        Object result = delta.onPromptAction(chain, true, "test-prompt");

        assertEquals(0, chain.proceedCalls.get());
        assertSame(kotlin.Unit.INSTANCE, result);
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d6_prompt_ui_observed_count=1"));
        assertTrue(summary.contains("d6_prompt_ui_suppressed_count=1"));
    }

    @Test
    public void promptDisabledProceedsUnchanged() throws Throwable {
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        Object original = new Object();
        FakeChain chain = new FakeChain(original);

        assertSame(original, delta.onPromptAction(chain, false, "test-prompt"));

        assertEquals(1, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d6_prompt_ui_observed_count=1"));
        assertTrue(summary.contains("d6_prompt_ui_suppressed_count=0"));
    }

    @Test
    public void suppressedResultAnswersTheRealUnitInstance() {
        D6AutoCommentDelta delta = new D6AutoCommentDelta(null, new ModuleLog(null), null);
        assertSame(kotlin.Unit.INSTANCE, delta.promptSuppressedResult());
        assertSame(kotlin.Unit.INSTANCE,
                D6AutoCommentDelta.resolveUnitInstance(getClass().getClassLoader()));
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
    private static final class WrongOwner {
        public static void Ϳ(Object fragment) {
        }

        public static Object Ԫ(Object fragment, Object range) {
            return null;
        }
    }
}
