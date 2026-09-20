package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.coolapk.market.model.$$AutoValue_Feed;
import com.coolapk.market.model.Entity;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.Test;

import io.github.libxposed.api.XposedInterface.Chain;

/**
 * D1 conservative contract: the sponsor getter is observe-only (the original
 * ALWAYS runs and its value passes through untouched), and the single
 * suppression point is the exact SponsorSelfDrawDetailViewHolder binder,
 * which collapses the bound itemView only for the exact
 * sponsorForFeedDetail template while the feature is enabled.
 */
public final class D1DetailSponsorDeltaTest {
    @Test
    public void manifestGetterSpecMatchesBaselineDescriptor() {
        DetailSponsorTargetSpec spec = TestManifests.profile().detailSponsor;
        assertEquals("Lcom/coolapk/market/model/$$AutoValue_Feed;->"
                        + "getDetailSponsorCard()Lcom/coolapk/market/model/Entity;",
                spec.descriptor());
    }

    @Test
    public void manifestUiSpecMatchesBaselineDescriptor() {
        DetailSponsorUiTargetSpec spec = TestManifests.profile().detailSponsorUi;
        assertEquals("Lcom/coolapk/market/view/ad/ֈ;->ވ(Ljava/lang/Object;)V",
                spec.descriptor());
        assertEquals("ކ", spec.layoutField);
        assertEquals("sponsorForFeedDetail", spec.entityTemplate);
    }

    @Test
    public void acceptsExactConcreteGetter() throws Exception {
        DetailSponsorTargetSpec spec = TestManifests.profile().detailSponsor;
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getDetailSponsorCard");
        Method wrongOwner = WrongOwner.class.getDeclaredMethod("getDetailSponsorCard");

        assertTrue(D1DetailSponsorDelta.isExactGetterTarget(spec, exact));
        assertFalse(D1DetailSponsorDelta.isExactGetterTarget(spec, wrongOwner));
    }

    @Test
    public void tamperedGetterSpecNeverVerifies() throws Exception {
        JSONObject json = new JSONObject()
                .put("ownerClass", "com.coolapk.market.model.Entity")
                .put("methodName", "getDetailSponsorCard")
                .put("returnType", "com.coolapk.market.model.Entity");
        DetailSponsorTargetSpec tampered = DetailSponsorTargetSpec.parse(json);
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getDetailSponsorCard");
        assertFalse(D1DetailSponsorDelta.isExactGetterTarget(tampered, exact));
    }

    @Test
    public void acceptsExactPinnedBinderContract() throws Exception {
        DetailSponsorUiTargetSpec spec = TestManifests.profile().detailSponsorUi;
        Class<?> owner = Class.forName("com.coolapk.market.view.ad.ֈ");
        Method target = owner.getDeclaredMethod("ވ", Object.class);
        assertTrue(D1DetailSponsorDelta.isExactUiTarget(
                spec, target, owner.getClassLoader()));
        assertFalse(D1DetailSponsorDelta.isExactUiTarget(
                spec, WrongBinder.class.getDeclaredMethod("ވ", Object.class),
                owner.getClassLoader()));
    }

    @Test
    public void templateMatchIsExactAndCaseSensitive() throws Exception {
        DetailSponsorUiTargetSpec spec = TestManifests.profile().detailSponsorUi;
        Method getter = Entity.class.getMethod("getEntityTemplate");
        Entity exact = new Entity("sponsorForFeedDetail");
        Entity nearMiss = new Entity("SponsorForFeedDetail");
        Entity replySponsor = new Entity("feedDetailReplySponsorCard");

        assertTrue(D1DetailSponsorDelta.isExactDetailSponsorEntity(
                spec, exact, Entity.class, getter));
        assertFalse(D1DetailSponsorDelta.isExactDetailSponsorEntity(
                spec, nearMiss, Entity.class, getter));
        assertFalse(D1DetailSponsorDelta.isExactDetailSponsorEntity(
                spec, replySponsor, Entity.class, getter));
        assertFalse(D1DetailSponsorDelta.isExactDetailSponsorEntity(
                spec, null, Entity.class, getter));
    }

    @Test
    public void viewMutationRequiresBothEnabledAndExactTemplate() {
        assertFalse(D1DetailSponsorDelta.shouldApplyViewMutation(false, true));
        assertFalse(D1DetailSponsorDelta.shouldApplyViewMutation(true, false));
        assertTrue(D1DetailSponsorDelta.shouldApplyViewMutation(true, true));
    }

