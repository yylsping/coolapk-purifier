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
    private final List<HookHandle> bootstrapHandles = new ArrayList<>();
    private final List<HookHandle> specificHandles = new ArrayList<>();
    private final Set<Method> hookedSpecific = new HashSet<>();
    private volatile boolean bootstrapCallbacksActive = true;

    SplashHooks(XposedModule module, ModuleLog log, ActivityObserver observer) {
        this.module = module;
        this.log = log;
        this.observer = observer;
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
                    if (activity != null && callbacksActive) {
                        observer.onPreActivityCreate(activity);
                    }
                    Object result = chain.proceed();
                    if (activity != null) {
                        if (callbacksActive) {
                            observer.onPostActivityCreate(activity);
                        }
                        // The splash safety net stays alive for the whole
                        // process lifetime: a later FullScreenAdActivity that
                        // is not part of any resolved hierarchy must still be
                        // finished after the bootstrap subsystem retires.
                        if (observer.shouldFinishSplash(activity)) {
                            finishSplash(activity, "instrumentation");
                        }
                    }
                    return result;
                });
        bootstrapHandles.add(handle);
    }

    /**
     * Retires the coordinator-facing bootstrap callbacks but keeps the
     * Instrumentation hooks installed in a passive mode. Unlike 2.1.0, the
     * hooks are never unhooked: after READY only the cheap splash gate runs,
     * which is the 2.0.1-proven safety net for splash-family activities that
     * appear outside the resolved target hierarchy.
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
                        Object result = chain.proceed();
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof Activity
                                && observer.shouldFinishSplash((Activity) thisObject)) {
                            finishSplash((Activity) thisObject, "specific");
                        }
                        return result;
                    });
            specificHandles.add(handle);
            hookedSpecific.add(onCreate);
            log.info("specific splash hook installed class=" + splashBase.getName()
                    + " method=" + onCreate);
            return true;
        } catch (Throwable throwable) {
            log.error("specific splash hook install failed class=" + splashBase.getName(),
                    throwable);
            return false;
        }
    }

    private void finishSplash(Activity activity, String source) {
        try {
            if (activity.isFinishing()) {
                return;
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
        } catch (Throwable throwable) {
            log.error("unable to finish splash", throwable);
        }
    }
}
