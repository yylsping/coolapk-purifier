package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
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
 * D2 replacement experiment shared by D2R0 and D2R1.
 *
 * The view-mutation policy is now decided at runtime through the restored
 * settings gate ({@link PurifierConfig.Feature#REPLY_SPONSOR}). The complete
 * host binder always runs and the exact, case-sensitive reply-sponsor template
 * is required before any optional View policy can run.
 */
final class D2ReplySponsorReplacement {
    static final long HOST_VERSION_CODE = 2_608_212L;
    static final String TARGET_CLASS = "fn4";
    static final String TARGET_METHOD = "\u0788";
    static final String TARGET_DESCRIPTOR = "Lfn4;->\u0788(Ljava/lang/Object;)V";
    static final String LAYOUT_FIELD = "\u0789";
    static final String TEMPLATE = EntityClassifier.REPLY_SPONSOR_CARVE_OUT;
    static final String ENTITY = "com.coolapk.market.model.Entity";
    static final String ENTITY_TEMPLATE_GETTER = "getEntityTemplate";
    static final String AD_HELPER = "com.coolapk.market.view.ad.EntityAdHelper";
    static final String BINDING_COMPONENT = "androidx.databinding.DataBindingComponent";
    static final String VIEW_HOLDER = "androidx.recyclerview.widget.RecyclerView$ViewHolder";

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final AtomicBoolean enteredLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedLogged = new AtomicBoolean();
    private final AtomicBoolean templateConfirmedLogged = new AtomicBoolean();
    private final AtomicBoolean mutationLogged = new AtomicBoolean();
    private volatile HookHandle handle;

    D2ReplySponsorReplacement(XposedModule module, ModuleLog log,
                              FeatureGate gate) {
        this.module = module;
        this.log = log;
        this.gate = gate;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized void install(Context context, ClassLoader loader) {
        if (handle != null) return;
        try {
            long versionCode = hostVersionCode(context);
            if (versionCode != HOST_VERSION_CODE) {
                log.info("d2r_hook_registered=false reason=host_version_mismatch"
                        + " expected=" + HOST_VERSION_CODE + " actual=" + versionCode);
                return;
            }
            Class<?> owner = Class.forName(TARGET_CLASS, false, loader);
            Method target = owner.getDeclaredMethod(TARGET_METHOD, Object.class);
            if (!isExactTarget(target, loader)) {
                log.info("d2r_hook_registered=false reason=exact_target_mismatch"
                        + " descriptor=" + TARGET_DESCRIPTOR);
                return;
            }
            Class<?> entityClass = Class.forName(ENTITY, false, loader);
            Method templateGetter = entityClass.getMethod(ENTITY_TEMPLATE_GETTER);
            if (templateGetter.getParameterCount() != 0
                    || templateGetter.getReturnType() != String.class) {
                throw new NoSuchMethodException("entity template getter mismatch");
            }
            templateGetter.setAccessible(true);
            target.setAccessible(true);
            HolderController controller = new HolderController(owner.getField("itemView"));
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d2r-reply-sponsor")
                    .intercept(chain -> {
                        boolean viewMutationEnabled = gate.isEffectiveEnabled(
                                PurifierConfig.Feature.REPLY_SPONSOR);
                        if (enteredLogged.compareAndSet(false, true)) {
                            log.info("d2r_interceptor_entered=true descriptor="
                                    + TARGET_DESCRIPTOR
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
                            log.info("d2r_host_binder_returned=true descriptor="
                                    + TARGET_DESCRIPTOR
                                    + " viewMutationEnabled=" + viewMutationEnabled);
                        }
                        try {
                            boolean exact = isExactReplySponsorEntity(
                                    chain.getArg(0), entityClass, templateGetter);
                            if (exact) {
                                if (templateConfirmedLogged.compareAndSet(false, true)) {
                                    log.info("d2r_exact_template_confirmed=true template="
                                            + TEMPLATE + " descriptor=" + TARGET_DESCRIPTOR
                                            + " viewMutationEnabled=" + viewMutationEnabled);
                                }
                                if (shouldApplyViewMutation(viewMutationEnabled, exact)) {
                                    controller.update(holder, true);
                                    if (mutationLogged.compareAndSet(false, true)) {
                                        log.info("d2r_view_mutation_applied=true"
                                                + " visibility=gone minimumHeight=0"
                                                + " layoutHeight=0 descriptor="
                                                + TARGET_DESCRIPTOR);
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
            log.info("d2r_hook_registered=true source=version_pinned_exact"
                    + " versionCode=" + versionCode
                    + " layout=R.layout.item_reply_self_draw"
                    + " carveOut=" + TEMPLATE
                    + " descriptor=" + TARGET_DESCRIPTOR
                    + " viewMutationEnabled=runtime_gated");
        } catch (Throwable throwable) {
            log.error("d2r_hook_registered=false reason=installation_failure"
                    + " descriptor=" + TARGET_DESCRIPTOR, throwable);
        }
    }

    static boolean isExactTarget(Method method, ClassLoader loader) {
        if (method == null || loader == null
                || !TARGET_METHOD.equals(method.getName())
                || !TARGET_CLASS.equals(method.getDeclaringClass().getName())) return false;
        try {
            Class<?> owner = method.getDeclaringClass();
            int flags = method.getModifiers();
            if (!Modifier.isFinal(owner.getModifiers())
                    || !Modifier.isPublic(flags) || Modifier.isStatic(flags)
                    || method.getReturnType() != void.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != Object.class) return false;
            Method parent = owner.getSuperclass().getDeclaredMethod(TARGET_METHOD, Object.class);
            if (parent.getReturnType() != void.class
                    || !Modifier.isPublic(parent.getModifiers())
                    || !Modifier.isAbstract(parent.getModifiers())) return false;
            Class<?> helper = Class.forName(AD_HELPER, false, loader);
            Class<?> component = Class.forName(BINDING_COMPONENT, false, loader);
            if (!Modifier.isPublic(owner.getDeclaredConstructor(
                    View.class, helper, component).getModifiers())) return false;
            Field itemView = owner.getField("itemView");
            if (itemView.getType() != View.class
                    || !VIEW_HOLDER.equals(itemView.getDeclaringClass().getName())) return false;
            Field layout = owner.getDeclaredField(LAYOUT_FIELD);
            if (layout.getType() != int.class || !Modifier.isStatic(layout.getModifiers())) {
                return false;
            }
            boolean hasEntity = false;
            boolean hasHelper = false;
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                hasEntity |= ENTITY.equals(field.getType().getName());
                hasHelper |= field.getType() == helper;
            }
            return hasEntity && hasHelper;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isExactReplySponsorEntity(Object entity, Class<?> entityClass,
                                              Method templateGetter)
            throws ReflectiveOperationException {
        return entity != null && entityClass != null && templateGetter != null
                && entityClass.isInstance(entity)
                && TEMPLATE.equals(templateGetter.invoke(entity));
    }

    static boolean shouldApplyViewMutation(boolean enabled, boolean exactTemplate) {
        return enabled && exactTemplate;
    }

    private static long hostVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
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
            if (!(candidate instanceof View)) return;
            View itemView = (View) candidate;
            if (!sponsored) {
                ViewState state = collapsed.remove(itemView);
                if (state != null) state.restore(itemView);
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
