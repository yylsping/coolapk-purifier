package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * RELATED_DATA conservative implementation.
 *
 * <p>The historical list-replacement hook is deliberately not present. The
 * Feed getter is observe-only and always returns the exact host value. UI
 * suppression is limited to two independently verified terminal binders:
 * the detail-page iconListCard family (goods/topic subtypes) and the exact
 * FeedBindGoodsViewHolder used by the content-related section.</p>
 */
final class RelatedDataDelta {
    static final String GETTER_HOOK_ID = "coolapk-related-data-getter";
    static final String ICON_UI_HOOK_ID = "coolapk-related-data-icon-ui";
    static final String CONTENT_UI_HOOK_ID = "coolapk-related-data-content-ui";
    private static final int SUMMARY_EVERY = 32;

    enum IconSubtype {
        NONE,
        PROMOTION,
        SINGLE_RECOMMEND
    }

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;

    private final AtomicInteger getterEnteredCount = new AtomicInteger();
    private final AtomicInteger originalReturnPreservedCount = new AtomicInteger();
    private final AtomicInteger promotionObservedCount = new AtomicInteger();
    private final AtomicInteger promotionSuppressedCount = new AtomicInteger();
    private final AtomicInteger singleObservedCount = new AtomicInteger();
    private final AtomicInteger singleSuppressedCount = new AtomicInteger();
    private final AtomicInteger contentObservedCount = new AtomicInteger();
    private final AtomicInteger contentSuppressedCount = new AtomicInteger();
    private final AtomicBoolean getterLogged = new AtomicBoolean();
    private final AtomicBoolean promotionObservedLogged = new AtomicBoolean();
    private final AtomicBoolean promotionSuppressedLogged = new AtomicBoolean();
    private final AtomicBoolean singleObservedLogged = new AtomicBoolean();
    private final AtomicBoolean singleSuppressedLogged = new AtomicBoolean();
    private final AtomicBoolean contentObservedLogged = new AtomicBoolean();
    private final AtomicBoolean contentSuppressedLogged = new AtomicBoolean();

    private volatile HookHandle getterHandle;
    private volatile HookHandle iconUiHandle;
    private volatile HookHandle contentUiHandle;

    RelatedDataDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    RelatedDataDelta(XposedModule module, ModuleLog log, FeatureGate gate,
                     FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi")
    synchronized InstallResult install(RelatedDataTargetSpec getterSpec,
                                       RelatedIconListUiTargetSpec iconSpec,
                                       RelatedContentUiTargetSpec contentSpec,
                                       ClassLoader loader) {
        if (getterHandle != null || iconUiHandle != null || contentUiHandle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (getterSpec == null || loader == null) {
            log.info("related_hook_registered=false reason=target_missing layer=getter");
            return InstallResult.TARGET_MISSING;
        }

        final Method getter;
        try {
            Class<?> owner = Class.forName(getterSpec.ownerClass, false, loader);
            getter = owner.getDeclaredMethod(getterSpec.methodName);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("related_hook_registered=false reason=target_missing layer=getter"
                    + " descriptor=" + getterSpec.descriptor() + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!isExactGetterTarget(getterSpec, getter)) {
            log.info("related_hook_registered=false reason=contract_mismatch layer=getter"
                    + " descriptor=" + getterSpec.descriptor());
            return InstallResult.CONTRACT_MISMATCH;
        }

        IconRuntime icon = resolveIconRuntime(iconSpec, loader);
        ContentRuntime content = resolveContentRuntime(contentSpec, loader);

        try {
            getter.setAccessible(true);
            getterHandle = module.hook(getter)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(GETTER_HOOK_ID)
                    .intercept(this::onGetter);
            log.info("related_hook_registered=true source=manifest_exact layer=getter"
                    + " mode=observe_only descriptor=" + getterSpec.descriptor());
        } catch (Throwable failure) {
            rollbackAll();
            log.error("related_hook_registered=false reason=installation_failure layer=getter",
                    failure);
            return InstallResult.INSTALL_FAILED;
        }

        int installedUi = 0;
        if (icon != null) {
            try {
                icon.binder.setAccessible(true);
                iconUiHandle = module.hook(icon.binder)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId(ICON_UI_HOOK_ID)
                        .intercept(chain -> onIconUi(chain, gateEnabled(), icon));
                installedUi++;
                log.info("related_hook_registered=true source=manifest_exact layer=icon_ui"
                        + " mode=binder_hide descriptor=" + icon.spec.descriptor()
                        + " carveOut=" + icon.spec.cardTemplate + "/{"
                        + icon.spec.promotionEntityType + ','
                        + icon.spec.singleRecommendEntityType + "}"
                        + " host=" + icon.spec.hostFragmentClass);
            } catch (Throwable failure) {
                iconUiHandle = null;
                log.error("related_hook_registered=false reason=installation_failure"
                        + " layer=icon_ui descriptor=" + icon.spec.descriptor(), failure);
            }
        }
        if (content != null) {
            try {
                content.binder.setAccessible(true);
                contentUiHandle = module.hook(content.binder)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId(CONTENT_UI_HOOK_ID)
                        .intercept(chain -> onContentUi(chain, gateEnabled(), content));
                installedUi++;
                log.info("related_hook_registered=true source=manifest_exact layer=content_ui"
                        + " mode=binder_hide descriptor=" + content.spec.descriptor()
                        + " carveOut=" + content.spec.entityType);
            } catch (Throwable failure) {
                contentUiHandle = null;
                log.error("related_hook_registered=false reason=installation_failure"
                        + " layer=content_ui descriptor=" + content.spec.descriptor(), failure);
            }
        }

        if (exposureLedger != null) {
            exposureLedger.recordHookInstalled(PurifierConfig.Feature.RELATED_DATA);
        }
        String coverage = installedUi == 2 ? "FULL"
                : installedUi == 0 ? "OBSERVE_ONLY" : "PARTIAL";
        log.info("related_coverage=" + coverage
                + " getter=OBSERVE_ONLY"
                + " promotion=" + (iconUiHandle != null ? "AVAILABLE" : "UNAVAILABLE")
                + " singleRecommend=" + (iconUiHandle != null ? "AVAILABLE" : "UNAVAILABLE")
                + " contentRelated=" + (contentUiHandle != null ? "AVAILABLE" : "UNAVAILABLE"));
        return installedUi == 2 ? InstallResult.INSTALLED : InstallResult.PARTIAL;
    }

    private IconRuntime resolveIconRuntime(RelatedIconListUiTargetSpec spec,
                                           ClassLoader loader) {
        if (spec == null) {
            log.info("related_subtype_available=false subtype=iconList reason=target_missing");
            return null;
        }
        try {
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            Method binder = owner.getDeclaredMethod(spec.methodName, Object.class);
            if (!isExactIconUiTarget(spec, binder, loader)) {
                log.info("related_subtype_available=false subtype=iconList"
                        + " reason=contract_mismatch descriptor=" + spec.descriptor());
                return null;
            }
            Class<?> holderClass = Class.forName(spec.holderClass, false, loader);
            Class<?> callbackClass = Class.forName(spec.callbackClass, false, loader);
            Class<?> hostFragmentClass = Class.forName(spec.hostFragmentClass, false, loader);
            Class<?> cardClass = Class.forName(spec.cardClass, false, loader);
            Class<?> entityClass = Class.forName(spec.entityClass, false, loader);
            Field callbackField = owner.getDeclaredField(spec.callbackField);
            Field fragmentField = callbackClass.getDeclaredField(spec.fragmentField);
            callbackField.setAccessible(true);
            fragmentField.setAccessible(true);
            Method templateGetter = cardClass.getMethod(spec.cardTemplateGetter);
            Method entitiesGetter = cardClass.getMethod(spec.entitiesGetter);
            Method entityTypeGetter = entityClass.getMethod(spec.entityTypeGetter);
            templateGetter.setAccessible(true);
            entitiesGetter.setAccessible(true);
            entityTypeGetter.setAccessible(true);
            return new IconRuntime(spec, binder, holderClass, callbackClass,
                    hostFragmentClass, cardClass, entityClass, callbackField,
                    fragmentField, templateGetter, entitiesGetter, entityTypeGetter,
                    new HolderController(holderClass.getField("itemView")));
        } catch (Throwable failure) {
            log.info("related_subtype_available=false subtype=iconList"
                    + " reason=target_missing descriptor=" + spec.descriptor()
                    + " error=" + failure);
            return null;
        }
    }

    private ContentRuntime resolveContentRuntime(RelatedContentUiTargetSpec spec,
                                                 ClassLoader loader) {
        if (spec == null) {
            log.info("related_subtype_available=false subtype=contentRelated"
                    + " reason=target_missing");
            return null;
        }
        try {
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            Class<?> dataClass = Class.forName(spec.dataClass, false, loader);
            Method binder = owner.getDeclaredMethod(spec.methodName, dataClass);
            if (!isExactContentUiTarget(spec, binder, loader)) {
                log.info("related_subtype_available=false subtype=contentRelated"
                        + " reason=contract_mismatch descriptor=" + spec.descriptor());
                return null;
            }
            Method entityTypeGetter = dataClass.getMethod(spec.entityTypeGetter);
            entityTypeGetter.setAccessible(true);
            return new ContentRuntime(spec, binder, dataClass, entityTypeGetter,
                    new HolderController(owner.getField("itemView")));
        } catch (Throwable failure) {
            log.info("related_subtype_available=false subtype=contentRelated"
                    + " reason=target_missing descriptor=" + spec.descriptor()
                    + " error=" + failure);
            return null;
        }
    }

    private boolean gateEnabled() {
        return gate != null
                && gate.isEffectiveEnabled(PurifierConfig.Feature.RELATED_DATA);
    }

    Object onGetter(Chain chain) throws Throwable {
        if (exposureLedger != null) {
            exposureLedger.recordEntered(PurifierConfig.Feature.RELATED_DATA);
        }
        int entered = getterEnteredCount.incrementAndGet();
        Object original = chain.proceed();
        originalReturnPreservedCount.incrementAndGet();
        if (getterLogged.compareAndSet(false, true)) {
            log.info("related_getter_entered=true mode=observe_only"
                    + " original_return_preserved=true");
        }
        if (entered % SUMMARY_EVERY == 0) {
            log.info(summaryLine());
        }
        return original;
    }

    Object onIconUi(Chain chain, boolean enabled, IconRuntime runtime) throws Throwable {
        Object holder = chain.getThisObject();
        boolean exactHost = false;
        try {
            exactHost = runtime.isExactDetailHost(holder);
            if (exactHost) {
                runtime.controller.update(holder, false);
            }
        } catch (Throwable restoreFailure) {
            log.error("related icon recycled holder restore failed; continuing host binder",
                    restoreFailure);
        }

        Object original = chain.proceed();
        if (!exactHost) {
            return original;
        }
        try {
            IconSubtype subtype = classifyIconCard(runtime.spec, chain.getArg(0),
                    runtime.cardClass, runtime.entityClass, runtime.templateGetter,
                    runtime.entitiesGetter, runtime.entityTypeGetter);
            if (subtype == IconSubtype.NONE) {
                return original;
            }
            if (exposureLedger != null) {
                exposureLedger.recordEntered(PurifierConfig.Feature.RELATED_DATA);
                exposureLedger.recordSampleSeen(PurifierConfig.Feature.RELATED_DATA);
            }
            boolean promotion = subtype == IconSubtype.PROMOTION;
            AtomicInteger observedCounter = promotion
                    ? promotionObservedCount : singleObservedCount;
            AtomicBoolean observedLogged = promotion
                    ? promotionObservedLogged : singleObservedLogged;
            AtomicInteger suppressedCounter = promotion
                    ? promotionSuppressedCount : singleSuppressedCount;
            AtomicBoolean suppressedLogged = promotion
                    ? promotionSuppressedLogged : singleSuppressedLogged;
            int observed = observedCounter.incrementAndGet();
            String name = promotion ? "promotion" : "single_recommend";
            if (observedLogged.compareAndSet(false, true)) {
                log.info("related_" + name + "_ui_observed=true enabled=" + enabled
                        + " descriptor=" + runtime.spec.descriptor());
            }
            if (enabled) {
                runtime.controller.update(holder, true);
                runtime.controller.collapseResidualSpacingAfterLayout(holder, log, name);
                int suppressed = suppressedCounter.incrementAndGet();
                if (exposureLedger != null) {
                    exposureLedger.recordModified(PurifierConfig.Feature.RELATED_DATA);
                }
                if (suppressedLogged.compareAndSet(false, true)) {
                    log.info("related_" + name + "_ui_suppressed=true count=" + suppressed
                            + " visibility=gone minimumHeight=0 layoutHeight=0"
                            + " residualSpacingScheduled=true");
                }
            }
            if (observed % SUMMARY_EVERY == 0) {
                log.info(summaryLine());
            }
        } catch (Throwable failure) {
            try {
                runtime.controller.update(holder, false);
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            log.error("related icon decision failed; preserving holder state", failure);
        }
        return original;
    }

    Object onContentUi(Chain chain, boolean enabled, ContentRuntime runtime) throws Throwable {
        Object holder = chain.getThisObject();
        try {
            runtime.controller.update(holder, false);
        } catch (Throwable restoreFailure) {
            log.error("related content recycled holder restore failed; continuing host binder",
                    restoreFailure);
        }
        Object original = chain.proceed();
        try {
            if (!isExactContentEntity(runtime.spec, chain.getArg(0),
                    runtime.dataClass, runtime.entityTypeGetter)) {
                return original;
            }
            if (exposureLedger != null) {
                exposureLedger.recordEntered(PurifierConfig.Feature.RELATED_DATA);
                exposureLedger.recordSampleSeen(PurifierConfig.Feature.RELATED_DATA);
            }
            int observed = contentObservedCount.incrementAndGet();
            if (contentObservedLogged.compareAndSet(false, true)) {
                log.info("related_content_section_ui_observed=true enabled=" + enabled
                        + " descriptor=" + runtime.spec.descriptor());
            }
            if (enabled) {
                runtime.controller.update(holder, true);
                runtime.controller.collapseResidualSpacingAfterLayout(
                        holder, log, "content_section");
                int suppressed = contentSuppressedCount.incrementAndGet();
                if (exposureLedger != null) {
                    exposureLedger.recordModified(PurifierConfig.Feature.RELATED_DATA);
                }
                if (contentSuppressedLogged.compareAndSet(false, true)) {
                    log.info("related_content_section_ui_suppressed=true count=" + suppressed
                            + " visibility=gone minimumHeight=0 layoutHeight=0"
                            + " residualSpacingScheduled=true");
                }
            }
            if (observed % SUMMARY_EVERY == 0) {
                log.info(summaryLine());
            }
        } catch (Throwable failure) {
            try {
                runtime.controller.update(holder, false);
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            log.error("related content decision failed; preserving holder state", failure);
        }
        return original;
    }

    String summaryLine() {
        return "related_ui_summary"
                + " related_getter_entered_count=" + getterEnteredCount.get()
                + " related_original_return_preserved_count="
                + originalReturnPreservedCount.get()
                + " related_promotion_ui_observed=" + promotionObservedCount.get()
                + " related_promotion_ui_suppressed=" + promotionSuppressedCount.get()
                + " related_single_recommend_ui_observed=" + singleObservedCount.get()
                + " related_single_recommend_ui_suppressed=" + singleSuppressedCount.get()
                + " related_content_section_ui_observed=" + contentObservedCount.get()
                + " related_content_section_ui_suppressed=" + contentSuppressedCount.get();
    }

    static boolean isExactGetterTarget(RelatedDataTargetSpec spec, Method method) {
        return spec != null && method != null
                && spec.ownerClass.equals(method.getDeclaringClass().getName())
                && spec.methodName.equals(method.getName())
                && method.getParameterCount() == 0
                && spec.returnType.equals(method.getReturnType().getName())
                && !Modifier.isAbstract(method.getModifiers());
    }

    static boolean isExactIconUiTarget(RelatedIconListUiTargetSpec spec, Method method,
                                       ClassLoader loader) {
        if (spec == null || method == null || loader == null
                || !spec.ownerClass.equals(method.getDeclaringClass().getName())
                || !spec.methodName.equals(method.getName())) {
            return false;
        }
        try {
            int flags = method.getModifiers();
            if (!Modifier.isPublic(flags) || Modifier.isStatic(flags)
                    || Modifier.isAbstract(flags) || method.getReturnType() != void.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != Object.class) {
                return false;
            }
            Class<?> owner = method.getDeclaringClass();
            Class<?> holder = Class.forName(spec.holderClass, false, loader);
            Class<?> callback = Class.forName(spec.callbackClass, false, loader);
            Class<?> fragmentBase = Class.forName(spec.fragmentBaseClass, false, loader);
            Class<?> hostFragment = Class.forName(spec.hostFragmentClass, false, loader);
            Class<?> component = Class.forName(spec.bindingComponentClass, false, loader);
            Class<?> card = Class.forName(spec.cardClass, false, loader);
            Class<?> entity = Class.forName(spec.entityClass, false, loader);
            if (!Modifier.isFinal(holder.getModifiers()) || holder.getSuperclass() != owner
                    || !fragmentBase.isAssignableFrom(hostFragment)
                    || !Modifier.isPublic(holder.getDeclaredConstructor(
                    View.class, component, fragmentBase).getModifiers())) {
                return false;
            }
            Field callbackField = owner.getDeclaredField(spec.callbackField);
            Field fragmentField = callback.getDeclaredField(spec.fragmentField);
            if (Modifier.isStatic(callbackField.getModifiers())
                    || !callbackField.getType().isAssignableFrom(callback)
                    || Modifier.isStatic(fragmentField.getModifiers())
                    || fragmentField.getType() != fragmentBase) {
                return false;
            }
            Field itemView = holder.getField("itemView");
            if (itemView.getType() != View.class
                    || !spec.viewHolderClass.equals(itemView.getDeclaringClass().getName())) {
                return false;
            }
            Method template = card.getMethod(spec.cardTemplateGetter);
            Method entities = card.getMethod(spec.entitiesGetter);
            Method entityType = entity.getMethod(spec.entityTypeGetter);
            return template.getParameterCount() == 0
                    && template.getReturnType() == String.class
                    && entities.getParameterCount() == 0
                    && List.class.isAssignableFrom(entities.getReturnType())
                    && entityType.getParameterCount() == 0
                    && entityType.getReturnType() == String.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isExactContentUiTarget(RelatedContentUiTargetSpec spec, Method method,
                                          ClassLoader loader) {
        if (spec == null || method == null || loader == null
                || !spec.ownerClass.equals(method.getDeclaringClass().getName())
                || !spec.methodName.equals(method.getName())) {
            return false;
        }
        try {
            Class<?> owner = method.getDeclaringClass();
            Class<?> data = Class.forName(spec.dataClass, false, loader);
            Class<?> component = Class.forName(spec.bindingComponentClass, false, loader);
            Class<?> viewModel = Class.forName(spec.viewModelClass, false, loader);
            int flags = method.getModifiers();
            if (!Modifier.isFinal(owner.getModifiers())
                    || !Modifier.isPublic(flags) || Modifier.isStatic(flags)
                    || method.getReturnType() != void.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != data
                    || !Modifier.isPublic(owner.getDeclaredConstructor(
                    View.class, component, viewModel).getModifiers())) {
                return false;
            }
            Field itemView = owner.getField("itemView");
            Method entityType = data.getMethod(spec.entityTypeGetter);
            return itemView.getType() == View.class
                    && spec.viewHolderClass.equals(itemView.getDeclaringClass().getName())
                    && entityType.getParameterCount() == 0
                    && entityType.getReturnType() == String.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static IconSubtype classifyIconCard(RelatedIconListUiTargetSpec spec, Object value,
                                        Class<?> cardClass, Class<?> entityClass,
                                        Method templateGetter, Method entitiesGetter,
                                        Method entityTypeGetter)
            throws ReflectiveOperationException {
        if (spec == null || value == null || cardClass == null || entityClass == null
                || templateGetter == null || entitiesGetter == null
                || entityTypeGetter == null || !cardClass.isInstance(value)
                || !spec.cardTemplate.equals(templateGetter.invoke(value))) {
            return IconSubtype.NONE;
        }
        Object raw = entitiesGetter.invoke(value);
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return IconSubtype.NONE;
        }
        boolean allPromotion = true;
        boolean allSingle = true;
        for (Object child : (List<?>) raw) {
            if (child == null || !entityClass.isInstance(child)) {
                return IconSubtype.NONE;
            }
            Object type = entityTypeGetter.invoke(child);
            allPromotion &= spec.promotionEntityType.equals(type);
            allSingle &= spec.singleRecommendEntityType.equals(type);
            if (!allPromotion && !allSingle) {
                return IconSubtype.NONE;
            }
        }
        if (allPromotion) {
            return IconSubtype.PROMOTION;
        }
        return allSingle ? IconSubtype.SINGLE_RECOMMEND : IconSubtype.NONE;
    }

    static boolean isExactContentEntity(RelatedContentUiTargetSpec spec, Object value,
                                        Class<?> dataClass, Method entityTypeGetter)
            throws ReflectiveOperationException {
        return spec != null && value != null && dataClass != null && entityTypeGetter != null
                && dataClass.isInstance(value)
                && spec.entityType.equals(entityTypeGetter.invoke(value));
    }

    private void rollbackAll() {
        HookHandle content = contentUiHandle;
        contentUiHandle = null;
        if (content != null) {
            try {
                content.unhook();
            } catch (Throwable failure) {
                log.error("related content hook rollback failed", failure);
            }
        }
        HookHandle icon = iconUiHandle;
        iconUiHandle = null;
        if (icon != null) {
            try {
                icon.unhook();
            } catch (Throwable failure) {
                log.error("related icon hook rollback failed", failure);
            }
        }
        HookHandle getter = getterHandle;
        getterHandle = null;
        if (getter != null) {
            try {
                getter.unhook();
            } catch (Throwable failure) {
                log.error("related getter hook rollback failed", failure);
            }
        }
    }

    synchronized boolean anyHookInstalled() {
        return getterHandle != null || iconUiHandle != null || contentUiHandle != null;
    }

    synchronized boolean getterInstalled() {
        return getterHandle != null;
    }

    synchronized boolean iconUiInstalled() {
        return iconUiHandle != null;
    }

    synchronized boolean contentUiInstalled() {
        return contentUiHandle != null;
    }

    static final class IconRuntime {
        final RelatedIconListUiTargetSpec spec;
        final Method binder;
        final Class<?> holderClass;
        final Class<?> callbackClass;
        final Class<?> hostFragmentClass;
        final Class<?> cardClass;
        final Class<?> entityClass;
        final Field callbackField;
        final Field fragmentField;
        final Method templateGetter;
        final Method entitiesGetter;
        final Method entityTypeGetter;
        final HolderController controller;

        IconRuntime(RelatedIconListUiTargetSpec spec, Method binder,
                    Class<?> holderClass, Class<?> callbackClass,
                    Class<?> hostFragmentClass, Class<?> cardClass,
                    Class<?> entityClass, Field callbackField, Field fragmentField,
                    Method templateGetter, Method entitiesGetter,
                    Method entityTypeGetter, HolderController controller) {
            this.spec = spec;
            this.binder = binder;
            this.holderClass = holderClass;
            this.callbackClass = callbackClass;
            this.hostFragmentClass = hostFragmentClass;
            this.cardClass = cardClass;
            this.entityClass = entityClass;
            this.callbackField = callbackField;
            this.fragmentField = fragmentField;
            this.templateGetter = templateGetter;
            this.entitiesGetter = entitiesGetter;
            this.entityTypeGetter = entityTypeGetter;
            this.controller = controller;
        }

        boolean isExactDetailHost(Object holder) throws IllegalAccessException {
            if (holder == null || holder.getClass() != holderClass) {
                return false;
            }
            Object callback = callbackField.get(holder);
            if (!callbackClass.isInstance(callback)) {
                return false;
            }
            return hostFragmentClass.isInstance(fragmentField.get(callback));
        }
    }

    static final class ContentRuntime {
        final RelatedContentUiTargetSpec spec;
        final Method binder;
        final Class<?> dataClass;
        final Method entityTypeGetter;
        final HolderController controller;

        ContentRuntime(RelatedContentUiTargetSpec spec, Method binder,
                       Class<?> dataClass, Method entityTypeGetter,
                       HolderController controller) {
            this.spec = spec;
            this.binder = binder;
            this.dataClass = dataClass;
            this.entityTypeGetter = entityTypeGetter;
            this.controller = controller;
        }
    }

    static final class HolderController {
        private final Field itemViewField;
        private final Map<View, ViewState> collapsed = new WeakHashMap<>();
        private final Map<View, DecorationState> spacingAdjustments =
                new WeakHashMap<>();
        private final AtomicBoolean spacingLogged = new AtomicBoolean();

        HolderController(Field itemViewField) {
            if (itemViewField == null || itemViewField.getType() != View.class) {
                throw new IllegalArgumentException("exact itemView field required");
            }
            this.itemViewField = itemViewField;
            this.itemViewField.setAccessible(true);
        }

        synchronized void update(Object holder, boolean collapse)
                throws IllegalAccessException {
            Object candidate = itemViewField.get(holder);
            if (!(candidate instanceof View)) {
                return;
            }
            View itemView = (View) candidate;
            if (!collapse) {
                DecorationState adjustment = spacingAdjustments.remove(itemView);
                if (adjustment != null) {
                    adjustment.restore();
                }
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
            boolean layoutChanged = false;
            if (params != null && params.height != 0) {
                params.height = 0;
                layoutChanged = true;
            }
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) params;
                if (margins.topMargin != 0 || margins.bottomMargin != 0) {
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                    layoutChanged = true;
                }
            }
            if (params != null && layoutChanged) {
                itemView.setLayoutParams(params);
            }
        }

        void collapseResidualSpacingAfterLayout(Object holder, ModuleLog log, String label) {
            if (holder == null || log == null) {
                return;
            }
            try {
                Object candidate = itemViewField.get(holder);
                if (!(candidate instanceof View)) {
                    return;
                }
                View itemView = (View) candidate;
                itemView.post(() -> collapseResidualSpacing(itemView, log, label));
            } catch (Throwable failure) {
                log.error("related residual spacing scheduling failed", failure);
            }
        }

        private void collapseResidualSpacing(View itemView, ModuleLog log, String label) {
            try {
                synchronized (this) {
                    if (!collapsed.containsKey(itemView)) {
                        return;
                    }
                }
                if (!(itemView.getParent() instanceof ViewGroup)) {
                    return;
                }
                ViewGroup group = (ViewGroup) itemView.getParent();
                int childIndex = group.indexOfChild(itemView);
                if (childIndex < 0) {
                    return;
                }
                View previous = childIndex > 0 ? group.getChildAt(childIndex - 1) : null;
                View next = childIndex + 1 < group.getChildCount()
                        ? group.getChildAt(childIndex + 1) : null;
                int topGap = previous == null ? 0
                        : Math.max(0, itemView.getTop() - previous.getBottom());
                int bottomGap = next == null ? 0
                        : Math.max(0, next.getTop() - itemView.getBottom());
                synchronized (this) {
                    if (spacingAdjustments.containsKey(itemView)) {
                        return;
                    }
                }
                DecorationState adjustment = DecorationState.suppress(
                        group, itemView, topGap, bottomGap);
                if (adjustment == null) {
                    return;
                }
                synchronized (this) {
                    spacingAdjustments.put(itemView, adjustment);
                }
                adjustment.invalidate();
                boolean logThisCollapse = spacingLogged.compareAndSet(false, true);
                if (logThisCollapse) {
                    log.info("related_residual_spacing_collapsed=true subtype=" + label
                            + " topGapPx=" + topGap
                            + " bottomGapPx=" + bottomGap
                            + " dividerEntries=" + adjustment.entries.size());
                }
                itemView.post(() -> settleResidualSpacing(
                        itemView, log, label, adjustment, 2, logThisCollapse));
            } catch (Throwable failure) {
                log.error("related residual spacing collapse failed", failure);
            }
        }

        private void settleResidualSpacing(View itemView, ModuleLog log, String label,
                                           DecorationState expectedState,
                                           int attemptsRemaining, boolean logResult) {
            try {
                synchronized (this) {
                    if (!collapsed.containsKey(itemView)
                            || spacingAdjustments.get(itemView) != expectedState) {
                        return;
                    }
                }
                if (!(itemView.getParent() instanceof ViewGroup)) {
                    return;
                }
                ViewGroup group = (ViewGroup) itemView.getParent();
                int childIndex = group.indexOfChild(itemView);
                if (childIndex <= 0 || childIndex + 1 >= group.getChildCount()) {
                    return;
                }
                View previous = group.getChildAt(childIndex - 1);
                View next = group.getChildAt(childIndex + 1);
                int topGap = Math.max(0, itemView.getTop() - previous.getBottom());
                int bottomGap = Math.max(0, next.getTop() - itemView.getBottom());
                int remaining = Math.max(0, next.getTop() - previous.getBottom());
                if (remaining > 0 && attemptsRemaining > 0) {
                    DecorationState additional = DecorationState.suppress(
                            group, itemView, topGap, bottomGap);
                    if (additional != null) {
                        expectedState.append(additional);
                        additional.invalidate();
                        itemView.post(() -> settleResidualSpacing(
                                itemView, log, label, expectedState,
                                attemptsRemaining - 1, logResult));
                        return;
                    }
                }
                if (logResult) {
                    log.info("related_residual_spacing_settled=true subtype=" + label
                            + " remainingGapPx=" + remaining
                            + " dividerEntries=" + expectedState.entries.size()
                            + spacingGeometry(itemView));
                }
            } catch (Throwable failure) {
                log.error("related residual spacing settle failed", failure);
            }
        }

        private static String spacingGeometry(View itemView) {
            if (itemView == null || !(itemView.getParent() instanceof ViewGroup)) {
                return " unavailable=true";
            }
            ViewGroup group = (ViewGroup) itemView.getParent();
            int childIndex = group.indexOfChild(itemView);
            if (childIndex < 0) {
                return " detached=true";
            }
            View previous = childIndex > 0 ? group.getChildAt(childIndex - 1) : null;
            View next = childIndex + 1 < group.getChildCount()
                    ? group.getChildAt(childIndex + 1) : null;
            return " previousBottom=" + (previous == null ? -1 : previous.getBottom())
                    + " targetTop=" + itemView.getTop()
                    + " targetBottom=" + itemView.getBottom()
                    + " nextTop=" + (next == null ? -1 : next.getTop());
        }
    }

    /**
     * Replaces only the two cached host dividers adjacent to an exact collapsed item.
     * The host's own zero-height divider singleton is reused, so offset calculation and
     * drawing agree. Cache entries are restored by identity before the holder is reused.
     */
    private static final class DecorationState {
        final ViewGroup parent;
        final Method invalidateItemDecorations;
        final List<DecorationCacheEntry> entries;

        DecorationState(ViewGroup parent, Method invalidateItemDecorations,
                        List<DecorationCacheEntry> entries) {
            this.parent = parent;
            this.invalidateItemDecorations = invalidateItemDecorations;
            this.entries = entries;
        }

        static DecorationState suppress(ViewGroup parent, View target,
                                        int topGap, int bottomGap) throws Exception {
            if (topGap <= 0 && bottomGap <= 0) {
                return null;
            }
            Class<?> parentClass = parent.getClass();
            Method positionMethod = parentClass.getMethod(
                    "getChildAdapterPosition", View.class);
            Method countMethod = parentClass.getMethod("getItemDecorationCount");
            Method atMethod = parentClass.getMethod("getItemDecorationAt", int.class);
            Method invalidateMethod = parentClass.getMethod("invalidateItemDecorations");
            int targetPosition = ((Number) positionMethod.invoke(parent, target)).intValue();
            if (targetPosition <= 0) {
                return null;
            }
            int decorationCount = ((Number) countMethod.invoke(parent)).intValue();
            List<DecorationCacheEntry> entries = new ArrayList<>();
            for (int index = 0; index < decorationCount; index++) {
                Object decoration = atMethod.invoke(parent, index);
                collectDividerEntries(decoration, targetPosition, topGap, bottomGap, entries);
            }
            if (entries.isEmpty()) {
                return null;
            }
            return new DecorationState(parent, invalidateMethod, entries);
        }

        private static void collectDividerEntries(Object decoration, int targetPosition,
                                                  int topGap, int bottomGap,
                                                  List<DecorationCacheEntry> entries)
                throws IllegalAccessException {
            for (Class<?> type = decoration.getClass(); type != null;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!SparseArray.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object candidate = field.get(decoration);
                    if (!(candidate instanceof SparseArray)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    SparseArray<Object> cache = (SparseArray<Object>) candidate;
                    collectDividerEntry(cache, targetPosition - 1, topGap, entries);
                    collectDividerEntry(cache, targetPosition, bottomGap, entries);
                }
            }
        }

        private static void collectDividerEntry(SparseArray<Object> cache, int position,
                                                int expectedHeight,
                                                List<DecorationCacheEntry> entries)
                throws IllegalAccessException {
            if (expectedHeight <= 0 || cache.indexOfKey(position) < 0) {
                return;
            }
            Object original = cache.get(position);
            // DividerData has an optional boxed color; the sibling margin cache does not.
            // Matching the measured pixel height keeps this structural lookup fail-closed.
            if (original == null || dividerHeight(original) != expectedHeight
                    || !hasBoxedIntegerField(original.getClass())) {
                return;
            }
            Object replacement = findZeroDivider(original.getClass());
            if (replacement == null) {
                return;
            }
            cache.put(position, replacement);
            entries.add(new DecorationCacheEntry(
                    cache, position, original, replacement));
        }

        private static int dividerHeight(Object value) throws IllegalAccessException {
            int maximum = Integer.MIN_VALUE;
            for (Field field : value.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                    continue;
                }
                field.setAccessible(true);
                maximum = Math.max(maximum, field.getInt(value));
            }
            return maximum;
        }

        private static boolean hasBoxedIntegerField(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && field.getType() == Integer.class) {
                    return true;
                }
            }
            return false;
        }

        private static Object findZeroDivider(Class<?> type) throws IllegalAccessException {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !type.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object candidate = field.get(null);
                if (candidate != null && dividerHeight(candidate) == 0) {
                    return candidate;
                }
            }
            return null;
        }

        void invalidate() throws Exception {
            invalidateItemDecorations.invoke(parent);
        }

        void append(DecorationState additional) {
            if (additional.parent == parent) {
                entries.addAll(additional.entries);
            }
        }

        void restore() {
            for (DecorationCacheEntry entry : entries) {
                entry.restore();
            }
            try {
                invalidateItemDecorations.invoke(parent);
            } catch (Throwable ignored) {
                parent.requestLayout();
            }
        }
    }

    static final class DecorationCacheEntry {
        final SparseArray<Object> cache;
        final int position;
        final Object original;
        final Object replacement;

        DecorationCacheEntry(SparseArray<Object> cache, int position,
                             Object original, Object replacement) {
            this.cache = cache;
            this.position = position;
            this.original = original;
            this.replacement = replacement;
        }

        void restore() {
            if (ownsReplacement(cache.get(position), replacement)) {
                cache.put(position, original);
            }
        }

        static boolean ownsReplacement(Object current, Object replacement) {
            return current == replacement;
        }
    }

    private static final class ViewState {
        final int visibility;
        final int minimumHeight;
        final int layoutHeight;
        final int topMargin;
        final int bottomMargin;

        ViewState(int visibility, int minimumHeight, int layoutHeight,
                  int topMargin, int bottomMargin) {
            this.visibility = visibility;
            this.minimumHeight = minimumHeight;
            this.layoutHeight = layoutHeight;
            this.topMargin = topMargin;
            this.bottomMargin = bottomMargin;
        }

        static ViewState capture(View view) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            int topMargin = Integer.MIN_VALUE;
            int bottomMargin = Integer.MIN_VALUE;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) params;
                topMargin = margins.topMargin;
                bottomMargin = margins.bottomMargin;
            }
            return new ViewState(view.getVisibility(), view.getMinimumHeight(),
                    params == null ? Integer.MIN_VALUE : params.height,
                    topMargin, bottomMargin);
        }

        void restore(View view) {
            view.setVisibility(visibility);
            view.setMinimumHeight(minimumHeight);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            boolean layoutChanged = false;
            if (params != null && layoutHeight != Integer.MIN_VALUE
                    && params.height != layoutHeight) {
                params.height = layoutHeight;
                layoutChanged = true;
            }
            if (params instanceof ViewGroup.MarginLayoutParams
                    && topMargin != Integer.MIN_VALUE
                    && bottomMargin != Integer.MIN_VALUE) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) params;
                if (margins.topMargin != topMargin
                        || margins.bottomMargin != bottomMargin) {
                    margins.topMargin = topMargin;
                    margins.bottomMargin = bottomMargin;
                    layoutChanged = true;
                }
            }
            if (params != null && layoutChanged) {
                view.setLayoutParams(params);
            }
        }
    }
}
