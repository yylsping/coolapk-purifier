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

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D2 reply-sponsor replacement. The complete host binder always runs and the
 * exact, case-sensitive reply-sponsor template is required before the
 * optional View policy can run. All adaptation data comes from the validated
 * manifest profile; structural verification stays exactly as strict as the
 * version-pinned implementation it replaces.
 */
final class D2ReplySponsorReplacement {
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;
    private final AtomicBoolean enteredLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedLogged = new AtomicBoolean();
    private final AtomicBoolean templateConfirmedLogged = new AtomicBoolean();
    private final AtomicBoolean mutationLogged = new AtomicBoolean();
    private volatile HookHandle handle;

    D2ReplySponsorReplacement(XposedModule module, ModuleLog log,
                              FeatureGate gate) {
        this(module, log, gate, null);
    }

    D2ReplySponsorReplacement(XposedModule module, ModuleLog log,
                              FeatureGate gate, FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(ReplySponsorTargetSpec spec, ClassLoader loader) {
        if (handle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (spec == null) {
            log.info("d2r_hook_registered=false reason=target_missing");
            return InstallResult.TARGET_MISSING;
        }
        String descriptor = spec.descriptor();
        try {
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            Method target = owner.getDeclaredMethod(spec.methodName, Object.class);
            if (!isExactTarget(spec, target, loader)) {
                log.info("d2r_hook_registered=false reason=contract_mismatch"
                        + " descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            Class<?> entityClass = Class.forName(spec.entityClass, false, loader);
            Method templateGetter = entityClass.getMethod(spec.entityTemplateGetter);
            if (templateGetter.getParameterCount() != 0
                    || templateGetter.getReturnType() != String.class) {
                log.info("d2r_hook_registered=false reason=contract_mismatch"
                        + " detail=entity_template_getter descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            templateGetter.setAccessible(true);
            target.setAccessible(true);
            HolderController controller = new HolderController(owner.getField("itemView"));
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d2r-reply-sponsor")
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(PurifierConfig.Feature.REPLY_SPONSOR);
                        }
                        boolean viewMutationEnabled = gate.isEffectiveEnabled(
                                PurifierConfig.Feature.REPLY_SPONSOR);
                        if (enteredLogged.compareAndSet(false, true)) {
                            log.info("d2r_interceptor_entered=true descriptor=" + descriptor
                                    + " viewMutationEnabled=" + viewMutationEnabled);
                        }
                        Object holder = chain.getThisObject();
                        if (viewMutationEnabled) {
                            try {
                                controller.update(holder, false);
                            } catch (Throwable restoreFailure) {
                                log.error("d2r recycled holder restore failed;"
                                        + " continuing host binder", restoreFailure);
                            }
                        }
                        Object original = chain.proceed();
                        if (hostReturnedLogged.compareAndSet(false, true)) {
                            log.info("d2r_host_binder_returned=true descriptor=" + descriptor
                                    + " viewMutationEnabled=" + viewMutationEnabled);
                        }
                        try {
                            boolean exact = isExactReplySponsorEntity(spec,
                                    chain.getArg(0), entityClass, templateGetter);
                            if (exact) {
                                if (exposureLedger != null) {
                                    exposureLedger.recordSampleSeen(
                                            PurifierConfig.Feature.REPLY_SPONSOR);
                                }
                                if (templateConfirmedLogged.compareAndSet(false, true)) {
                                    log.info("d2r_exact_template_confirmed=true template="
                                            + spec.entityTemplate + " descriptor=" + descriptor
                                            + " viewMutationEnabled=" + viewMutationEnabled);
                                }
                                if (shouldApplyViewMutation(viewMutationEnabled, exact)) {
                                    controller.update(holder, true);
                                    if (exposureLedger != null) {
                                        exposureLedger.recordModified(
                                                PurifierConfig.Feature.REPLY_SPONSOR);
                                    }
                                    if (mutationLogged.compareAndSet(false, true)) {
                                        log.info("d2r_view_mutation_applied=true"
                                                + " visibility=gone minimumHeight=0"
                                                + " layoutHeight=0 descriptor=" + descriptor);
                                    }
                                }
                            }
                        } catch (Throwable throwable) {
                            if (viewMutationEnabled) {
                                try {
                                    controller.update(holder, false);
                                } catch (Throwable restoreFailure) {
                                    throwable.addSuppressed(restoreFailure);
                                }
                            }
                            log.error("d2r decision failed; preserving holder state", throwable);
                        }
                        return original;
                    });
            log.info("d2r_hook_registered=true source=manifest_exact"
                    + " layout=R.layout.item_reply_self_draw"
                    + " carveOut=" + spec.entityTemplate
                    + " descriptor=" + descriptor
                    + " viewMutationEnabled=runtime_gated");
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.REPLY_SPONSOR);
            }
            return InstallResult.INSTALLED;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d2r_hook_registered=false reason=target_missing"
                    + " descriptor=" + descriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        } catch (Throwable throwable) {
            log.error("d2r_hook_registered=false reason=installation_failure"
                    + " descriptor=" + descriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    static boolean isExactTarget(ReplySponsorTargetSpec spec, Method method,
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
            if (!Modifier.isPublic(owner.getDeclaredConstructor(
                    View.class, helper, component).getModifiers())) {
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

    static boolean isExactReplySponsorEntity(ReplySponsorTargetSpec spec, Object entity,
                                             Class<?> entityClass, Method templateGetter)
            throws ReflectiveOperationException {
        return spec != null && entity != null && entityClass != null && templateGetter != null
                && entityClass.isInstance(entity)
                && spec.entityTemplate.equals(templateGetter.invoke(entity));
    }

    static boolean shouldApplyViewMutation(boolean enabled, boolean exactTemplate) {
        return enabled && exactTemplate;
    }

    private static final class HolderController {
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
