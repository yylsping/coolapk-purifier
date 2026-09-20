package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D1 detail-sponsor card, conservative two-layer design:
 *
 * <pre>
 * Feed.getDetailSponsorCard() getter          -> observe only, ALWAYS proceeds
 * SponsorSelfDrawDetailViewHolder bind (ad.ֈ.ވ) -> proceed, then exact
 *     template check, then collapse the bound itemView
 * </pre>
 *
 * The getter never rewrites the host object: the FeedDetailActivityV8
 * integrity probe rebuilds a Feed with non-null sponsor fields and reads the
 * getters back, so a null-replacing hook is a deterministic fcFlagMap flag-8
 * fingerprint. Suppression happens only at the exact sponsor binder, after
 * the host bind completed, via GONE + zero-height collapse with full restore
 * on recycled holders.
 */
final class D1DetailSponsorDelta {
    static final String GETTER_HOOK_ID = "coolapk-d1-detail-sponsor";
    static final String UI_HOOK_ID = "coolapk-d1-detail-sponsor-ui";
    private static final int SUMMARY_EVERY = 32;

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;

    private final AtomicInteger hostObjectObservedCount = new AtomicInteger();
    private final AtomicInteger hostSponsorTemplateCount = new AtomicInteger();
    private final AtomicInteger originalReturnPreservedCount = new AtomicInteger();
    private final AtomicInteger targetUiObservedCount = new AtomicInteger();
    private final AtomicInteger targetUiSuppressedCount = new AtomicInteger();
    private final AtomicBoolean getterEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean hostObjectObservedLogged = new AtomicBoolean();
    private final AtomicBoolean targetUiObservedLogged = new AtomicBoolean();
    private final AtomicBoolean targetUiSuppressedLogged = new AtomicBoolean();
    private final java.util.Set<String> templatesLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // HookHandles must remain strongly reachable for the process lifetime.
    private volatile HookHandle getterHandle;
    private volatile HookHandle uiHandle;