    @Test
    public void getterAlwaysProceedsAndPreservesTheOriginal() throws Throwable {
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        Entity original = new Entity("sponsorForFeedDetail");
        FakeChain chain = new FakeChain(null, original, null);
        DetailSponsorUiTargetSpec spec = uiSpec();
        Method templateGetter = Entity.class.getMethod("getEntityTemplate");

        // The gate is never consulted on the getter path: whatever the
        // feature state, the host object passes through untouched.
        assertSame(original, delta.onGetterEnter(chain, "test-getter",
                spec, Entity.class, templateGetter));
        assertSame(original, delta.onGetterEnter(chain, "test-getter",
                spec, Entity.class, templateGetter));

        assertEquals(2, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d1_host_object_observed_count=2"));
        assertTrue(summary.contains("d1_host_sponsor_template_count=2"));
        assertTrue(summary.contains("d1_original_return_preserved_count=2"));
    }

    @Test
    public void getterCountsNullReturnsAsPreservedButNotObserved() throws Throwable {
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(null, null, null);

        org.junit.Assert.assertNull(delta.onGetterEnter(chain, "test-getter",
                uiSpec(), Entity.class, Entity.class.getMethod("getEntityTemplate")));

        String summary = delta.summaryLine();
        assertTrue(summary.contains("d1_host_object_observed_count=0"));
        assertTrue(summary.contains("d1_host_sponsor_template_count=0"));
        assertTrue(summary.contains("d1_original_return_preserved_count=1"));
    }

    @Test
    public void exactSponsorBindIsCollapsedOnlyWhenEnabled() throws Throwable {
        DetailSponsorUiTargetSpec spec = uiSpec();
        Class<?> owner = Class.forName(spec.ownerClass);
        Object holder = owner.getDeclaredConstructor(
                android.view.View.class,
                androidx.databinding.DataBindingComponent.class,
                com.coolapk.market.view.ad.EntityAdHelper.class)
                .newInstance(new android.view.View(null), null, null);
        D1DetailSponsorDelta.HolderController controller =
                new D1DetailSponsorDelta.HolderController(owner.getField("itemView"));
        Method templateGetter = Entity.class.getMethod("getEntityTemplate");
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(holder, null, new Entity("sponsorForFeedDetail"));

        delta.onTargetUi(chain, true, spec, Entity.class, templateGetter,
                controller, "test-ui");

        assertEquals(1, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d1_target_ui_observed_count=1"));
        assertTrue(summary.contains("d1_target_ui_suppressed_count=1"));
    }

    @Test
    public void exactSponsorBindIsUntouchedWhenDisabled() throws Throwable {
        DetailSponsorUiTargetSpec spec = uiSpec();
        Class<?> owner = Class.forName(spec.ownerClass);
        Object holder = owner.getDeclaredConstructor(
                android.view.View.class,
                androidx.databinding.DataBindingComponent.class,
                com.coolapk.market.view.ad.EntityAdHelper.class)
                .newInstance(new android.view.View(null), null, null);
        D1DetailSponsorDelta.HolderController controller =
                new D1DetailSponsorDelta.HolderController(owner.getField("itemView"));
        Method templateGetter = Entity.class.getMethod("getEntityTemplate");
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(holder, null, new Entity("sponsorForFeedDetail"));

        delta.onTargetUi(chain, false, spec, Entity.class, templateGetter,
                controller, "test-ui");

        assertEquals(1, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d1_target_ui_observed_count=1"));
        assertTrue(summary.contains("d1_target_ui_suppressed_count=0"));
    }

    @Test
    public void ordinaryDetailCardIsNeverSuppressed() throws Throwable {
        DetailSponsorUiTargetSpec spec = uiSpec();
        Class<?> owner = Class.forName(spec.ownerClass);
        Object holder = owner.getDeclaredConstructor(
                android.view.View.class,
                androidx.databinding.DataBindingComponent.class,
                com.coolapk.market.view.ad.EntityAdHelper.class)
                .newInstance(new android.view.View(null), null, null);
        D1DetailSponsorDelta.HolderController controller =
                new D1DetailSponsorDelta.HolderController(owner.getField("itemView"));
        Method templateGetter = Entity.class.getMethod("getEntityTemplate");
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        FakeChain chain = new FakeChain(holder, null, new Entity("feedDetail"));

        delta.onTargetUi(chain, true, spec, Entity.class, templateGetter,
                controller, "test-ui");

        assertEquals(1, chain.proceedCalls.get());
        String summary = delta.summaryLine();
        assertTrue(summary.contains("d1_target_ui_observed_count=0"));
        assertTrue(summary.contains("d1_target_ui_suppressed_count=0"));
    }

    @Test
    public void missingTargetSpecsYieldTargetMissingWithoutAnyHook() {
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(null, null, getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingUiSpecYieldsTargetMissingWithoutAnyHook() throws Exception {
        // The getter layer alone is never enough: ui missing fails the whole
        // pair closed and installs nothing.
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(getterSpec(), null, getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void missingClassYieldsTargetMissing() {
        // The bundled owner classes do not exist on the bootstrap class
        // loader, so the lookup must fail closed.
        TargetProfile bundled = TestManifests.profile();
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.TARGET_MISSING,
                delta.install(bundled.detailSponsor, bundled.detailSponsorUi,
                        new ClassLoader(null) {
                        }));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void getterContractMismatchYieldsContractMismatch() throws Exception {
        // Return-type drift against the real getter must fail closed.
        JSONObject drifted = new JSONObject()
                .put("ownerClass", "com.coolapk.market.model.$$AutoValue_Feed")
                .put("methodName", "getDetailSponsorCard")
                .put("returnType", "java.lang.Object");
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.CONTRACT_MISMATCH,
                delta.install(DetailSponsorTargetSpec.parse(drifted), uiSpec(),
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void uiContractMismatchYieldsContractMismatchWithoutAnyHook() throws Exception {
        // Getter layer valid; the ui layer drifted onto an owner without the
        // structural contract. The pair must fail closed before any hook
        // exists.
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.CONTRACT_MISMATCH,
                delta.install(getterSpec(), uiSpec(WrongBinder.class.getName()),
                        getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    @Test
    public void installationFailureLeavesNoResidualHook() throws Exception {
        // module == null: the first module.hook call throws mid-install. The
        // rollback must leave zero handles behind (both-or-nothing).
        D1DetailSponsorDelta delta =
                new D1DetailSponsorDelta(null, new ModuleLog(null), null);
        assertEquals(InstallResult.INSTALL_FAILED,
                delta.install(getterSpec(), uiSpec(), getClass().getClassLoader()));
        assertFalse(delta.anyHookInstalled());
    }

    private static DetailSponsorTargetSpec getterSpec() throws Exception {
        return DetailSponsorTargetSpec.parse(new JSONObject()
                .put("ownerClass", "com.coolapk.market.model.$$AutoValue_Feed")
                .put("methodName", "getDetailSponsorCard")
                .put("returnType", "com.coolapk.market.model.Entity"));
    }

    private static DetailSponsorUiTargetSpec uiSpec() throws Exception {
        return uiSpec("com.coolapk.market.view.ad.ֈ");
    }

    private static DetailSponsorUiTargetSpec uiSpec(String ownerClass) throws Exception {
        return DetailSponsorUiTargetSpec.parse(new JSONObject()
                .put("ownerClass", ownerClass)
                .put("methodName", "ވ")
                .put("layoutField", "ކ")
                .put("entityClass", "com.coolapk.market.model.Entity")
                .put("entityTemplateGetter", "getEntityTemplate")
                .put("entityTemplate", "sponsorForFeedDetail")
                .put("adHelperClass", "com.coolapk.market.view.ad.EntityAdHelper")
                .put("bindingComponentClass", "androidx.databinding.DataBindingComponent")
                .put("viewHolderClass", "androidx.recyclerview.widget.RecyclerView$ViewHolder"));
    }

    private static final class FakeChain implements Chain {
        final AtomicInteger proceedCalls = new AtomicInteger();
        private final Object thisObject;
        private final Object result;
        private final Object arg0;

        FakeChain(Object thisObject, Object result, Object arg0) {
            this.thisObject = thisObject;
            this.result = result;
            this.arg0 = arg0;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @Override
        public List<Object> getArgs() {
            return Collections.emptyList();
        }

        @Override
        public Object getArg(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException();
            }
            return arg0;
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
        public Entity getDetailSponsorCard() {
            return new Entity();
        }
    }

    @SuppressWarnings("unused")
    private static final class WrongBinder {
        public void ވ(Object value) {
        }
    }
}
