package io.github.yylsping.coolapkpurifier;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * UI-layer cleaner for the embedded MainActivity splash (16.6.x):
 * {@code SplashAdFragment} inside the {@code main_splash_ad} container.
 *
 * <p>The hook runs the fragment's original lifecycle method first. Only
 * after the exact splash UI has genuinely entered its lifecycle does the
 * cleaner emit the host's own fragment-result finish signal — the same
 * dismissal channel and payload the in-app countdown/skip path uses — so
 * MainActivity (or SplashAdActivity) performs its regular cleanup,
 * continuation and fragment removal itself. No upstream business decision
 * is touched; there is no visual-only fallback that could strand the host
 * on a hidden splash frame.
 *
 * <p>Per-instance dedup and outcome tracking live in
 * {@link SplashEmbeddedDispatch}; "signal sent" and "host removal confirmed"
 * are distinct ledger events and are never conflated.
 *
 * <p>androidx types are reached reflectively: the module carries no
 * androidx dependency and must stay agnostic to the host's bundled
 * fragment version.
 */
final class SplashEmbeddedHooks {
    private static final String HOOK_ID = "coolapk-splash-embedded-ui";
    private static final String FRAGMENT_RESULT_KEY = "SplashAd";
    private static final String EXTRA_FINISH_REASON = "FINISH_REASON";
    /**
     * Host-native finish payload, byte-identical to what SplashAdFragment
     * itself emits when its countdown ends ({@code sdk_should_go_main}; the
     * SDK close button uses {@code ad_close}). Decompilation shows both
     * consumers (MainActivity/SplashAdActivity listeners) only log the
     * reason locally — no branching, no reporting — so reusing the normal
     * exit reason keeps the module indistinguishable from a host-initiated
     * dismissal and carries no module-specific marker.
     */
    private static final String DISMISS_REASON = "sdk_should_go_main";
    private static final String ANDROIDX_FRAGMENT = "androidx.fragment.app.Fragment";
    /** One-shot confirmation delay; no periodic polling is involved. */
    private static final long CONFIRM_DELAY_MS = 1500L;

    private final XposedModule module;
    private final ModuleLog log;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposureLedger;
    private final SplashUiLedger splashUiLedger;
    private final SplashDecisionState decisionState;
    private final SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
    private volatile String dispatchState = "NOT_SEEN";
    private volatile Handler mainHandler;
    private Class<?> installedClass;
    @SuppressWarnings("unused")
    private HookHandle installedHandle;