    D1DetailSponsorDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    D1DetailSponsorDelta(XposedModule module, ModuleLog log, FeatureGate gate,
                         FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(DetailSponsorTargetSpec getterSpec,
                                       DetailSponsorUiTargetSpec uiSpec,
                                       ClassLoader loader) {
        if (getterHandle != null || uiHandle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (getterSpec == null || uiSpec == null) {
            log.info("d1_hook_registered=false reason=target_missing"
                    + " getterSpec=" + (getterSpec != null)
                    + " uiSpec=" + (uiSpec != null));
            return InstallResult.TARGET_MISSING;
        }
        String getterDescriptor = getterSpec.descriptor();
        String uiDescriptor = uiSpec.descriptor();
        Method getter;
        Method binder;
        try {
            Class<?> getterOwner = Class.forName(getterSpec.ownerClass, false, loader);
            getter = getterOwner.getDeclaredMethod(getterSpec.methodName);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d1_hook_registered=false reason=target_missing layer=getter"
                    + " descriptor=" + getterDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!isExactGetterTarget(getterSpec, getter)) {
            log.info("d1_hook_registered=false reason=contract_mismatch layer=getter"
                    + " descriptor=" + getterDescriptor);
            return InstallResult.CONTRACT_MISMATCH;
        }
        final Class<?> entityClass;
        final Method templateGetter;
        final HolderController controller;
        try {
            Class<?> uiOwner = Class.forName(uiSpec.ownerClass, false, loader);
            binder = uiOwner.getDeclaredMethod(uiSpec.methodName, Object.class);
            if (!isExactUiTarget(uiSpec, binder, loader)) {
                log.info("d1_hook_registered=false reason=contract_mismatch layer=ui"
                        + " descriptor=" + uiDescriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            entityClass = Class.forName(uiSpec.entityClass, false, loader);
            templateGetter = entityClass.getMethod(uiSpec.entityTemplateGetter);
            if (templateGetter.getParameterCount() != 0
                    || templateGetter.getReturnType() != String.class) {
                log.info("d1_hook_registered=false reason=contract_mismatch"
                        + " detail=entity_template_getter descriptor=" + uiDescriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            templateGetter.setAccessible(true);
            controller = new HolderController(uiOwner.getField("itemView"));
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d1_hook_registered=false reason=target_missing layer=ui"
                    + " descriptor=" + uiDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        } catch (Throwable throwable) {
            log.error("d1_hook_registered=false reason=installation_failure layer=ui"
                    + " descriptor=" + uiDescriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
        try {
            getter.setAccessible(true);
            binder.setAccessible(true);
            getterHandle = module.hook(getter)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(GETTER_HOOK_ID)
                    .intercept(chain -> onGetterEnter(chain, getterDescriptor,
                            uiSpec, entityClass, templateGetter));
            uiHandle = module.hook(binder)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(UI_HOOK_ID)
                    .intercept(chain -> onTargetUi(chain, gateEnabled(), uiSpec,
                            entityClass, templateGetter, controller, uiDescriptor));
            log.info("d1_hook_registered=true source=manifest_exact layer=getter"
                    + " mode=observe_only descriptor=" + getterDescriptor);
            log.info("d1_hook_registered=true source=manifest_exact layer=ui"
                    + " mode=binder_hide layout=R.layout.item_sponsor_self_draw_detail"
                    + " carveOut=" + uiSpec.entityTemplate
                    + " descriptor=" + uiDescriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.DETAIL_SPONSOR);
            }
            return InstallResult.INSTALLED;
        } catch (Throwable throwable) {
            // both-or-nothing: a half-installed pair must never survive an
            // installation failure.
            rollbackHandles();
            log.error("d1_hook_registered=false reason=installation_failure"
                    + " descriptor=" + getterDescriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    private void rollbackHandles() {
        HookHandle ui = uiHandle;
        uiHandle = null;
        if (ui != null) {
            try {
                ui.unhook();
            } catch (Throwable failure) {
                log.error("d1 ui hook rollback failed", failure);
            }
        }
        HookHandle getter = getterHandle;
        getterHandle = null;
        if (getter != null) {
            try {
                getter.unhook();
            } catch (Throwable failure) {
                log.error("d1 getter hook rollback failed", failure);
            }
        }
    }

    private boolean gateEnabled() {
        return gate != null && gate.isEffectiveEnabled(
                PurifierConfig.Feature.DETAIL_SPONSOR);
    }

    /**
     * Getter policy: observe only. The original always runs and its return
     * value is passed through untouched — the FeedDetailActivityV8 probe and
     * the gson/parcel/cache paths must observe the real object.
     */
    Object onGetterEnter(Chain chain, String descriptor,
                         DetailSponsorUiTargetSpec uiSpec, Class<?> entityClass,
                         Method templateGetter) throws Throwable {
        if (exposureLedger != null) {
            exposureLedger.recordEntered(PurifierConfig.Feature.DETAIL_SPONSOR);
        }
        Object original = chain.proceed();
        int preserved = originalReturnPreservedCount.incrementAndGet();
        if (original != null) {
            hostObjectObservedCount.incrementAndGet();
            if (exposureLedger != null) {
                exposureLedger.recordSampleSeen(PurifierConfig.Feature.DETAIL_SPONSOR);
            }
            String template = readTemplateSafely(original, entityClass, templateGetter);
            if (uiSpec != null && uiSpec.entityTemplate.equals(template)) {
                hostSponsorTemplateCount.incrementAndGet();
            }
            if (templatesLogged.size() < 64 && templatesLogged.add(template)) {
                log.info("d1_host_template_seen template=" + template
                        + " descriptor=" + descriptor);
            }
            if (hostObjectObservedLogged.compareAndSet(false, true)) {
                log.info("d1_host_object_observed=true template=" + template
                        + " descriptor=" + descriptor);
            }
        }
        if (getterEnteredLogged.compareAndSet(false, true)) {
            log.info("d1_interceptor_entered=true mode=observe_only"
                    + " original_return_preserved_count=" + preserved
                    + " descriptor=" + descriptor);
        }
        if (preserved % SUMMARY_EVERY == 0) {
            log.info(summaryLine());
        }
        return original;
    }

    private static String readTemplateSafely(Object entity, Class<?> entityClass,
                                             Method templateGetter) {
        try {
            if (entityClass != null && templateGetter != null
                    && entityClass.isInstance(entity)) {
                Object value = templateGetter.invoke(entity);
                return value == null ? "<null>" : value.toString();
            }
        } catch (Throwable ignored) {
            // Observation must never disturb the host object.
        }
        return "<unreadable>";
    }

    /**
     * Exact sponsor binder. The host binder always runs to completion; when
     * the feature is enabled and the bound entity is exactly the
     * sponsorForFeedDetail template, the bound itemView is collapsed
     * (GONE + zero height). Recycled holders are restored before bind and
     * whenever the entity is not the exact target or the feature is off.
     */
    Object onTargetUi(Chain chain, boolean enabled, DetailSponsorUiTargetSpec spec,
                      Class<?> entityClass, Method templateGetter,
                      HolderController controller, String descriptor) throws Throwable {
        Object holder = chain.getThisObject();
        if (enabled) {
            try {
                controller.update(holder, false);
            } catch (Throwable restoreFailure) {
                log.error("d1 recycled holder restore failed;"
                        + " continuing host binder", restoreFailure);
            }
        }
        Object original = chain.proceed();
        try {
            boolean exact = isExactDetailSponsorEntity(spec, chain.getArg(0),
                    entityClass, templateGetter);
            if (exact) {
                int observed = targetUiObservedCount.incrementAndGet();
                if (targetUiObservedLogged.compareAndSet(false, true)) {
                    log.info("d1_target_ui_observed=true template=" + spec.entityTemplate
                            + " descriptor=" + descriptor + " enabled=" + enabled);
                }
                if (shouldApplyViewMutation(enabled, exact)) {
                    controller.update(holder, true);
                    int suppressed = targetUiSuppressedCount.incrementAndGet();
                    if (exposureLedger != null) {
                        exposureLedger.recordModified(PurifierConfig.Feature.DETAIL_SPONSOR);
                    }
                    if (targetUiSuppressedLogged.compareAndSet(false, true)) {
                        log.info("d1_target_ui_suppressed=true count=" + suppressed
                                + " visibility=gone minimumHeight=0 layoutHeight=0"
                                + " descriptor=" + descriptor);
                    }
                }
                if (observed % SUMMARY_EVERY == 0) {
                    log.info(summaryLine());
                }
            }
        } catch (Throwable throwable) {
            if (enabled) {
                try {
                    controller.update(holder, false);
                } catch (Throwable restoreFailure) {
                    throwable.addSuppressed(restoreFailure);
                }
            }
            log.error("d1 decision failed; preserving holder state", throwable);
        }
        return original;
    }

    String summaryLine() {
        return "d1_target_ui_summary"
                + " d1_host_object_observed_count=" + hostObjectObservedCount.get()
                + " d1_host_sponsor_template_count=" + hostSponsorTemplateCount.get()
                + " d1_original_return_preserved_count=" + originalReturnPreservedCount.get()
                + " d1_target_ui_observed_count=" + targetUiObservedCount.get()
                + " d1_target_ui_suppressed_count=" + targetUiSuppressedCount.get();
    }

    static boolean isExactGetterTarget(DetailSponsorTargetSpec spec, Method method) {
        return spec != null && method != null
                && spec.methodName.equals(method.getName())
                && spec.ownerClass.equals(method.getDeclaringClass().getName())
                && method.getParameterTypes().length == 0
                && spec.returnType.equals(method.getReturnType().getName())
                && !Modifier.isAbstract(method.getModifiers());
    }

    static boolean isExactUiTarget(DetailSponsorUiTargetSpec spec, Method method,
                                   ClassLoader loader) {
        if (spec == null || method == null || loader == null
                || !spec.methodName.equals(method.getName())
                || !spec.ownerClass.equals(method.getDeclaringClass().getName())) {
            return false;
        }
        try {
            Class<?> owner = method.getDeclaringClass();
            int flags = method.getModifiers();
            if (!Modifier.isFinal(owner.getModifiers())
                    || !Modifier.isPublic(flags) || Modifier.isStatic(flags)
                    || method.getReturnType() != void.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != Object.class) {
                return false;
            }
            Method parent = owner.getSuperclass()
                    .getDeclaredMethod(spec.methodName, Object.class);
            if (parent.getReturnType() != void.class
                    || !Modifier.isPublic(parent.getModifiers())
                    || !Modifier.isAbstract(parent.getModifiers())) {
                return false;
            }
            Class<?> helper = Class.forName(spec.adHelperClass, false, loader);
            Class<?> component = Class.forName(spec.bindingComponentClass, false, loader);
            // SponsorSelfDrawDetailViewHolder's constructor takes the binding
            // component before the ad helper (the reverse of the D2 holder).
            if (!Modifier.isPublic(owner.getDeclaredConstructor(
                    View.class, component, helper).getModifiers())) {
                return false;
            }
            Field itemView = owner.getField("itemView");
            if (itemView.getType() != View.class
                    || !spec.viewHolderClass.equals(itemView.getDeclaringClass().getName())) {
                return false;
            }
            Field layout = owner.getDeclaredField(spec.layoutField);
            if (layout.getType() != int.class || !Modifier.isStatic(layout.getModifiers())) {
                return false;
            }
            boolean hasEntity = false;
            boolean hasHelper = false;
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                hasEntity |= spec.entityClass.equals(field.getType().getName());
                hasHelper |= field.getType() == helper;
            }
            return hasEntity && hasHelper;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isExactDetailSponsorEntity(DetailSponsorUiTargetSpec spec, Object entity,
                                              Class<?> entityClass, Method templateGetter)
            throws ReflectiveOperationException {
        return spec != null && entity != null && entityClass != null && templateGetter != null
                && entityClass.isInstance(entity)
                && spec.entityTemplate.equals(templateGetter.invoke(entity));
    }

    static boolean shouldApplyViewMutation(boolean enabled, boolean exactTemplate) {
        return enabled && exactTemplate;
    }

    // Test-only visibility into the both-or-nothing install invariant.
    synchronized boolean anyHookInstalled() {
        return getterHandle != null || uiHandle != null;
    }

    static final class HolderController {
        private final Field itemViewField;
        private final Map<View, ViewState> collapsed = new WeakHashMap<>();

        HolderController(Field itemViewField) {
            if (itemViewField == null || itemViewField.getType() != View.class) {
                throw new IllegalArgumentException("exact itemView field required");
            }
            this.itemViewField = itemViewField;
            this.itemViewField.setAccessible(true);
        }

        void update(Object holder, boolean sponsored) throws IllegalAccessException {
            Object candidate = itemViewField.get(holder);
            if (!(candidate instanceof View)) {
                return;
            }
            View itemView = (View) candidate;
            if (!sponsored) {
                ViewState state = collapsed.remove(itemView);
                if (state != null) {
                    state.restore(itemView);
                }
                return;
            }
            if (!collapsed.containsKey(itemView)) {
                collapsed.put(itemView, ViewState.capture(itemView));
            }
            itemView.setVisibility(View.GONE);
            itemView.setMinimumHeight(0);
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params != null && params.height != 0) {
                params.height = 0;
                itemView.setLayoutParams(params);
            }
        }
    }

    private static final class ViewState {
        final int visibility;
        final int minimumHeight;
        final int layoutHeight;

        ViewState(int visibility, int minimumHeight, int layoutHeight) {
            this.visibility = visibility;
            this.minimumHeight = minimumHeight;
            this.layoutHeight = layoutHeight;
        }

        static ViewState capture(View view) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            return new ViewState(view.getVisibility(), view.getMinimumHeight(),
                    params == null ? Integer.MIN_VALUE : params.height);
        }

        void restore(View view) {
            view.setVisibility(visibility);
            view.setMinimumHeight(minimumHeight);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && layoutHeight != Integer.MIN_VALUE
                    && params.height != layoutHeight) {
                params.height = layoutHeight;
                view.setLayoutParams(params);
            }
        }
    }
}
