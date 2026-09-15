package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

final class SplashHooks {
    static final String MAIN_ACTIVITY = "com.coolapk.market.view.main.MainActivity";

    private static final int MAIN_INTENT_FLAGS =
            Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP;

    interface ActivityObserver {
        void onPreActivityCreate(Activity activity);

        void onPostActivityCreate(Activity activity);

        boolean shouldFinishSplash(Activity activity);
    }

    private final XposedModule module;
    private final ModuleLog log;
    private final ActivityObserver observer;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposureLedger;
    private final List<HookHandle> bootstrapHandles = new ArrayList<>();
    private final List<String> bootstrapHookIds = new ArrayList<>();
    private final List<HookHandle> specificHandles = new ArrayList<>();
    private final Set<Method> hookedSpecific = new HashSet<>();
    private volatile boolean bootstrapCallbacksActive = true;

    SplashHooks(XposedModule module, ModuleLog log, ActivityObserver observer,
                HookLedger ledger, FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.observer = observer;
        this.ledger = ledger;
        this.exposureLedger = exposureLedger;
    }

    void installInstrumentationFallback() throws ReflectiveOperationException {
        Method regular = Instrumentation.class.getDeclaredMethod(
                "callActivityOnCreate", Activity.class, Bundle.class);
        Method persistent = Instrumentation.class.getDeclaredMethod(
                "callActivityOnCreate", Activity.class, Bundle.class, PersistableBundle.class);
        installInstrumentationMethod(regular, "coolapk-activity-create-2");
        installInstrumentationMethod(persistent, "coolapk-activity-create-3");
        log.info("Instrumentation bootstrap hooks installed (pre/post split)");
    }

    private void installInstrumentationMethod(Method method, String id) {
        HookHandle handle = module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept(chain -> {
                    Object candidate = chain.getArg(0);
                    Activity activity = candidate instanceof Activity ? (Activity) candidate : null;
                    boolean callbacksActive = bootstrapCallbacksActive;
                    if (activity != null && exposureLedger != null) {
                        exposureLedger.recordEntered(PurifierConfig.Feature.SPLASH);
                    }
                    if (activity != null && callbacksActive) {
                        observer.onPreActivityCreate(activity);
                    }
                    Object result = chain.proceed();
                    if (activity != null) {
                        if (callbacksActive) {
                            observer.onPostActivityCreate(activity);
                        }
                        // While the safety net lives, a later FullScreenAdActivity
                        // that is not part of any resolved hierarchy must still
                        // be finished.
                        if (observer.shouldFinishSplash(activity)) {
                            recordSplashSample();
                            if (finishSplash(activity, "instrumentation")) {
                                recordSplashModified();
                            }
                        }
                    }
                    return result;
                });
        bootstrapHandles.add(handle);
        bootstrapHookIds.add(id);
        if (ledger != null) {
            ledger.record(HookLedger.Layer.FRAMEWORK, "splash", id,
                    "Instrumentation.callActivityOnCreate");
        }
        if (exposureLedger != null) {
            exposureLedger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
        }
    }

