package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** Exact auto-comment callback suppression, gated by settings. */
final class D6AutoCommentDelta {
    static final long HOST_VERSION_CODE = 2_608_212L;
    static final String TARGET_CLASS =
            "com.coolapk.market.view.cardlist.component."
                    + "RecyclerViewItemFullVisibleControllerKt";
    static final String TARGET_METHOD = "\u037f";
    static final String TARGET_PARAMETER =
            "com.coolapk.market.view.cardlist.EntityListFragment";
    static final String TARGET_DESCRIPTOR =
            "Lcom/coolapk/market/view/cardlist/component/"
                    + "RecyclerViewItemFullVisibleControllerKt;->\u037f("
                    + "Lcom/coolapk/market/view/cardlist/EntityListFragment;)V";

    private static final List<String> TARGET_PARAMETER_NAMES =
            Collections.singletonList(TARGET_PARAMETER);

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final AtomicBoolean interceptorEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean resultSuppressedLogged = new AtomicBoolean();
    private final AtomicInteger invocationCount = new AtomicInteger();

    // A HookHandle must remain strongly reachable for the process lifetime.
    private volatile HookHandle handle;

    D6AutoCommentDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this.module = module;
        this.log = log;
        this.gate = gate;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized void install(Context context, ClassLoader loader) {
        if (handle != null) return;
        try {
            long versionCode = hostVersionCode(context);
            if (!isExactHostVersion(versionCode)) {
                log.info("d6_hook_registered=false reason=host_version_mismatch"
                        + " expected=" + HOST_VERSION_CODE + " actual=" + versionCode);
                return;
            }

            Class<?> owner = Class.forName(TARGET_CLASS, false, loader);
            Class<?> parameter = Class.forName(TARGET_PARAMETER, false, loader);
            Method target = owner.getDeclaredMethod(TARGET_METHOD, parameter);
            if (!isExactTarget(target)) {
                log.info("d6_hook_registered=false reason=exact_target_mismatch"
                        + " descriptor=" + TARGET_DESCRIPTOR);
                return;
            }
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d6-auto-comment")
                    .intercept(chain -> {
                        int invocation = invocationCount.incrementAndGet();
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.AUTO_COMMENT)) {
                            return chain.proceed();
                        }
                        if (interceptorEnteredLogged.compareAndSet(false, true)) {
                            log.info("d6_interceptor_entered=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " descriptor=" + TARGET_DESCRIPTOR);
                        }

                        // D6 deliberately suppresses this exact void callback.
                        Object result = suppressedResult();
                        if (resultSuppressedLogged.compareAndSet(false, true)) {
                            log.info("d6_result_suppressed=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " result=null"
                                    + " descriptor=" + TARGET_DESCRIPTOR);
                        }
                        return result;
                    });
            log.info("d6_hook_registered=true source=version_pinned_exact"
                    + " versionCode=" + versionCode
                    + " descriptor=" + TARGET_DESCRIPTOR
                    + " original_skipped=true"
                    + " onlyNewBusinessHook=true");
        } catch (Throwable throwable) {
            log.error("d6_hook_registered=false reason=installation_failure"
                    + " descriptor=" + TARGET_DESCRIPTOR, throwable);
        }
    }

    static boolean isExactHostVersion(long versionCode) {
        return versionCode == HOST_VERSION_CODE;
    }

    static boolean isExactTarget(Method method) {
        if (method == null) return false;
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != 1) return false;
        return isExactTargetContract(method.getDeclaringClass().getName(), method.getName(),
                Collections.singletonList(parameters[0].getName()),
                method.getReturnType().getName(), method.getModifiers());
    }

    static boolean isExactTargetContract(String ownerName, String methodName,
            List<String> parameterNames, String returnName, int modifiers) {
        return TARGET_CLASS.equals(ownerName)
                && TARGET_METHOD.equals(methodName)
                && TARGET_PARAMETER_NAMES.equals(parameterNames)
                && "void".equals(returnName)
                && Modifier.isStatic(modifiers)
                && !Modifier.isAbstract(modifiers);
    }

    static Object suppressedResult() {
        return null;
    }

    private static long hostVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        //noinspection deprecation
        return info.versionCode;
    }
}
