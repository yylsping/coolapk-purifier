package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Read-only observer on the one strictly resolved Coolapk splash decision
 * method. The original method always runs and its result is always returned
 * unchanged (OBSERVE_ONLY); observation feeds diagnostics and the
 * process-local {@link SplashDecisionState} only.
 */
final class SplashDecisionObserver {
    private static final String HOOK_ID = "coolapk-splash-decision";

    private final XposedModule module;
    private final ModuleLog log;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposureLedger;
    private final SplashUiLedger splashUiLedger;
    private final SplashDecisionState decisionState;
    private final AtomicBoolean observationLogged = new AtomicBoolean();
    private Method installedMethod;
    @SuppressWarnings("unused")
    private HookHandle installedHandle;

    SplashDecisionObserver(XposedModule module, ModuleLog log, HookLedger ledger,
                           FeatureExposureLedger exposureLedger,
                           SplashUiLedger splashUiLedger,
                           SplashDecisionState decisionState) {
        this.module = module;
        this.log = log;
        this.ledger = ledger;
        this.exposureLedger = exposureLedger;
        this.splashUiLedger = splashUiLedger;
        this.decisionState = decisionState;
    }

    synchronized boolean install(ResolvedTarget target, ClassLoader loader) {
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
                    .intercept(chain -> SplashDecisionPolicy.intercept(chain::proceed,
                            (original, returned) -> {
                                boolean originalTrue = Boolean.TRUE.equals(original);
                                decisionState.record(originalTrue);
                                if (splashUiLedger != null) {
                                    splashUiLedger.recordDecisionObserved(originalTrue);
                                }
                                if (observationLogged.compareAndSet(false, true)) {
                                    log.info("splashDecision observation original=" + original
                                            + " returned=" + returned
                                            + " overrideApplied=false"
                                            + " mode=" + SplashDecisionPolicy.MODE);
                                }
                            }));
            installedMethod = method;
            ledger.record(HookLedger.Layer.BUSINESS, "splash", HOOK_ID,
                    target.methodDescriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
            }
            log.info("splash decision observer installed mode=" + SplashDecisionPolicy.MODE
                    + " target=" + target.methodDescriptor);
            return true;
        } catch (Throwable failure) {
            log.error("splash decision observer install failed; UI cleaners unaffected", failure);
            return false;
        }
    }

    synchronized boolean isInstalled() {
        return installedMethod != null;
    }
}