    /**
     * BUG-F: genuinely unhooks the generic Instrumentation bootstrap hooks.
     * Only invoked once specific splash coverage (or a splash-free startup
     * topology) makes the broad framework hook unnecessary. DEGRADED starts
     * without specific splash coverage keep the passive net instead.
     *
     * <p>Release-hardening §4: callbacks go logically inert first; each handle
     * is retired in the ledger only after its unhook actually succeeded. A
     * failed unhook keeps the handle retained, keeps the ledger entry active
     * and is logged with the remaining count.
     */
    synchronized void unhookBootstrap(String reason) {
        bootstrapCallbacksActive = false;
        List<HookHandle> retained = new ArrayList<>();
        List<String> retainedIds = new ArrayList<>();
        for (int i = 0; i < bootstrapHandles.size(); i++) {
            String id = i < bootstrapHookIds.size() ? bootstrapHookIds.get(i) : null;
            try {
                bootstrapHandles.get(i).unhook();
                if (id != null && ledger != null) {
                    ledger.retire(id, reason);
                }
            } catch (Throwable failure) {
                retained.add(bootstrapHandles.get(i));
                if (id != null) {
                    retainedIds.add(id);
                }
                log.error("instrumentation bootstrap hook unhook failed id=" + id, failure);
            }
        }
        bootstrapHandles.clear();
        bootstrapHandles.addAll(retained);
        bootstrapHookIds.clear();
        bootstrapHookIds.addAll(retainedIds);
        if (retained.isEmpty()) {
            log.info("instrumentation bootstrap hooks unhooked reason=" + reason
                    + " frameworkHooksRetired=true");
        } else {
            log.info("instrumentation bootstrap hooks partially retained reason=" + reason
                    + " remaining=" + retainedIds + " frameworkHooksRetired=false");
        }
    }

    boolean isBootstrapInstalled() {
        synchronized (this) {
            return !bootstrapHandles.isEmpty();
        }
    }

    /**
     * Retires the coordinator-facing bootstrap callbacks but keeps the
     * Instrumentation hooks installed in a passive mode. Used only for the
     * deliberate DEGRADED splash safety fallback.
     */
    synchronized void retireBootstrapCallbacks() {
        bootstrapCallbacksActive = false;
        log.info("instrumentation bootstrap callbacks retired hooksRetained=true");
    }

    synchronized boolean installSpecific(Class<?> splashBase) {
        Method onCreate = TargetVerifier.findOnCreate(splashBase);
        if (onCreate == null) {
            log.info("specific splash hook skipped class=" + splashBase.getName()
                    + " reason=noCoolapkOnCreate frameworkFallback=true");
            return false;
        }
        if (hookedSpecific.contains(onCreate)) {
            log.info("specific splash hook already installed method=" + onCreate);
            return true;
        }
        try {
            HookHandle handle = module.hook(onCreate)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-specific-splash")
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(PurifierConfig.Feature.SPLASH);
                        }
                        Object result = chain.proceed();
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof Activity
                                && observer.shouldFinishSplash((Activity) thisObject)) {
                            recordSplashSample();
                            if (finishSplash((Activity) thisObject, "specific")) {
                                recordSplashModified();
                            }
                        }
                        return result;
                    });
            specificHandles.add(handle);
            hookedSpecific.add(onCreate);
            if (ledger != null) {
                // §7: specific splash hooks are business hooks and auditable.
                ledger.record(HookLedger.Layer.BUSINESS, "splash",
                        "coolapk-specific-splash:" + splashBase.getName(),
                        onCreate.toString());
            }
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
            }
            log.info("specific splash hook installed class=" + splashBase.getName()
                    + " method=" + onCreate);
            return true;
        } catch (Throwable throwable) {
            log.error("specific splash hook install failed class=" + splashBase.getName(),
                    throwable);
            return false;
        }
    }

    private boolean finishSplash(Activity activity, String source) {
        try {
            if (activity.isFinishing()) {
                return false;
            }
            String className = activity.getClass().getName();
            if (activity.isTaskRoot()) {
                Intent intent = new Intent();
                intent.setClassName(CoolapkModule.TARGET_PACKAGE, MAIN_ACTIVITY);
                intent.addFlags(MAIN_INTENT_FLAGS);
                activity.startActivity(intent);
            }
            activity.finish();
            log.info("finished splash via " + source + ": " + className);
            return true;
        } catch (Throwable throwable) {
            log.error("unable to finish splash", throwable);
            return false;
        }
    }

    private void recordSplashSample() {
        if (exposureLedger != null) {
            exposureLedger.recordSampleSeen(PurifierConfig.Feature.SPLASH);
        }
    }

    private void recordSplashModified() {
        if (exposureLedger != null) {
            exposureLedger.recordModified(PurifierConfig.Feature.SPLASH);
        }
    }
}
