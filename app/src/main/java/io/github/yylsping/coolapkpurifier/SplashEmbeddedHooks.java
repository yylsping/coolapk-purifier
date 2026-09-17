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
 * {@link SplashEmbeddedDispatch}; "signal sent" and "removal observed" are
 * distinct ledger events and are never conflated. The delayed removal check
 * is a local observation only — it is not a host acknowledgement, and a
 * still-added fragment after the observation window (UNCONFIRMED) never
 * triggers an automatic re-send, because an accepted fragment result may
 * still be delivered by the host later.
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
     * exit reason reuses the host-native dismissal payload and introduces
     * no module-specific reason marker.
     */
    private static final String DISMISS_REASON = "sdk_should_go_main";
    private static final String ANDROIDX_FRAGMENT = "androidx.fragment.app.Fragment";
    /** One-shot removal-observation delay; no periodic polling is involved. */
    private static final long OBSERVE_DELAY_MS = 1500L;

    /** Tri-state result of the reflective {@code Fragment.isAdded} probe. */
    enum AddedState {
        ADDED,
        NOT_ADDED,
        /** Reflection failed; the true state is unknown, never assumed. */
        UNKNOWN
    }

    /** Outcome of the delayed local removal observation. */
    enum RemovalOutcome {
        /** Weak ref cleared or fragment provably not added anymore. */
        REMOVAL_OBSERVED,
        /** Still added after the window; signal stays accepted, no retry. */
        UNCONFIRMED,
        /** Probe unreadable; says nothing about the actual UI state. */
        OBSERVATION_UNKNOWN
    }

    private final XposedModule module;
    private final ModuleLog log;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposureLedger;
    private final SplashUiLedger splashUiLedger;
    private final SplashDecisionState decisionState;
    private final SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
    /** Snapshot of the latest dispatch transition; NOT a terminal guarantee. */
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
     * Latest dispatch transition snapshot for diagnostics:
     * NOT_SEEN / SENT / REMOVAL_OBSERVED / UNCONFIRMED / OBSERVATION_UNKNOWN
     * / DISPATCH_FAILED. Point-in-time only; later transitions are reported
     * through {@code embeddedDispatchTransition} log lines.
     */
    String dispatchState() {
        return dispatchState;
    }

    /**
     * Maps the delayed observation inputs to an outcome. UNKNOWN probe
     * results never map to REMOVAL_OBSERVED.
     */
    static RemovalOutcome removalOutcome(boolean referenceCleared, AddedState added) {
        if (referenceCleared || added == AddedState.NOT_ADDED) {
            return RemovalOutcome.REMOVAL_OBSERVED;
        }
        if (added == AddedState.ADDED) {
            return RemovalOutcome.UNCONFIRMED;
        }
        return RemovalOutcome.OBSERVATION_UNKNOWN;
    }

    /**
     * Tri-state reflective {@code isAdded} probe. Any reflection failure is
     * UNKNOWN — callers must fail closed, never assume removal.
     */
    static AddedState probeAdded(Object fragment) {
        try {
            Object added = fragment.getClass().getMethod("isAdded").invoke(fragment);
            return Boolean.TRUE.equals(added) ? AddedState.ADDED : AddedState.NOT_ADDED;
        } catch (Throwable unknown) {
            return AddedState.UNKNOWN;
        }
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
        // UNKNOWN fails closed: only a provably added fragment is suppressed.
        if (!SplashEmbeddedPolicy.shouldSuppress(enabledNow,
                probeAdded(fragment) == AddedState.ADDED)) {
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
        AddedState added = probeAdded(fragment);
        if (added != AddedState.ADDED) {
            // Host already removed the fragment on its own (or the state is
            // unreadable); drop the claim without sending anything.
            dispatch.clearPending(fragment);
            log.info("embedded splash suppress skipped reason="
                    + (added == AddedState.NOT_ADDED ? "noLongerAdded" : "addedStateUnknown"));
            return;
        }
        try {
            Object fragmentManager = fragment.getClass()
                    .getMethod("getParentFragmentManager").invoke(fragment);
            if (fragmentManager == null) {
                dispatch.clearPending(fragment);
                log.info("embedded splash suppress skipped reason=noFragmentManager");
                return;
            }
            Bundle result = new Bundle();
            result.putString(EXTRA_FINISH_REASON, DISMISS_REASON);
            fragmentManager.getClass()
                    .getMethod("setFragmentResult", String.class, Bundle.class)
                    .invoke(fragmentManager, FRAGMENT_RESULT_KEY, result);
        } catch (Throwable failure) {
            // The finish signal itself could not be submitted.
            dispatch.markDispatchFailed(fragment);
            transitionTo("DISPATCH_FAILED");
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedFinishDispatchFailed();
            }
            log.error("embedded finish dispatch failed coverage=PARTIAL", failure);
            return;
        }
        // The signal was accepted by the FragmentManager. Everything below is
        // diagnostics only and must never reclassify this dispatch.
        dispatch.markSent(fragment);
        try {
            transitionTo("SENT");
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedFinishSignalSent();
            }
            if (exposureLedger != null) {
                exposureLedger.recordModified(PurifierConfig.Feature.SPLASH);
            }
            log.info("embedded finish signal sent payloadSource=HOST_NATIVE"
                    + correlationSuffix());
            scheduleRemovalObservation(fragment);
        } catch (Throwable diagnosticsFailure) {
            log.error("embedded post-dispatch diagnostics failed; dispatch unchanged",
                    diagnosticsFailure);
        }
    }

    /**
     * One-shot delayed check whether the embedded splash fragment is gone.
     * This is the module's own local observation, never a host-side
     * acknowledgement. A cleared weak reference means the fragment is
     * already unreachable, which is only possible after removal, so it also
     * counts as observed removal. A still-added fragment becomes UNCONFIRMED
     * and is NOT retried: the accepted result may still be delivered later.
     * An unreadable probe is OBSERVATION_UNKNOWN and says nothing about the
     * UI. Never reschedules itself.
     */
    private void scheduleRemovalObservation(Object fragment) {
        WeakReference<Object> fragmentRef = new WeakReference<>(fragment);
        handler().postDelayed(() -> {
            Object current = fragmentRef.get();
            RemovalOutcome outcome = removalOutcome(current == null,
                    current == null ? AddedState.UNKNOWN : probeAdded(current));
            if (outcome == RemovalOutcome.REMOVAL_OBSERVED) {
                transitionTo("REMOVAL_OBSERVED");
                if (splashUiLedger != null) {
                    splashUiLedger.recordEmbeddedRemovalObserved();
                }
                log.info("embedded removal observed after finish signal");
                return;
            }
            if (outcome == RemovalOutcome.UNCONFIRMED) {
                dispatch.markUnconfirmed(current);
                transitionTo("UNCONFIRMED");
                if (splashUiLedger != null) {
                    splashUiLedger.recordEmbeddedFinishUnconfirmed();
                }
                log.info("embedded finish unconfirmed after " + OBSERVE_DELAY_MS
                        + "ms; signal already sent, no automatic retry");
                return;
            }
            transitionTo("OBSERVATION_UNKNOWN");
            if (splashUiLedger != null) {
                splashUiLedger.recordEmbeddedRemovalObservationFailed();
            }
            log.info("embedded removal observation failed; isAdded probe unreadable,"
                    + " dispatch unchanged, no automatic retry");
        }, OBSERVE_DELAY_MS);
    }

    private void transitionTo(String next) {
        String from = dispatchState;
        dispatchState = next;
        log.info("embeddedDispatchTransition from=" + from + " to=" + next);
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
