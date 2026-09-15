package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D1 detail-sponsor delta. All version adaptation data (owner class, getter,
 * return type) comes from the validated manifest profile; this class only
 * verifies the contract strictly and installs the behavior delta.
 */
final class D1DetailSponsorDelta {
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;
    private final AtomicBoolean interceptorEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedNonnullLogged = new AtomicBoolean();
    private final AtomicBoolean resultReplacedNullLogged = new AtomicBoolean();

    // A HookHandle must remain strongly reachable for the process lifetime.
    private volatile HookHandle handle;

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
    synchronized InstallResult install(DetailSponsorTargetSpec spec, ClassLoader loader) {
        if (handle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (spec == null) {
            log.info("d1_hook_registered=false reason=target_missing");
            return InstallResult.TARGET_MISSING;
        }
        String descriptor = spec.descriptor();
        try {
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            Method target = owner.getDeclaredMethod(spec.methodName);
            if (!isExactTarget(spec, target)) {
                log.info("d1_hook_registered=false reason=contract_mismatch"
                        + " descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d1-detail-sponsor")
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(
                                    PurifierConfig.Feature.DETAIL_SPONSOR);
                        }
                        if (interceptorEnteredLogged.compareAndSet(false, true)) {
                            log.info("d1_interceptor_entered=true descriptor=" + descriptor);
                        }
                        Object original = chain.proceed();
                        if (original != null
                                && hostReturnedNonnullLogged.compareAndSet(false, true)) {
                            log.info("d1_host_returned_nonnull=true descriptor=" + descriptor);
                        }
                        if (original != null && exposureLedger != null) {
                            exposureLedger.recordSampleSeen(
                                    PurifierConfig.Feature.DETAIL_SPONSOR);
                        }
                        Object result = applyDelta(gate.isEffectiveEnabled(
                                PurifierConfig.Feature.DETAIL_SPONSOR), original);
                        if (original != null && result == null
                                && resultReplacedNullLogged.compareAndSet(false, true)) {
                            log.info("d1_result_replaced_null=true descriptor=" + descriptor);
                        }
                        if (original != null && result == null && exposureLedger != null) {
                            exposureLedger.recordModified(
                                    PurifierConfig.Feature.DETAIL_SPONSOR);
                        }
                        return result;
                    });
            log.info("d1_hook_registered=true source=manifest_exact descriptor=" + descriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.DETAIL_SPONSOR);
            }
            return InstallResult.INSTALLED;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d1_hook_registered=false reason=target_missing"
                    + " descriptor=" + descriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        } catch (Throwable throwable) {
            log.error("d1_hook_registered=false reason=installation_failure"
                    + " descriptor=" + descriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    static boolean isExactTarget(DetailSponsorTargetSpec spec, Method method) {
        return spec != null && method != null
                && spec.methodName.equals(method.getName())
                && spec.ownerClass.equals(method.getDeclaringClass().getName())
                && method.getParameterTypes().length == 0
                && spec.returnType.equals(method.getReturnType().getName())
                && !Modifier.isAbstract(method.getModifiers());
    }

    static Object applyDelta(boolean enabled, Object original) {
        return enabled && original != null ? null : original;
    }
}
