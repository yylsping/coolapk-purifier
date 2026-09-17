package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Event-driven bootstrap coordinator.
 *
 * <pre>
 * BOOTSTRAP → WAIT_RUNTIME_DEX → CACHE_VERIFY → SPLASH_CRITICAL
 *           → FULL_RESOLVE → READY
 * failure: DEGRADED
 * </pre>
 *
 * The runtime hook topology is decided once at Application.attach from the
 * persisted feature snapshot plus the validated manifest profile (BUG-G):
 * manifest-managed features disabled at startup never register a hook, the
 * resolver pipeline only starts when an enabled dynamic-trusted feature
 * needs it, and the generic Instrumentation splash fallback exists only
 * while SPLASH is enabled and specific splash coverage is not yet proven.
 *
 * <p>Lifecycle invariants (BUG-D): the attach handoff succeeds exactly once,
 * terminal states are strictly monotonic, and no late callback may submit
 * work to a shut-down executor, recreate trace/cache/session state or
 * reinstall pinned hooks.
 */
final class HookCoordinator implements SplashHooks.ActivityObserver,
        RuntimeDexObserver.Listener {
    private static final long WATCHDOG_DELAY_MILLIS = 8_000L;
    private static final long DEADLINE_MILLIS = 20_000L;
    /**
     * libxposed must leave the Instrumentation interception stack completely
     * before its handle is unhooked. A same-queue immediate post is still too
     * early on cache-hit cold starts on the reference device.
     */
    private static final long BOOTSTRAP_RETIRE_DELAY_MILLIS = 1_000L;
    private static final String WATCHDOG_RETRY_REASON = "watchdog 8s";
    private static final String WATCHDOG_DEADLINE_REASON = "watchdog 20s deadline";
    private static final String ATTACH_HOOK_ID = "coolapk-application-attach";

    private static final String ANCHOR_AD_HELPER_DESCRIPTOR =
            DescriptorUtils.classDescriptorOf(NormalResolver.AD_HELPER_CLASS);
    private static final String ANCHOR_ENTITY_LIST_FRAGMENT_DESCRIPTOR =
            DescriptorUtils.classDescriptorOf(NormalResolver.ENTITY_LIST_FRAGMENT_CLASS);

    private final XposedModule module;
    private final ModuleLog log;
    private final ModuleStorage storage;
    private final ClassLoader primaryLoader;
    private final ApplicationInfo moduleInfo;
    private final FeatureGate featureGate = new FeatureGate();
    private final HookLedger hookLedger = new HookLedger();
    private final FeatureExposureLedger exposureLedger;
    private final SplashUiLedger splashUiLedger;
    private final SplashDecisionState splashDecisionState;
    private final SplashHooks splashHooks;
    private final SplashDecisionObserver splashDecisionObserver;
    private final SplashEmbeddedHooks splashEmbeddedHooks;
    private final EntityListHooks entityListHooks;
    private final SplashGate splashGate = new SplashGate();
    private final RuntimeDexObserver runtimeDexObserver;
    private final D1DetailSponsorDelta d1DetailSponsorDelta;
    private final D2ReplySponsorReplacement d2ReplySponsorReplacement;
    private final D3SameTopicReplacement d3SameTopicReplacement;
    private final D5TopicDeviceRecommendDelta d5TopicDeviceRecommendDelta;
    private final D6AutoCommentDelta d6AutoCommentDelta;
    private final RecoveryController recoveryController;
    private final FirstAdaptationToast firstAdaptationToast;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "pool-resolver-worker"));
    private final Object stateLock = new Object();
    private final AtomicBoolean sessionRunning = new AtomicBoolean();
    private final AtomicBoolean bootstrapRetired = new AtomicBoolean();
    private final AttachHandoff attachHandoff = new AttachHandoff();
    private final OnceFlag firstActivityPreRecorded = new OnceFlag();
    private final OnceFlag firstActivityPostRecorded = new OnceFlag();
    private final List<HookHandle> bootstrapHandles = new java.util.ArrayList<>();
    private final Set<String> installedSplashClasses = ConcurrentHashMap.newKeySet();

    private volatile BootstrapState state = BootstrapState.BOOTSTRAP;
    private volatile Context appContext;
    private volatile BootstrapTrace trace;
    private volatile ResolutionCache cache;
    private volatile TargetIdentity identity;
    private volatile DexKitSession dexKitSession;
    private volatile HookTopology topology;
    private final Map<String, ResolvedTarget> resolvedTargets = new LinkedHashMap<>();
    private volatile ClassLoader activeRuntimeLoader;
    /**
     * True when the previous resolution session ended without full coverage.
     * The next session forces a DexKit bridge rebuild so freshly appended
     * runtime DEX of the same ClassLoader becomes searchable.
     */
    private volatile boolean lastResolutionIncomplete;
    private volatile boolean splashCandidateSeenBeforeReady;
    private volatile boolean splashFinishedByHook;
    private volatile boolean embeddedSplashHost;
    private volatile boolean terminalCleaned;
    /** §10A.6: root cause for a dynamic-feature UNAVAILABLE terminal report. */
    private volatile String dynamicFailureReason;
    private int sessionAttempt;

    HookCoordinator(XposedModule module, ModuleLog log, ClassLoader primaryLoader,
                    ApplicationInfo moduleInfo) {
        this.module = module;
        this.log = log;
        this.storage = ModuleStorage.framework(module, log);
        this.primaryLoader = primaryLoader;
        this.moduleInfo = moduleInfo;
        this.exposureLedger = new FeatureExposureLedger(log::info);
        this.splashUiLedger = new SplashUiLedger(log::info);
        this.splashDecisionState = new SplashDecisionState();
        this.splashHooks = new SplashHooks(
                module, log, this, hookLedger, exposureLedger, splashUiLedger);
        this.splashDecisionObserver = new SplashDecisionObserver(
                module, log, hookLedger, exposureLedger,
                splashUiLedger, splashDecisionState);
        this.splashEmbeddedHooks = new SplashEmbeddedHooks(
                module, log, hookLedger, exposureLedger,
                splashUiLedger, splashDecisionState);
        this.entityListHooks = new EntityListHooks(
                module, log, featureGate, hookLedger, exposureLedger);
        this.runtimeDexObserver = new RuntimeDexObserver(module, log, this, hookLedger);
        this.d1DetailSponsorDelta = new D1DetailSponsorDelta(
                module, log, featureGate, exposureLedger);
        this.d2ReplySponsorReplacement =
                new D2ReplySponsorReplacement(module, log, featureGate, exposureLedger);
        this.d3SameTopicReplacement = new D3SameTopicReplacement(
                module, log, featureGate, exposureLedger);
        this.d5TopicDeviceRecommendDelta =
                new D5TopicDeviceRecommendDelta(module, log, featureGate, exposureLedger);
        this.d6AutoCommentDelta = new D6AutoCommentDelta(
                module, log, featureGate, exposureLedger);
        this.recoveryController = new RecoveryController(log, null, null);
        this.firstAdaptationToast = new FirstAdaptationToast(log);
    }

    void install() throws ReflectiveOperationException {
        markState(BootstrapState.BOOTSTRAP);
        traceAfterContext("packageReady", "loader=" + System.identityHashCode(primaryLoader));

        // Only the attach handoff hook is installed unconditionally. Every
        // optional hook waits for the startup feature snapshot (BUG-G).
        installApplicationAttachHook();

        mainHandler.postDelayed(() -> watchdog(WATCHDOG_RETRY_REASON), WATCHDOG_DELAY_MILLIS);
        mainHandler.postDelayed(() -> watchdog(WATCHDOG_DEADLINE_REASON), DEADLINE_MILLIS);
    }

    // ------------------------------------------------------------------
    // Bootstrap hooks
    // ------------------------------------------------------------------

    private void installApplicationAttachHook() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            HookHandle handle = module.hook(attach)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(ATTACH_HOOK_ID)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object context = chain.getArg(0);
                        if (context instanceof Context) {
                            Object self = chain.getThisObject();
                            onApplicationAttached((Context) context,
                                    self instanceof Application ? (Application) self : null);
                        }
                        return result;
                    });
            bootstrapHandles.add(handle);
            hookLedger.record(HookLedger.Layer.FRAMEWORK, "coordinator",
                    ATTACH_HOOK_ID, "Application.attach");
            traceAfterContext("attachHookInstalled", "before attach");
        } catch (Throwable throwable) {
            log.error("Application.attach bootstrap hook install failed", throwable);
        }
    }

    /**
     * BUG-D: the attach bootstrap hook retires as soon as it is no longer
     * needed. Release-hardening §4: a failed unhook is NOT retired in the
     * ledger — the handle stays active/retained and the failure is logged; the
     * interceptor is already logically inert via the attach handoff claim.
     */
    private void retireAttachHook(String reason) {
        int remaining = 0;
        synchronized (bootstrapHandles) {
            java.util.Iterator<HookHandle> iterator = bootstrapHandles.iterator();
            while (iterator.hasNext()) {
                HookHandle handle = iterator.next();
                try {
                    handle.unhook();
                    iterator.remove();
                } catch (Throwable failure) {
                    remaining++;
                    log.error("framework bootstrap hook unhook failed id="
                            + ATTACH_HOOK_ID, failure);
                }
            }
        }
        if (remaining == 0) {
            if (hookLedger.retire(ATTACH_HOOK_ID, reason)) {
                log.info("framework bootstrap hook retired id=" + ATTACH_HOOK_ID
                        + " reason=" + reason);
            }
        } else {
            log.info("framework bootstrap hook retire deferred id=" + ATTACH_HOOK_ID
                    + " reason=unhookFailure remaining=" + remaining);
        }
    }

    // ------------------------------------------------------------------
    // Configuration / topology
    // ------------------------------------------------------------------

    private final Object configLock = new Object();
    private volatile PurifierConfig config;
    private volatile SettingsHooks settingsHooks;
    private volatile int coolapkMajor;

    /**
     * Loads the persisted switches and installs the settings entry. During
     * Application.attach the base context has no application context yet, so
     * the attach interceptor passes the Application itself; the config half
     * also works from any usable context, and the settings install is retried
     * from the first activity when it could not complete at attach time.
     */
    private void ensureConfiguration(Context context) {
        if (context == null) {
            return;
        }
        if (config == null) {
            synchronized (configLock) {
                if (config == null) {
                    Context candidate = context.getApplicationContext();
                    Context usable = candidate != null ? candidate : context;
                    coolapkMajor = readCoolapkMajor(usable);
                    PurifierConfig loaded = PurifierConfig.load(
                            usable, storage.configStore(), log);
                    featureGate.bind(loaded, coolapkMajor);
                    settingsHooks = new SettingsHooks(
                            module, hookLedger, log, loaded,
                            storage.resolverCacheStore(), coolapkMajor);
                    config = loaded;
                    log.info("configuration initialized coolapkMajor=" + coolapkMajor
                            + " pending=" + loaded.pendingKind()
                            + " revision=" + loaded.revision()
                            + " source=" + loaded.loadedSource());
                    log.info(storage.auditLine());
                }
            }
        }
        SettingsHooks hooks = settingsHooks;
        if (hooks != null && !hooks.isLifecycleCallbacksInstalled()) {
            hooks.install(context);
        }
    }

    private void onApplicationAttached(Context context, Application application) {
        long start = SystemClock.elapsedRealtime();
        // §8 handoff transaction: UNCLAIMED → IN_PROGRESS → COMPLETE/FAILED.
        // Late wrapper/packer attach calls are safe no-ops and never rewrite
        // coordinator state or reset a terminal READY/DEGRADED.
        if (!attachHandoff.claim()) {
            log.info("terminal late callback ignored event=attach state=" + state
                    + " handoff=" + attachHandoff.state());
            return;
        }
        try {
            handoffBody(context, application, start);
            attachHandoff.complete();
        } catch (Throwable failure) {
            // §8: a mid-handoff failure must not leave a permanently claimed,
            // half-initialized process pretending to be fine. The failure is
            // terminal-degraded (never retried, so no duplicate hooks); safe
            // state established so far is kept, no new work is submitted to a
            // shut-down executor (SafeExecutor guards anyway).
            attachHandoff.fail();
            dynamicFailureReason = "attachHandoffFailed";
            log.error("attach handoff failed mid-transaction; degrading handoff="
                    + attachHandoff.state(), failure);
            markState(BootstrapState.DEGRADED);
            cleanupTerminal();
            maybeScheduleBootstrapRetire();
        }
    }

    private void handoffBody(Context context, Application application, long start) {
        Context attached = application != null ? application
                : context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
        appContext = attached;
        storage.attachContext(attached);
        ensureConfiguration(attached);
        embeddedSplashHost = hasEmbeddedSplashResource(attached)
                || SplashDecisionResolver.hasEmbeddedHost(context.getClassLoader());
        log.info("splash embeddedHost=" + embeddedSplashHost
                + " resource=main_splash_ad fragment="
                + SplashDecisionResolver.FRAGMENT);

        // Startup hook topology (BUG-G): persisted feature snapshot plus the
        // validated manifest profile decide what this process installs.
        TargetManifestRepository repository = TargetManifestRepository.load(
                TargetManifestRepository.moduleApkSource(moduleInfo), log);
        long hostVersion = hostVersionCode(attached);
        String hostVersionName = hostVersionName(attached);
        TargetProfile profile = repository == null
                ? null : repository.validatedProfileFor(hostVersion);
        log.info("manifest schema=" + (repository == null ? "unavailable" : repository.schema())
                + " hostVersion=" + hostVersionName + "(" + hostVersion + ")"
                + " profile=" + (repository == null ? "missing"
                : repository.profileStatus(hostVersion)));
        topology = new HookTopology(effectiveSnapshot(), profile != null);

        installManifestFeatures(profile, context.getClassLoader());

        // The embedded UI cleaner depends only on the exact fragment class,
        // never on the resolver pipeline or the decision observer.
        maybeInstallEmbeddedCleaner(context.getClassLoader(), "attach");

        if (topology.needsDynamicBootstrap()) {
            try {
                if (topology.needsInstrumentationFallback()) {
                    splashHooks.installInstrumentationFallback();
                }
                runtimeDexObserver.install();
            } catch (Throwable throwable) {
                log.error("dynamic bootstrap install failed", throwable);
            }
        } else {
            log.info("dynamicBootstrap=skipped reason=noEnabledDynamicFeature");
        }

        trace = new BootstrapTrace(storage.diagnosticSink());
        trace.mark("attachAfter", "context=" + appContext.getPackageName());
        cache = new ResolutionCache(storage.resolverCacheStore());
        recoveryController.attachContext(appContext);
        recoveryController.attachTrace(trace);
        retireAttachHook("handoffComplete");
        log.info("coordinator attachAfter state=" + state
                + " attachElapsedMs=" + (SystemClock.elapsedRealtime() - start));

        if (!topology.needsDynamicBootstrap()) {
            // Nothing to resolve: the process is ready with exactly the
            // manifest/settings hooks its feature snapshot requires.
            finishReady("noEnabledDynamicFeature");
            return;
        }
        markState(BootstrapState.WAIT_RUNTIME_DEX);
        ensureIdentityAsync();
    }

    /**
     * Event-driven install of the embedded splash UI cleaner: attempted at
     * attach and retried from activity-create events (never on a timer) until
     * the exact fragment class is loadable. Independent of the resolver
     * pipeline and of the decision observer.
     */
    private void maybeInstallEmbeddedCleaner(ClassLoader loader, String trigger) {
        HookTopology current = topology;
        if (current == null || !current.needsSplash() || !embeddedSplashHost
                || splashEmbeddedHooks.isInstalled()) {
            return;
        }
        if (loader == null) {
            return;
        }
        boolean installed = splashEmbeddedHooks.install(loader,
                () -> featureGate.isEffectiveEnabled(PurifierConfig.Feature.SPLASH));
        if (installed) {
            log.info("embedded splash UI cleaner installed trigger=" + trigger);
        }
    }

    private Map<PurifierConfig.Feature, Boolean> effectiveSnapshot() {
        EnumMap<PurifierConfig.Feature, Boolean> snapshot =
                new EnumMap<>(PurifierConfig.Feature.class);
        PurifierConfig current = config;
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            snapshot.put(feature, current != null
                    ? current.isEffectiveEnabled(feature, coolapkMajor)
                    : feature.defaultEnabled);
        }
        return snapshot;
    }

    /** Manifest-managed installs: fail-closed, feature-aware, structured. */
    private void installManifestFeatures(TargetProfile profile, ClassLoader loader) {
        installManifestFeature(PurifierConfig.Feature.DETAIL_SPONSOR,
                profile == null ? null : profile.detailSponsor, loader);
        installManifestFeature(PurifierConfig.Feature.REPLY_SPONSOR,
                profile == null ? null : profile.replySponsor, loader);
        installManifestFeature(PurifierConfig.Feature.SAME_TOPIC_FEED,
                profile == null ? null : profile.sameTopic, loader);
        installManifestFeature(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND,
                profile == null ? null : profile.topicDeviceRecommend, loader);
        installManifestFeature(PurifierConfig.Feature.AUTO_COMMENT,
                profile == null ? null : profile.autoComment, loader);
    }

    private void installManifestFeature(PurifierConfig.Feature feature, Object spec,
                                        ClassLoader loader) {
        if (!topology.isEnabledAtStart(feature)) {
            // Recorded as DISABLED by the topology itself; prove it in logs.
            log.info("feature=" + feature.key + " source=manifest_exact"
                    + " install=DISABLED hookInstalled=false");
            return;
        }
        InstallResult result;
        if (spec instanceof DetailSponsorTargetSpec) {
            result = d1DetailSponsorDelta.install((DetailSponsorTargetSpec) spec, loader);
        } else if (spec instanceof ReplySponsorTargetSpec) {
            result = d2ReplySponsorReplacement.install((ReplySponsorTargetSpec) spec, loader);
        } else if (spec instanceof SameTopicTargetSpec) {
            result = d3SameTopicReplacement.install((SameTopicTargetSpec) spec, loader);
        } else if (spec instanceof TopicDeviceTargetSpec) {
            result = d5TopicDeviceRecommendDelta.install((TopicDeviceTargetSpec) spec, loader);
        } else if (spec instanceof AutoCommentTargetSpec) {
            result = d6AutoCommentDelta.install((AutoCommentTargetSpec) spec, loader);
        } else {
            // Enabled, but no validated profile/target for this host version.
            result = topology.installResult(feature) != null
                    ? topology.installResult(feature) : InstallResult.TARGET_MISSING;
            log.info("feature=" + feature.key + " source=manifest_exact"
                    + " install=" + result + " hookInstalled=false");
        }
        topology.recordInstallResult(feature, result);
        if (result == InstallResult.INSTALLED) {
            hookLedger.record(HookLedger.Layer.BUSINESS, feature.key,
                    HookTopology.businessHookId(feature), "manifest_exact");
        }
        log.info("feature=" + feature.key + " source=manifest_exact"
                + " install=" + result
                + " hookInstalled=" + (result == InstallResult.INSTALLED
                || result == InstallResult.ALREADY_INSTALLED));
    }

    // ------------------------------------------------------------------
    // Activity pre/post
    // ------------------------------------------------------------------

    @Override
    public void onPreActivityCreate(Activity activity) {
        if (activity == null) {
            return;
        }
        ensureConfiguration(activity);
        String name = activity.getClass().getName();
        if (firstActivityPreRecorded.tryOnce()) {
            splashGate.markFirstActivity();
            traceAfterContext("firstActivityPre", "class=" + name);
            runtimeDexObserver.notifyFirstActivityPre(activity.getClass().getClassLoader());
        }
        if (SplashHooks.MAIN_ACTIVITY.equals(name)) {
            splashGate.markMainActivity();
            traceAfterContext("mainActivitySeen", "class=" + name);
        }
        if (splashGate.isFallbackSplashCandidate(activity)
                || splashGate.isLegacySplash(activity)) {
            splashCandidateSeenBeforeReady = true;
        }
        ClassLoader activityLoader = activity.getClass().getClassLoader();
        maybeInstallEmbeddedCleaner(activityLoader, "activityPre:" + name);
        if (activeRuntimeLoader != null && activityLoader != activeRuntimeLoader) {
            onRuntimeLoaderChanged(activityLoader, "activityLoaderChanged:" + name);
        }
        if (state != BootstrapState.READY && state != BootstrapState.DEGRADED) {
            triggerSession("activityPre:" + name);
        }
        maybeScheduleBootstrapRetire();
    }

    @Override
    public void onPostActivityCreate(Activity activity) {
        if (activity == null) {
            return;
        }
        if (SplashHooks.MAIN_ACTIVITY.equals(activity.getClass().getName())) {
            splashGate.markMainActivity();
        }
        if (firstActivityPostRecorded.tryOnce()) {
            traceAfterContext("firstActivityPost", "class=" + activity.getClass().getName());
        }
        maybeScheduleBootstrapRetire();
    }

    @Override
    public boolean shouldFinishSplash(Activity activity) {
        boolean finish = featureGate.isEffectiveEnabled(PurifierConfig.Feature.SPLASH)
                && splashGate.shouldFinishSplash(activity);
        if (finish) {
            splashFinishedByHook = true;
        }
        return finish;
    }

    // ------------------------------------------------------------------
    // Runtime dex / loader generation events
    // ------------------------------------------------------------------

    @Override
    public void onRuntimeDexReady(String trigger, ClassLoader runtimeClassLoader) {
        if (state.isTerminal()) {
            // BUG-D: a late runtime-dex event after READY/DEGRADED must not
            // resurrect sessions, traces or the observer.
            log.info("terminal late callback ignored event=runtimeDexReady state=" + state);
            return;
        }
        ClassLoader loader = runtimeClassLoader != null ? runtimeClassLoader : primaryLoader;
        long previous = activeRuntimeLoader == null
                ? -1L : System.identityHashCode(activeRuntimeLoader);
        traceAfterContext("runtimeDexReady", "trigger=" + trigger
                + " runtimeLoaderIdentity=" + System.identityHashCode(loader)
                + " previousLoaderIdentity=" + previous);

        if (appContext == null) {
            appContext = currentApplication();
        }
        if (appContext != null && trace == null) {
            trace = new BootstrapTrace(storage.diagnosticSink());
        }

        boolean loaderChanged = activeRuntimeLoader != null && activeRuntimeLoader != loader;
        if (activeRuntimeLoader == null || loaderChanged) {
            if (loaderChanged) {
                closeSession("runtimeLoaderChanged");
                lastResolutionIncomplete = false;
            }
            activeRuntimeLoader = loader;
            if (dexKitSession == null && trace != null) {
                dexKitSession = new DexKitSession(log, trace, activeRuntimeLoader,
                        sessionContext());
                dexKitSession.notifyLoaderGenerationChanged(
                        loaderChanged ? "runtimeLoaderChanged" : "initial");
            }
        }
        markState(BootstrapState.CACHE_VERIFY);
        log.info("coordinator runtimeDexReady trigger=" + trigger
                + " runtimeLoaderIdentity=" + System.identityHashCode(loader)
                + " previousLoaderIdentity=" + previous
                + " generation=" + (dexKitSession == null ? -1 : dexKitSession.getGeneration()));
        triggerSession("runtimeDex:" + trigger);
    }

    /**
     * Rearms the runtime-dex observer for the next retry, but never after a
     * terminal state: a resolution session that is still running when the 20s
     * deadline terminates the coordinator must not resurrect the closed
     * loadClass hooks (they would then stay installed for the whole process).
     */
    private void rearmObserverForRetry() {
        if (state == BootstrapState.READY || state == BootstrapState.DEGRADED) {
            return;
        }
        runtimeDexObserver.rearm();
    }

    private void onRuntimeLoaderChanged(ClassLoader loader, String reason) {
        long previous = activeRuntimeLoader == null
                ? -1L : System.identityHashCode(activeRuntimeLoader);
        traceAfterContext("runtimeLoaderChanged", "reason=" + reason
                + " runtimeLoaderIdentity=" + System.identityHashCode(loader)
                + " previousLoaderIdentity=" + previous);
        log.info("coordinator runtimeLoaderChanged reason=" + reason
                + " runtimeLoaderIdentity=" + System.identityHashCode(loader)
                + " previousLoaderIdentity=" + previous);
        closeSession(reason);
        lastResolutionIncomplete = false;
        activeRuntimeLoader = loader;
        rearmObserverForRetry();
    }

    // ------------------------------------------------------------------
    // Resolution session
    // ------------------------------------------------------------------

    private void triggerSession(String trigger) {
        if (state == BootstrapState.READY || state == BootstrapState.DEGRADED) {
            return;
        }
        HookTopology current = topology;
        if (current != null && !current.needsDynamicBootstrap()) {
            return;
        }
        if (!sessionRunning.compareAndSet(false, true)) {
            log.info("coordinator session already running trigger=" + trigger);
            return;
        }
        int attempt = ++sessionAttempt;
        traceAfterContext("sessionStart", "trigger=" + trigger + " attempt=" + attempt);
        // BUG-D: a terminal-state cleanup may race a late callback; the
        // shut-down worker must never surface as an exception.
        if (!SafeExecutor.tryExecute(worker, () -> {
            try {
                runSession(trigger, attempt);
            } catch (Throwable throwable) {
                log.error("coordinator resolution session failed", throwable);
                traceAfterContext("sessionError", "trigger=" + trigger
                        + " error=" + throwable
                        + " stack=" + android.util.Log.getStackTraceString(throwable));
                dynamicFailureReason = "sessionError";
                markState(BootstrapState.DEGRADED);
                cleanupTerminal();
            } finally {
                sessionRunning.set(false);
            }
        })) {
            sessionRunning.set(false);
            log.info("terminal late callback ignored event=triggerSession"
                    + " reason=executorShutdown trigger=" + trigger);
        }
    }

    private void runSession(String trigger, int attempt) {
        if (appContext == null) {
            appContext = currentApplication();
            if (appContext == null) {
                log.info("coordinator session skipped context=null trigger=" + trigger);
                return;
            }
        }
        if (trace == null) {
            trace = new BootstrapTrace(storage.diagnosticSink());
        }
        if (cache == null) {
            cache = new ResolutionCache(storage.resolverCacheStore());
            recoveryController.attachContext(appContext);
            recoveryController.attachTrace(trace);
        }
        if (identity == null) {
            long start = SystemClock.elapsedRealtime();
            identity = TargetIdentity.compute(appContext);
            trace.mark("stableIdentityComputed",
                    identity.describe() + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
            log.info("coordinator stable identity: " + identity.describe());
        }

        ResolutionCache.CacheLookup lookup = cache.lookup(identity);
        trace.mark("cacheLookupStart", "attempt=" + attempt + " trigger=" + trigger);
        Map<String, ResolvedTarget> verified = verifyCacheTargets(lookup.targets);
        if (!verified.isEmpty() && !state.isTerminal()) {
            // A deadline/terminal state racing this session must not let stale
            // results install hooks (release-hardening §3.3).
            applyTargets(verified, "cache");
        }
        if (isCoreReady() && isCoverageSettled()) {
            trace.mark("cacheHit", "entries=" + verified.size() + " dexkitScan=false");
            log.info("resolver path=cache hit=true identity=" + identity.shortToken()
                    + " verified=" + verified.size() + " dexkitScan=false state=" + state);
            lastResolutionIncomplete = false;
            finishReady("cache");
            return;
        }
        // Structured miss diagnostics (BUG-C): distinguish "no cache" from
        // "cache rejected" and from "hit but coverage/verification failed".
        String missReason = !lookup.isHit() ? lookup.missReason
                : verified.isEmpty() && !lookup.targets.isEmpty()
                        ? "targetVerifyFailed" : "coverageUnsettled";
        trace.mark("cacheMiss", "reason=" + missReason
                + " verified=" + verified.size() + " total=" + lookup.totalEntries
                + " trigger=" + trigger);
        log.info("resolver path=cache hit=false reason=" + missReason
                + " identity=" + identity.shortToken()
                + " verified=" + verified.size() + " total=" + lookup.totalEntries
                + " trigger=" + trigger);

        DexKitSession session = ensureSession(trigger);
        if (session == null) {
            markState(BootstrapState.WAIT_RUNTIME_DEX);
            lastResolutionIncomplete = true;
            rearmObserverForRetry();
            return;
        }
        if (lastResolutionIncomplete) {
            // The protected app appends runtime DEX to the same ClassLoader;
            // bumping the generation forces the bridge to rescan the current
            // DEX list instead of reusing the stale one.
            session.notifyLoaderGenerationChanged("incompleteRetryRescan");
        }
        if (!session.beginQuery()) {
            log.info("resolver session aborted reason=sessionClosing trigger=" + trigger);
            return;
        }
        try {
            runDexKitTransaction(trigger, session, verified);
        } finally {
            session.endQuery();
        }
    }

    /**
     * The resolver worker's exclusive bridge transaction (release-hardening
     * §3): native queries and the physical bridge close never run
     * concurrently, and results produced after a logical close / loader
     * change / terminal state are discarded instead of installing hooks,
     * saving cache entries or rewriting the state machine.
     */
    private void runDexKitTransaction(String trigger, DexKitSession session,
                                      Map<String, ResolvedTarget> verified) {
        org.luckypray.dexkit.DexKitBridge bridge;
        try {
            bridge = session.ensureBridge(trigger);
        } catch (DexKitNativeLoader.LoadFailure failure) {
            // BUG-E: permanent native bootstrap failure. Sticky for the whole
            // process, classified once; a cache miss can never recover from it
            // in-process, so degrade safely with a single root cause instead
            // of repeating the deterministically failing load every session.
            traceAfterContext("nativeBootstrapPermanentFailure",
                    "trigger=" + trigger + " classification=" + DexKitNativeLoader.FAILURE_REASON);
            log.info("resolver nativeBootstrap=permanentFailure safeDegraded=true"
                    + " trigger=" + trigger + " stage=" + failure.stage);
            dynamicFailureReason = "nativeBootstrapFailed";
            markState(BootstrapState.DEGRADED);
            cleanupTerminal();
            maybeScheduleBootstrapRetire();
            return;
        }
        if (bridge == null || !bridge.isValid()) {
            markState(BootstrapState.WAIT_RUNTIME_DEX);
            log.info("resolver bridge unavailable state=WAIT_RUNTIME_DEX trigger=" + trigger);
            lastResolutionIncomplete = true;
            rearmObserverForRetry();
            return;
        }

        ClassLoader loader = resolveLoader();
        // Only reachable after a cache miss / invalid cache. Cache-hit runs
        // return earlier, so the Toast is never shown on cache hits.
        firstAdaptationToast.showOnce(appContext);
        boolean needsSplash = topology == null || topology.needsSplash();
        boolean needsFeed = topology == null || topology.needsFeed();

        if (needsSplash) {
            markState(BootstrapState.SPLASH_CRITICAL);
            trace.mark("splashResolveStart", "trigger=" + trigger);
            List<ResolvedTarget> splashes =
                    new SplashCriticalResolver(bridge, loader, log).resolve();
            trace.mark("splashResolveEnd", "candidates=" + splashes.size());
            if (!splashes.isEmpty()) {
                Map<String, ResolvedTarget> splashTargets = new LinkedHashMap<>();
                for (int i = 0; i < splashes.size(); i++) {
                    String key = TargetResolver.indexedKey(TargetResolver.KEY_SPLASH_BASE, i);
                    splashTargets.put(key, splashes.get(i).withKey(key));
                }
                if (isSessionUsable(session)) {
                    applyTargets(splashTargets, "dexkit");
                } else {
                    log.info("resolver results discarded reason=sessionInvalidated"
                            + " stage=splash trigger=" + trigger);
                    return;
                }
            } else {
                markState(BootstrapState.WAIT_RUNTIME_DEX);
                rearmObserverForRetry();
                log.info("resolver splash retryable state=WAIT_RUNTIME_DEX"
                        + " reason=zeroOrUnverifiableCandidates trigger=" + trigger);
            }

            if (embeddedSplashHost) {
                ResolvedTarget decision =
                        SplashDecisionResolver.resolve(bridge, loader, log);
                if (decision != null) {
                    Map<String, ResolvedTarget> decisionTarget = new LinkedHashMap<>();
                    decisionTarget.put(TargetResolver.KEY_SPLASH_DECISION, decision);
                    if (isSessionUsable(session)) {
                        applyTargets(decisionTarget, "dexkit-splash-decision");
                    } else {
                        log.info("resolver results discarded reason=sessionInvalidated"
                                + " stage=splashDecision trigger=" + trigger);
                        return;
                    }
                } else {
                    log.info("splash decision unavailable embeddedHost=true coverage=PARTIAL");
                }
            }
        }

        if (needsFeed) {
            markState(BootstrapState.FULL_RESOLVE);
            trace.mark("normalResolveStart", "trigger=" + trigger);
            Map<String, ResolvedTarget> normal =
                    new NormalResolver(bridge, loader, log).resolve();
            trace.mark("normalResolveEnd", "targets=" + normal.keySet());
            if (isSessionUsable(session)) {
                applyTargets(normal, "dexkit");
            } else {
                log.info("resolver results discarded reason=sessionInvalidated"
                        + " stage=feed trigger=" + trigger);
                return;
            }
        }

        if (!isSessionUsable(session)) {
            log.info("resolver results discarded reason=sessionInvalidated"
                    + " stage=save trigger=" + trigger);
            return;
        }
        Map<String, ResolvedTarget> all = TargetApplicability.mergeForSave(
                currentTargets(), verified, topology);
        if (!all.isEmpty()) {
            // Targets of features disabled at start stay in the cache (they
            // were verified but not applicable now) so a later process with a
            // different topology can still hit them.
            cache.saveTargets(identity, all);
            trace.mark("cacheSaved", "entries=" + all.size() + " identity=" + identity.shortToken());
        }

        boolean coreReady = isCoreReady();
        boolean coverageSettled = isCoverageSettled();
        ReadinessPolicy.SessionOutcome outcome =
                ReadinessPolicy.sessionOutcome(coreReady, coverageSettled);
        lastResolutionIncomplete = outcome != ReadinessPolicy.SessionOutcome.READY;
        switch (outcome) {
            case READY:
                finishReady("anchors");
                break;
            case RETRY_COVERAGE:
                // Core filtering already works, but a known feed anchor is not
                // live yet: the shell may still append its DEX. Deterministic
                // retry: the re-armed observer fires on the next business
                // class load, and lastResolutionIncomplete forces the next
                // session to rebuild the DexKit bridge so appended DEX of the
                // same loader becomes visible.
                rearmObserverForRetry();
                markState(BootstrapState.FULL_RESOLVE);
                log.info("resolver coreReady coveragePending state=FULL_RESOLVE"
                        + " adHelperHooked="
                        + entityListHooks.hasHookedInClass(ANCHOR_AD_HELPER_DESCRIPTOR)
                        + " entityListFragmentHooked="
                        + entityListHooks.hasHookedInClass(ANCHOR_ENTITY_LIST_FRAGMENT_DESCRIPTOR)
                        + " trigger=" + trigger);
                break;
            default:
                // Core capability still missing (splash, feed hooks or
                // accessors). Also deterministically retried: observer
                // re-armed here, 8s watchdog retries FULL_RESOLVE too.
                rearmObserverForRetry();
                if (isSplashReady() || !needsSplash) {
                    markState(BootstrapState.FULL_RESOLVE);
                    log.info("resolver splashReady coreIncomplete state=FULL_RESOLVE"
                            + " trigger=" + trigger);
                } else {
                    // A zero-candidate splash is retryable and must not be terminal.
                    log.info("resolver incomplete splash=false core=false"
                            + " state=RETRYABLE trigger=" + trigger);
                }
                break;
        }

        maybeRecoverAfterSplashResolved();
    }

    /**
     * True while this transaction's results may still take effect: the
     * coordinator is not terminal, the session is still the current one and
     * no logical close was requested by another thread.
     */
    private boolean isSessionUsable(DexKitSession session) {
        return !state.isTerminal() && dexKitSession == session
                && !session.isCloseRequested();
    }

    private Map<String, ResolvedTarget> verifyCacheTargets(Map<String, ResolvedTarget> cached) {
        Map<String, ResolvedTarget> verified = new LinkedHashMap<>();
        ClassLoader loader = resolveLoader();
        for (ResolvedTarget target : cached.values()) {
            String problem = TargetVerifier.verify(target, loader);
            if (problem == null) {
                verified.put(target.key, target);
            } else if (isClassNotReady(problem)) {
                log.info("cache target pending key=" + target.key
                        + " reason=" + problem + " state=WAIT_RUNTIME_DEX");
                markState(BootstrapState.WAIT_RUNTIME_DEX);
            } else {
                log.info("cache target invalid key=" + target.key
                        + " reason=" + problem + " currentEntryOnly=true");
                cache.removeTargets(identity);
                return new LinkedHashMap<>();
            }
        }
        return verified;
    }

    private boolean isClassNotReady(String problem) {
        return problem != null && (problem.contains("ClassNotFound")
                || problem.contains("class not loadable"));
    }

    private DexKitSession ensureSession(String trigger) {
        if (trace == null) {
            trace = new BootstrapTrace(storage.diagnosticSink());
        }
        if (dexKitSession == null) {
            dexKitSession = new DexKitSession(log, trace, resolveLoader(), sessionContext());
            dexKitSession.notifyLoaderGenerationChanged("sessionCreated:" + trigger);
        }
        return dexKitSession;
    }

    private Context sessionContext() {
        Context context = appContext;
        return context != null ? context : currentApplication();
    }

    private ClassLoader resolveLoader() {
        ClassLoader loader = activeRuntimeLoader;
        return loader == null ? primaryLoader : loader;
    }

    private void closeSession(String reason) {
        DexKitSession session = dexKitSession;
        if (session != null) {
            // Release-hardening §3: this thread only publishes the logical
            // close; the resolver worker's transaction performs the physical
            // bridge close exactly once when its native queries are done.
            session.requestClose(reason);
            traceAfterContext("bridgeCloseRequested", "reason=" + reason
                    + " generation=" + session.getGeneration());
        }
        dexKitSession = null;
    }

    private void applyTargets(Map<String, ResolvedTarget> targets, String source) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        ClassLoader loader = resolveLoader();
        // Release-hardening §2: a feature disabled at process start never gets
        // its business hooks — from a cache hit exactly as from a fresh DexKit
        // resolution. This filter is the single topology choke point.
        Map<String, ResolvedTarget> applicable = TargetApplicability.filter(targets, topology);
        if (applicable.size() != targets.size()) {
            List<String> dropped = new java.util.ArrayList<>();
            for (String key : targets.keySet()) {
                if (!applicable.containsKey(key)) {
                    dropped.add(key);
                }
            }
            log.info("topology filtered dynamic targets source=" + source
                    + " kept=" + applicable.keySet() + " dropped=" + dropped);
        }
        if (applicable.isEmpty()) {
            return;
        }
        Map<String, ResolvedTarget> merged;
        synchronized (resolvedTargets) {
            // Descriptor-stable merge: an existing descriptor keeps its key,
            // so candidate order changes across sessions can never overwrite
            // an unrelated cached entry.
            TargetResolver.mergeTargets(resolvedTargets, applicable);
            merged = new LinkedHashMap<>(resolvedTargets);
        }
        DynamicTargetApplier.Outcome outcome = DynamicTargetApplier.apply(
                applicable, merged, loader, applierSink, log, source);
        if (entityListHooks.hookedMethodCount() > 0) {
            log.info("installed feed hooks source=" + source
                    + " total=" + entityListHooks.hookedMethodCount());
        }
        for (String className : outcome.splashInstalled) {
            installedSplashClasses.add(className);
            traceAfterContext("splashHookInstalled", "class=" + className
                    + " installed=true source=" + source);
            log.info("installed splash hook source=" + source + " class=" + className);
        }
        for (String className : outcome.splashUnresolved) {
            traceAfterContext("splashHookInstallFailed", "class=" + className
                    + " source=" + source);
        }
        if (outcome.splashDecisionInstalled) {
            traceAfterContext("splashDecisionHookInstalled",
                    "installed=true source=" + source + " mode=" + SplashDecisionPolicy.MODE);
            log.info("installed splash decision observer mode=" + SplashDecisionPolicy.MODE
                    + " source=" + source);
        }
    }

    private final DynamicTargetApplier.Sink applierSink = new DynamicTargetApplier.Sink() {
        @Override
        public void updateAccessors(Map<String, ResolvedTarget> merged, ClassLoader loader) {
            entityListHooks.updateAccessors(merged, loader);
        }

        @Override
        public boolean installFeed(Method method, ResolvedTarget target) {
            return entityListHooks.install(method) > 0;
        }

        @Override
        public void addSplashGateClass(Class<?> type) {
            splashGate.addResolvedSplashClass(type);
        }

        @Override
        public boolean installSplash(Class<?> type, ResolvedTarget target) {
            return splashHooks.installSpecific(type);
        }

        @Override
        public boolean installSplashDecision(Method method, ResolvedTarget target) {
            return splashDecisionObserver.install(target,
                    method.getDeclaringClass().getClassLoader());
        }
    };

    private Map<String, ResolvedTarget> currentTargets() {
        synchronized (resolvedTargets) {
            return new LinkedHashMap<>(resolvedTargets);
        }
    }

    private boolean isSplashReady() {
        // UI-cleaner truth only; the decision observer is diagnostics and
        // never gates readiness.
        return SplashCoveragePolicy.ready(
                !installedSplashClasses.isEmpty(),
                embeddedSplashHost,
                splashEmbeddedHooks.isInstalled());
    }

    /**
     * Core capability, feature-aware (BUG-G): a feature that is disabled at
     * startup never blocks readiness.
     */
    private boolean isCoreReady() {
        HookTopology current = topology;
        boolean splashOk = current == null || !current.needsSplash() || isSplashReady();
        boolean feedOk = current == null || !current.needsFeed()
                || ReadinessPolicy.isCoreReady(true,
                        entityListHooks.hookedMethodCount(),
                        entityListHooks.isAccessorsComplete());
        return splashOk && feedOk;
    }

    /** Feed coverage converged by anchor classes (see {@link ReadinessPolicy}). */
    private boolean isCoverageSettled() {
        HookTopology current = topology;
        if (current != null && !current.needsFeed()) {
            return true;
        }
        return ReadinessPolicy.isCoverageSettledByAnchors(
                entityListHooks.hasHookedInClass(ANCHOR_AD_HELPER_DESCRIPTOR),
                entityListHooks.hasHookedInClass(ANCHOR_ENTITY_LIST_FRAGMENT_DESCRIPTOR));
    }

    private void finishReady(String coverageSource) {
        markState(BootstrapState.READY);
        cleanupTerminal();
        maybeScheduleBootstrapRetire();
        log.info("resolver fullReady state=READY feedInstalled="
                + entityListHooks.hookedMethodCount()
                + " splashInstalled=" + installedSplashClasses
                + " coverageSettledBy=" + coverageSource);
    }

    /**
     * Terminal-point replay of the decision observation semantics. The live
     * first-observation line can be lost to OEM log burst quotas (ColorOS
     * LOG_FLOWCTRL), so the post-terminal summary re-states it from the
     * process-local observation state.
     */
    private void logDecisionObservationSummary(String phase) {
        Boolean original = splashDecisionState.lastDecisionOriginal();
        if (original == null) {
            log.info("splashDecision observationSummary phase=" + phase
                    + " observed=false mode=" + SplashDecisionPolicy.MODE);
            return;
        }
        log.info("splashDecision observationSummary phase=" + phase
                + " original=" + original + " returned=" + original
                + " overrideApplied=false mode=" + SplashDecisionPolicy.MODE
                + " observedAtElapsed=" + splashDecisionState.lastDecisionObservedAtElapsed());
    }

    /**
     * Event-driven retire. No polling. Retirement uses one short main-thread
     * delay so libxposed is outside the Instrumentation interception stack on
     * cache-hit cold starts before the physical unhook runs.
     */
    private void maybeScheduleBootstrapRetire() {
        if (bootstrapRetired.get()) {
            return;
        }
        if (shouldRetireFramework()) {
            mainHandler.postDelayed(this::retireBootstrap,
                    BOOTSTRAP_RETIRE_DELAY_MILLIS);
        }
    }

    private boolean shouldRetireFramework() {
        if (!state.isTerminal()) {
            return false;
        }
        HookTopology current = topology;
        if (current == null) {
            return false;
        }
        if (!current.needsInstrumentationFallback()) {
            // No splash safety net exists in this topology; nothing gates
            // retirement on MainActivity visibility.
            return true;
        }
        return BootstrapRetirePolicy.canRetire(
                state, splashGate.isMainActivitySeen(), isSplashReady());
    }

    private void retireBootstrap() {
        if (!shouldRetireFramework()) {
            return;
        }
        if (!bootstrapRetired.compareAndSet(false, true)) {
            return;
        }
        BootstrapTrace current = trace;
        HookTopology currentTopology = topology;
        try {
            if (current != null) {
                current.freeze("terminalState",
                        "state=" + state
                                + " bootstrapRetireAttempted=true traceFrozen=true"
                                + " elapsedMs=" + current.elapsedSinceStart());
            }
            retireAttachHook("terminalState");
            if (splashHooks.isBootstrapInstalled()) {
                boolean cleanReady = state == BootstrapState.READY;
                boolean splashCovered = currentTopology == null
                        || !currentTopology.needsSplash() || isSplashReady();
                if (cleanReady || splashCovered) {
                    // BUG-F: the broad framework hook genuinely retires once
                    // specific/business coverage makes it unnecessary.
                    splashHooks.unhookBootstrap("coverageSettled:" + state);
                    boolean physicallyRetired = !splashHooks.isBootstrapInstalled();
                    log.info("framework bootstrap hook retirement"
                            + " id=instrumentation-callActivityOnCreate state=" + state
                            + " physicallyRetired=" + physicallyRetired);
                } else {
                    // Deliberate DEGRADED fallback: SPLASH is enabled but no
                    // specific splash coverage could be established.
                    splashHooks.retireBootstrapCallbacks();
                    log.info("framework fallback retained"
                            + " reason=splashSpecificCoverageMissing state=" + state);
                }
            }
        } catch (Throwable failure) {
            log.error("framework bootstrap retirement failed state=" + state, failure);
        } finally {
            // The final state must remain observable even if an unexpected
            // retirement-path diagnostic or framework call fails.
            log.info(hookLedger.summaryLine("terminal:" + state));
            if (currentTopology != null) {
                log.info(currentTopology.summaryLine(hookLedger));
            }
            log.info(dynamicTrustedSummaryLine("postRetirement"));
            log.info(exposureLedger.summaryLine("postRetirement:" + state));
            log.info(splashUiLedger.summaryLine("postRetirement:" + state));
            logDecisionObservationSummary("postRetirement:" + state);
            log.info(storage.auditLine());
            log.info("coordinator bootstrapRetireAttempted=true state=" + state
                    + " frameworkHooksRetired=" + !hookLedger.hasActiveFrameworkHooks()
                    + " traceFrozen=" + (current != null && current.isFrozen()));
        }
    }

    private void cleanupTerminal() {
        synchronized (stateLock) {
            if (terminalCleaned) {
                return;
            }
            terminalCleaned = true;
        }
        mainHandler.removeCallbacksAndMessages(null);
        runtimeDexObserver.close();
        closeSession("terminal");
        retireAttachHook("terminalCleanup");
        worker.shutdown();
        // §7/§10A.6: the terminal summary must distinguish manifest-exact and
        // dynamic-trusted availability, machine-readable, even when the
        // bootstrap retire never runs (e.g. retained splash fallback).
        HookTopology currentTopology = topology;
        if (currentTopology != null) {
            log.info(currentTopology.summaryLine(hookLedger));
        }
        log.info(dynamicTrustedSummaryLine("terminalCleanup"));
        log.info(exposureLedger.summaryLine("terminal:" + state));
        log.info(splashUiLedger.summaryLine("terminal:" + state));
        logDecisionObservationSummary("terminal:" + state);
        log.info(storage.auditLine());
        log.info("coordinator bootstrap lifecycle retired executorShutdown=true"
                + " watcherUnhooked=true");
    }

    /**
     * §10A.6 machine-readable dynamic-feature terminal status. An enabled
     * dynamic feature that could not establish real coverage reports
     * UNAVAILABLE with a root cause — never a bare "enabled=true" that could
     * be misread as healthy while the process is DEGRADED.
     */
    private String dynamicTrustedSummaryLine(String phase) {
        HookTopology current = topology;
        boolean splashEnabled = current != null && current.needsSplash();
        boolean feedEnabled = current != null && current.needsFeed();
        boolean fallbackRequired = current != null
                && current.needsInstrumentationFallback();
        boolean instrumentationHookPresent = splashHooks.isBootstrapInstalled();
        boolean retirePending = state.isTerminal()
                && instrumentationHookPresent
                && shouldRetireFramework()
                && !bootstrapRetired.get();
        return DynamicTrustedStatus.summaryLine(
                state,
                splashEnabled,
                fallbackRequired,
                !installedSplashClasses.isEmpty(),
                splashEnabled && embeddedSplashHost,
                splashEmbeddedHooks.isInstalled(),
                splashDecisionObserver.isInstalled(),
                instrumentationHookPresent,
                retirePending,
                feedEnabled,
                entityListHooks.hookedMethodCount(),
                entityListHooks.isAccessorsComplete(),
                isCoverageSettled(),
                dynamicFailureReason,
                phase);
    }

    private void maybeRecoverAfterSplashResolved() {
        if (!splashCandidateSeenBeforeReady || splashFinishedByHook || !isSplashReady()) {
            return;
        }
        if (cache == null || identity == null) {
            return;
        }
        Map<String, ResolvedTarget> cached = cache.loadTargets(identity);
        ResolvedTarget splash = cached.get(TargetResolver.KEY_SPLASH_BASE);
        if (splash == null || TargetVerifier.verify(splash, resolveLoader()) != null) {
            log.info("recovery skipped reason=cacheVerificationFailed");
            return;
        }
        recoveryController.attachIdentity(identity);
        recoveryController.markSplashEscaped();
        recoveryController.onSplashResolved(cache);
    }

    private void watchdog(String reason) {
        if (state.isTerminal()) {
            // BUG-D: a deadline queued before termination must not resurrect
            // sessions or rewrite the terminal state.
            if (WATCHDOG_DEADLINE_REASON.equals(reason)) {
                log.info("terminal late callback ignored event=watchdogDeadline"
                        + " state=" + state);
            }
            return;
        }
        traceAfterContext("watchdog", reason);
        log.info("coordinator watchdog fired reason=" + reason + " state=" + state);
        if (WATCHDOG_DEADLINE_REASON.equals(reason)) {
            // Deadline semantics take precedence over ANY intermediate state:
            // a process stuck in WAIT_RUNTIME_DEX/CACHE_VERIFY/... at 20s must
            // still terminate here instead of returning early and suspending
            // forever. Coverage settles by definition at the deadline, so a
            // core-ready process finishes READY; a core-incapable one is
            // DEGRADED.
            boolean coreReady = isCoreReady();
            BootstrapState terminal = ReadinessPolicy.deadlineTerminalState(coreReady);
            log.info("resolver watchdog deadline intermediateState=" + state
                    + " coreReady=" + coreReady + " terminal=" + terminal);
            if (terminal == BootstrapState.READY) {
                finishReady("deadline");
            } else {
                dynamicFailureReason = "watchdogDeadline";
                markState(BootstrapState.DEGRADED);
                cleanupTerminal();
                maybeScheduleBootstrapRetire();
                log.info("resolver watchdog deadline state=DEGRADED");
            }
            return;
        }
        if (state == BootstrapState.BOOTSTRAP) {
            markState(BootstrapState.WAIT_RUNTIME_DEX);
        }
        if (ReadinessPolicy.shouldWatchdogRetrySession(state)) {
            // Retry from every non-terminal state, FULL_RESOLVE included:
            // core-ready-but-coverage-pending must not depend on further
            // class loading to get its next resolution session.
            triggerSession(reason);
        }
    }

    /**
     * BUG-D: terminal states are strictly monotonic. Once READY or DEGRADED
     * is reached, no late callback may move the state anywhere — not even to
     * the other terminal state.
     */
    private void markState(BootstrapState next) {
        synchronized (stateLock) {
            if (state.isTerminal()) {
                return;
            }
            if (state != next) {
                state = next;
                traceAfterContext("state", next.name());
                log.info("coordinator state=" + next);
            }
        }
    }

    private void traceAfterContext(String event, String detail) {
        BootstrapTrace current = trace;
        if (current != null) {
            current.mark(event, detail);
        }
    }

    private void ensureIdentityAsync() {
        if (!SafeExecutor.tryExecute(worker, () -> {
            if (identity != null || appContext == null) {
                return;
            }
            identity = TargetIdentity.compute(appContext);
            if (trace != null) {
                trace.mark("stableIdentityComputed", identity.describe());
            }
            log.info("coordinator stable identity: " + identity.describe());
        })) {
            log.info("terminal late callback ignored event=identityAsync"
                    + " reason=executorShutdown");
        }
    }

    private static int readCoolapkMajor(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(CoolapkModule.TARGET_PACKAGE, 0);
            String version = info.versionName;
            if (version == null || version.isEmpty()) {
                return 0;
            }
            int dot = version.indexOf('.');
            String major = dot < 0 ? version : version.substring(0, dot);
            return Integer.parseInt(major.replaceAll("[^0-9]", ""));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Exact resource capability for the MainActivity-hosted splash container. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static boolean hasEmbeddedSplashResource(Context context) {
        try {
            return context.getResources().getIdentifier(
                    "main_splash_ad", "id", CoolapkModule.TARGET_PACKAGE) != 0;
        } catch (Throwable ignored) {
            // Class capability below remains an independent conservative signal.
            return false;
        }
    }

    private static long hostVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(CoolapkModule.TARGET_PACKAGE, 0)
                    .getLongVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String hostVersionName(Context context) {
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(CoolapkModule.TARGET_PACKAGE, 0).versionName;
            return versionName == null ? "unknown" : versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object instance = activityThread.getMethod("currentActivityThread").invoke(null);
            if (instance == null) {
                return null;
            }
            Object application = activityThread.getMethod("currentApplication").invoke(instance);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