    SplashEmbeddedHooks(XposedModule module, ModuleLog log, HookLedger ledger,
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

    /**
     * Fail-closed install: only the exact fragment class with its own
     * declared {@code onViewCreated} (fallback: {@code onResume}) is
     * hookable. A missing/renamed class or an inherited-only lifecycle
     * leaves embedded coverage PARTIAL instead of hooking a shared base
     * method that would hit every fragment.
     *
     * <p>{@code onViewCreated} is preferred because it still runs when a
     * system dialog keeps the host paused; the host's own finish dispatch
     * pattern proves dismissal is safe from either point.
     */
    synchronized boolean install(ClassLoader loader, BooleanSupplier enabled) {
        if (installedClass != null) {
            return true;
        }
        final Class<?> fragmentType;
        final Method lifecycle;
        final String lifecycleName;
        try {
            fragmentType = Class.forName(SplashEmbeddedPolicy.FRAGMENT_CLASS, false, loader);
            Class<?> androidxFragment = Class.forName(ANDROIDX_FRAGMENT, false, loader);
            if (!androidxFragment.isAssignableFrom(fragmentType)) {
                log.info("embedded splash hook skipped reason=notAFragment class="
                        + fragmentType.getName());
                return false;
            }
            Method onViewCreated = findDeclared(fragmentType, "onViewCreated",
                    "android.view.View", "android.os.Bundle");
            Method onResume = findDeclared(fragmentType, "onResume");
            if (onViewCreated != null) {
                lifecycle = onViewCreated;
                lifecycleName = "onViewCreated";
            } else if (onResume != null) {
                lifecycle = onResume;
                lifecycleName = "onResume";
            } else {
                log.info("embedded splash hook skipped reason=noDeclaredLifecycle");
                return false;
            }
        } catch (Throwable absent) {
            log.info("embedded splash hook skipped reason=classOrLifecycleUnavailable");
            return false;
        }
        try {
            installedHandle = module.hook(lifecycle)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(HOOK_ID)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self != null && self.getClass() == fragmentType) {
                            onExactFragmentUiEntered(self, enabled);
                        }
                        return result;
                    });
            installedClass = fragmentType;
            ledger.record(HookLedger.Layer.BUSINESS, "splash", HOOK_ID,
                    fragmentType.getName() + "#" + lifecycleName);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.SPLASH);
            }
            log.info("embedded splash UI hook installed class=" + fragmentType.getName()
                    + " method=" + lifecycleName + " mode=UI_LAYER");
            return true;
        } catch (Throwable failure) {
            log.error("embedded splash UI hook install failed coverage=PARTIAL", failure);
            return false;
        }
    }

    synchronized boolean isInstalled() {
        return installedClass != null;
    }

    /**
     * Aggregate dispatch outcome for diagnostics:
     * NOT_SEEN / SENT / CONFIRMED / FAILED.
     */
    String dispatchState() {
        return dispatchState;
    }

    private void onExactFragmentUiEntered(Object fragment, BooleanSupplier enabled) {
        if (splashUiLedger != null) {
            splashUiLedger.recordEmbeddedUiEntered();
        }
        if (exposureLedger != null) {
            exposureLedger.recordEntered(PurifierConfig.Feature.SPLASH);
            exposureLedger.recordSampleSeen(PurifierConfig.Feature.SPLASH);
        }
        boolean enabledNow;
        try {
            enabledNow = enabled.getAsBoolean();
        } catch (Throwable unavailable) {
            enabledNow = false;
        }
        if (!SplashEmbeddedPolicy.shouldSuppress(enabledNow, isAdded(fragment))) {
            return;
        }
        // Atomic per-instance claim: a lifecycle re-entry inside the pending
        // window cannot enqueue a second finish for the same instance.
        if (!dispatch.tryMarkPending(fragment)) {
            return;
        }
        // Let the original lifecycle unwind fully before dismissing, mirroring
        // the host's own handler-posted finish dispatch.
        handler().post(() -> suppress(fragment));
    }

    private void suppress(Object fragment) {
        try {
            if (!isAdded(fragment)) {
                dispatch.markRetryable(fragment);
                log.info("embedded splash suppress skipped reason=noLongerAdded");
                return;
            }
            Object fragmentManager = fragment.getClass()
                    .getMethod("getParentFragmentManager").invoke(fragment);
            if (fragmentManager == null) {
                dispatch.markRetryable(fragment);
                log.info("embedded splash suppress skipped reason=noFragmentManager");
                return;
            }
            Bundle result = new Bundle();
            result.putString(EXTRA_FINISH_REASON, DISMISS_REASON);
            fragmentManager.getClass()
                    .getMethod("setFragmentResult", String.class, Bundle.class)
                    .invoke(fragmentManager, FRAGMENT_RESULT_KEY, result);
            dispatch.markSent(fragment);
            dispatchState = "SENT";
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedFinishSignalSent();
            }
            if (exposureLedger != null) {
                exposureLedger.recordModified(PurifierConfig.Feature.SPLASH);
            }
            log.info("embedded finish signal sent payloadSource=HOST_NATIVE"
                    + correlationSuffix());
            scheduleConfirmation(fragment);
        } catch (Throwable failure) {
            dispatch.markRetryable(fragment);
            dispatchState = "FAILED";
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedFinishFailed();
            }
            log.error("embedded finish signal failed coverage=PARTIAL", failure);
        }
    }

    /**
     * One-shot delayed check that the host actually consumed the signal and
     * removed the fragment. A cleared weak reference means the fragment is
     * already unreachable, which is only possible after removal, so it also
     * counts as confirmation. Never reschedules itself.
     */
    private void scheduleConfirmation(Object fragment) {
        WeakReference<Object> fragmentRef = new WeakReference<>(fragment);
        handler().postDelayed(() -> {
            Object current = fragmentRef.get();
            if (current == null || !isAdded(current)) {
                dispatchState = "CONFIRMED";
                if (splashUiLedger != null) {
                    splashUiLedger.recordEmbeddedFinishConfirmed();
                }
                log.info("embedded finish confirmed");
                return;
            }
            dispatch.markRetryable(current);
            dispatchState = "FAILED";
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedFinishFailed();
            }
            log.info("embedded finish unconfirmed; instance eligible for retry");
        }, CONFIRM_DELAY_MS);
    }

    /** Observation correlation is diagnostic only; never a suppress gate. */
    private String correlationSuffix() {
        SplashDecisionState state = decisionState;
        if (state == null || state.lastDecisionObservedAtElapsed() < 0) {
            return " decisionObserved=false";
        }
        return " decisionObserved=true decisionOriginal=" + state.expectedSplash()
                + " decisionAgeMs="
                + Math.max(0L, SystemClock.elapsedRealtime()
                        - state.lastDecisionObservedAtElapsed());
    }

    private static Method findDeclared(Class<?> type, String name, String... paramClassNames) {
        try {
            Class<?>[] params = new Class<?>[paramClassNames.length];
            for (int i = 0; i < paramClassNames.length; i++) {
                params[i] = Class.forName(paramClassNames[i], false, type.getClassLoader());
            }
            return type.getDeclaredMethod(name, params);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static boolean isAdded(Object fragment) {
        try {
            Object added = fragment.getClass().getMethod("isAdded").invoke(fragment);
            return Boolean.TRUE.equals(added);
        } catch (Throwable unknown) {
            return false;
        }
    }

    private Handler handler() {
        Handler handler = mainHandler;
        if (handler == null) {
            synchronized (this) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
                handler = mainHandler;
            }
        }
        return handler;
    }
}
