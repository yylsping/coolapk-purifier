package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** One strictly resolved Coolapk business method; no View or ad-SDK interception. */
final class SplashDecisionHooks {
    private static final String HOOK_ID = "coolapk-splash-decision";

    private final XposedModule module;
    private final ModuleLog log;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposureLedger;
    private final AtomicBoolean observationLogged = new AtomicBoolean();
    private Method installedMethod;
    @SuppressWarnings("unused")
    private HookHandle installedHandle;

    SplashDecisionHooks(XposedModule module, ModuleLog log, HookLedger ledger,
                        FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.ledger = ledger;
        this.exposureLedger = exposureLedger;
    }

    synchronized boolean install(ResolvedTarget target, ClassLoader loader,
                                 BooleanSupplier enabled) {
        if (!SplashDecisionResolver.verify(target, loader)) {
            return false;
        }
        Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
        if (method == null || method.getDeclaringClass().getClassLoader() != loader) {
            return false;
        }
        if (method.equals(installedMethod)) {
            return true;
        }
        try {
            installedHandle = module.hook(method)
                    .setId(HOOK_ID)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(PurifierConfig.Feature.SPLASH);
                        }
                        return SplashDecisionPolicy.intercept(chain::proceed, enabled,
                                (original, returned, suppress) -> {
                                    if (Boolean.TRUE.equals(original) && exposureLedger != null) {
                                        exposureLedger.recordSampleSeen(
                                                PurifierConfig.Feature.SPLASH);
                                        if (Boolean.FALSE.equals(returned)) {
                                            exposureLedger.recordModified(
                                                    PurifierConfig.Feature.SPLASH);
                                        }
                                    }
                                    if (observationLogged.compareAndSet(false, true)) {
                                        log.info("splashDecision firstObservation original="
                                                + original + " returned=" + returned
                                                + " overrideApplied="
                                                + !java.util.Objects.equals(original, returned)
                                                + " enabled=" + suppress
                                                + " mode=POST_RESULT");
                                    }
                                });
                    });
            installedMethod = method;
            ledger.record(HookLedger.Layer.BUSINESS, "splash", HOOK_ID,
                    target.methodDescriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
            }
            log.info("splash decision installed mode=POST_RESULT target="
                    + target.methodDescriptor);
            return true;
        } catch (Throwable failure) {
            log.error("splash decision install failed; Activity protection retained", failure);
            return false;
        }
    }

    synchronized boolean isInstalled() {
        return installedMethod != null;
    }
}
