package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** Exact topic/device target-row assembler suppression, gated by settings. */
final class D5TopicDeviceRecommendDelta {
    static final long HOST_VERSION_CODE = 2_608_212L;
    static final String TARGET_CLASS = "d14";
    static final String TARGET_METHOD = "\u0793";
    static final String FEED_PARAMETER = "com.coolapk.market.model.Feed";
    static final String COMPOSER_PARAMETER = "androidx.compose.runtime.Composer";
    static final String UNIT_RETURN = "kotlin.Unit";
    static final String TARGET_DESCRIPTOR =
            "Ld14;->\u0793(Lcom/coolapk/market/model/Feed;Ld14;"
                    + "Landroidx/compose/runtime/Composer;I)Lkotlin/Unit;";

    private static final List<String> TARGET_PARAMETER_NAMES = Arrays.asList(
            FEED_PARAMETER, TARGET_CLASS, COMPOSER_PARAMETER, "int");

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final AtomicBoolean interceptorEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean resultSuppressedLogged = new AtomicBoolean();
    private final AtomicInteger invocationCount = new AtomicInteger();

    // A HookHandle must remain strongly reachable for the process lifetime.
    private volatile HookHandle handle;

    D5TopicDeviceRecommendDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
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
                log.info("d5_hook_registered=false reason=host_version_mismatch"
                        + " expected=" + HOST_VERSION_CODE + " actual=" + versionCode);
                return;
            }

            Class<?> owner = Class.forName(TARGET_CLASS, false, loader);
            Class<?> feed = Class.forName(FEED_PARAMETER, false, loader);
            Class<?> composer = Class.forName(COMPOSER_PARAMETER, false, loader);
            Method target = owner.getDeclaredMethod(TARGET_METHOD,
                    feed, owner, composer, int.class);
            if (!isExactTarget(target)) {
                log.info("d5_hook_registered=false reason=exact_target_mismatch"
                        + " descriptor=" + TARGET_DESCRIPTOR);
                return;
            }
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d5-topic-device-recommend")
                    .intercept(chain -> {
                        int invocation = invocationCount.incrementAndGet();
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND)) {
                            return chain.proceed();
                        }
                        if (interceptorEnteredLogged.compareAndSet(false, true)) {
                            log.info("d5_interceptor_entered=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " descriptor=" + TARGET_DESCRIPTOR);
                        }

                        // D5 deliberately suppresses this exact Kotlin Unit assembler.
                        Object result = suppressedResult();
                        if (resultSuppressedLogged.compareAndSet(false, true)) {
                            log.info("d5_result_suppressed=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " result=null"
                                    + " descriptor=" + TARGET_DESCRIPTOR);
                        }
                        return result;
                    });
            log.info("d5_hook_registered=true source=version_pinned_exact"
                    + " versionCode=" + versionCode
                    + " descriptor=" + TARGET_DESCRIPTOR
                    + " original_skipped=true"
                    + " onlyNewBusinessHook=true");
        } catch (Throwable throwable) {
            log.error("d5_hook_registered=false reason=installation_failure"
                    + " descriptor=" + TARGET_DESCRIPTOR, throwable);
        }
    }

    static boolean isExactHostVersion(long versionCode) {
        return versionCode == HOST_VERSION_CODE;
    }

    static boolean isExactTarget(Method method) {
        if (method == null) return false;
        Class<?>[] parameters = method.getParameterTypes();
        String[] parameterNames = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            parameterNames[i] = parameters[i].getName();
        }
        return isExactTargetContract(method.getDeclaringClass().getName(), method.getName(),
                Arrays.asList(parameterNames), method.getReturnType().getName(),
                method.getModifiers());
    }

    static boolean isExactTargetContract(String ownerName, String methodName,
            List<String> parameterNames, String returnName, int modifiers) {
        return TARGET_CLASS.equals(ownerName)
                && TARGET_METHOD.equals(methodName)
                && TARGET_PARAMETER_NAMES.equals(parameterNames)
                && UNIT_RETURN.equals(returnName)
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
