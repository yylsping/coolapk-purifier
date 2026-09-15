package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D6 auto-comment callback suppression, gated by settings. Adaptation data
 * comes from the validated manifest profile.
 */
final class D6AutoCommentDelta {
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;
    private final AtomicBoolean interceptorEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean resultSuppressedLogged = new AtomicBoolean();
    private final AtomicInteger invocationCount = new AtomicInteger();

    // A HookHandle must remain strongly reachable for the process lifetime.
    private volatile HookHandle handle;

    D6AutoCommentDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    D6AutoCommentDelta(XposedModule module, ModuleLog log, FeatureGate gate,
                       FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(AutoCommentTargetSpec spec, ClassLoader loader) {
        if (handle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (spec == null) {
            log.info("d6_hook_registered=false reason=target_missing");
            return InstallResult.TARGET_MISSING;
        }
        String descriptor = spec.descriptor();
        try {
            Class<?>[] parameters = new Class<?>[spec.parameterTypes.size()];
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            for (int i = 0; i < parameters.length; i++) {
                parameters[i] = Class.forName(spec.parameterTypes.get(i), false, loader);
            }
            Method target = owner.getDeclaredMethod(spec.methodName, parameters);
            if (!ExactMethodVerifier.isExactTarget(spec, target)) {
                log.info("d6_hook_registered=false reason=contract_mismatch"
                        + " descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d6-auto-comment")
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(PurifierConfig.Feature.AUTO_COMMENT);
                            exposureLedger.recordSampleSeen(
                                    PurifierConfig.Feature.AUTO_COMMENT);
                        }
                        int invocation = invocationCount.incrementAndGet();
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.AUTO_COMMENT)) {
                            return chain.proceed();
                        }
                        if (interceptorEnteredLogged.compareAndSet(false, true)) {
                            log.info("d6_interceptor_entered=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " descriptor=" + descriptor);
                        }

                        // D6 deliberately suppresses this exact void callback.
                        Object result = suppressedResult();
                        if (exposureLedger != null) {
                            exposureLedger.recordModified(PurifierConfig.Feature.AUTO_COMMENT);
                        }
                        if (resultSuppressedLogged.compareAndSet(false, true)) {
                            log.info("d6_result_suppressed=true"
                                    + " invocation=" + invocation
                                    + " original_skipped=true"
                                    + " result=null"
                                    + " descriptor=" + descriptor);
                        }
                        return result;
                    });
            log.info("d6_hook_registered=true source=manifest_exact"
                    + " descriptor=" + descriptor
                    + " original_skipped=true"
                    + " onlyNewBusinessHook=true");
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.AUTO_COMMENT);
            }
            return InstallResult.INSTALLED;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d6_hook_registered=false reason=target_missing"
                    + " descriptor=" + descriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        } catch (Throwable throwable) {
            log.error("d6_hook_registered=false reason=installation_failure"
                    + " descriptor=" + descriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    static Object suppressedResult() {
        return null;
    }
}
