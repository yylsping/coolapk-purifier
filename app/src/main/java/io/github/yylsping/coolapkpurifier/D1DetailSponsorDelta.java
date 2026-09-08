package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D1 diagnostic candidate: literal 2.1.2 plus one version-pinned business delta.
 */
final class D1DetailSponsorDelta {
    static final long HOST_VERSION_CODE = 2_608_212L;
    static final String TARGET_CLASS = "com.coolapk.market.model.$$AutoValue_Feed";
    static final String TARGET_METHOD = "getDetailSponsorCard";
    static final String TARGET_RETURN_CLASS = "com.coolapk.market.model.Entity";
    static final String TARGET_DESCRIPTOR =
            "Lcom/coolapk/market/model/$$AutoValue_Feed;->"
                    + "getDetailSponsorCard()Lcom/coolapk/market/model/Entity;";

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final AtomicBoolean interceptorEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedNonnullLogged = new AtomicBoolean();
    private final AtomicBoolean resultReplacedNullLogged = new AtomicBoolean();

    // A HookHandle must remain strongly reachable for the process lifetime.
    private volatile HookHandle handle;

    D1DetailSponsorDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this.module = module;
        this.log = log;
        this.gate = gate;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized void install(Context context, ClassLoader loader) {
        if (handle != null) {
            return;
        }
        try {
            long versionCode = hostVersionCode(context);
            if (!isExactHostVersion(versionCode)) {
                log.info("d1_hook_registered=false reason=host_version_mismatch"
                        + " expected=" + HOST_VERSION_CODE + " actual=" + versionCode);
                return;
            }

            Class<?> owner = Class.forName(TARGET_CLASS, false, loader);
            Method target = owner.getDeclaredMethod(TARGET_METHOD);
            if (!isExactTarget(target)) {
                log.info("d1_hook_registered=false reason=exact_target_mismatch"
                        + " descriptor=" + TARGET_DESCRIPTOR);
                return;
            }
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d1-detail-sponsor")
                    .intercept(chain -> {
                        if (interceptorEnteredLogged.compareAndSet(false, true)) {
                            log.info("d1_interceptor_entered=true descriptor="
                                    + TARGET_DESCRIPTOR);
                        }
                        Object original = chain.proceed();
                        if (original != null
                                && hostReturnedNonnullLogged.compareAndSet(false, true)) {
                            log.info("d1_host_returned_nonnull=true descriptor="
                                    + TARGET_DESCRIPTOR);
                        }
                        Object result = applyDelta(gate.isEffectiveEnabled(
                                PurifierConfig.Feature.DETAIL_SPONSOR), original);
                        if (original != null && result == null
                                && resultReplacedNullLogged.compareAndSet(false, true)) {
                            log.info("d1_result_replaced_null=true descriptor="
                                    + TARGET_DESCRIPTOR);
                        }
                        return result;
                    });
            log.info("d1_hook_registered=true source=version_pinned_exact"
                    + " versionCode=" + versionCode
                    + " descriptor=" + TARGET_DESCRIPTOR);
        } catch (Throwable throwable) {
            log.error("d1_hook_registered=false reason=installation_failure"
                    + " descriptor=" + TARGET_DESCRIPTOR, throwable);
        }
    }

    static boolean isExactHostVersion(long versionCode) {
        return versionCode == HOST_VERSION_CODE;
    }

    static boolean isExactTarget(Method method) {
        return method != null
                && TARGET_METHOD.equals(method.getName())
                && TARGET_CLASS.equals(method.getDeclaringClass().getName())
                && method.getParameterTypes().length == 0
                && TARGET_RETURN_CLASS.equals(method.getReturnType().getName())
                && !Modifier.isAbstract(method.getModifiers());
    }

    static Object applyDelta(boolean enabled, Object original) {
        return enabled && original != null ? null : original;
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
