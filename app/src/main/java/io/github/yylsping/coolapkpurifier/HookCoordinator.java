package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * Normal triggers are Application.attach, runtime class load and Activity
 * pre-create. Timer watchdogs are only a last-resort fallback: the 8s watchdog
 * retries a session from ANY non-terminal state (including FULL_RESOLVE with
 * unsettled feed coverage), and the 20s deadline takes precedence over every
 * intermediate state and always terminates (READY when core filtering works,
 * DEGRADED otherwise). READY itself is two-layer: core hooks installed AND
 * feed coverage settled (both anchor classes hooked, or deadline).
 */
final class HookCoordinator implements SplashHooks.ActivityObserver,
        RuntimeDexObserver.Listener {
    private static final long WATCHDOG_DELAY_MILLIS = 8_000L;
    private static final long DEADLINE_MILLIS = 20_000L;
    private static final String WATCHDOG_RETRY_REASON = "watchdog 8s";
    private static final String WATCHDOG_DEADLINE_REASON = "watchdog 20s deadline";

    private static final String ANCHOR_AD_HELPER_DESCRIPTOR =
            DescriptorUtils.classDescriptorOf(NormalResolver.AD_HELPER_CLASS);
    private static final String ANCHOR_ENTITY_LIST_FRAGMENT_DESCRIPTOR =
            DescriptorUtils.classDescriptorOf(NormalResolver.ENTITY_LIST_FRAGMENT_CLASS);

    private final XposedModule module;
    private final ModuleLog log;
    private final ClassLoader primaryLoader;
    private final FeatureGate featureGate = new FeatureGate();
    private final HookLedger hookLedger = new HookLedger();
    private final SplashHooks splashHooks;
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
    private volatile boolean terminalCleaned;
    private int sessionAttempt;

    HookCoordinator(XposedModule module, ModuleLog log, ClassLoader primaryLoader) {
        this.module = module;
        this.log = log;
        this.primaryLoader = primaryLoader;
        this.splashHooks = new SplashHooks(module, log, this);
        this.entityListHooks = new EntityListHooks(module, log, featureGate);
        this.runtimeDexObserver = new RuntimeDexObserver(module, log, this);
        this.d1DetailSponsorDelta = new D1DetailSponsorDelta(module, log, featureGate);
        this.d2ReplySponsorReplacement =
                new D2ReplySponsorReplacement(module, log, featureGate);
        this.d3SameTopicReplacement = new D3SameTopicReplacement(module, log, featureGate);
        this.d5TopicDeviceRecommendDelta =
                new D5TopicDeviceRecommendDelta(module, log, featureGate);
        this.d6AutoCommentDelta = new D6AutoCommentDelta(module, log, featureGate);
        this.recoveryController = new RecoveryController(log, null, null);
        this.firstAdaptationToast = new FirstAdaptationToast(log);
    }

    void install() throws ReflectiveOperationException {
        markState(BootstrapState.BOOTSTRAP);
        traceAfterContext("packageReady", "loader=" + System.identityHashCode(primaryLoader));

        splashHooks.installInstrumentationFallback();
        runtimeDexObserver.install();
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
                    .setId("coolapk-application-attach")
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
            traceAfterContext("attachHookInstalled", "before attach");
        } catch (Throwable throwable) {
            log.error("Application.attach bootstrap hook install failed", throwable);
        }
    }

    private final Object configLock = new Object();
    private volatile PurifierConfig config;
    private volatile SettingsHooks settingsHooks;

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
                    int coolapkMajor = readCoolapkMajor(usable);
                    PurifierConfig loaded = PurifierConfig.load(usable, log);
                    featureGate.bind(loaded, coolapkMajor);
                    settingsHooks = new SettingsHooks(
                            module, hookLedger, log, loaded, coolapkMajor);
                    config = loaded;
                    log.info("configuration initialized coolapkMajor=" + coolapkMajor
                            + " pending=" + loaded.pendingKind()
                            + " revision=" + loaded.revision());
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
        Context attached = application != null ? application
                : context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
        appContext = attached;
        ensureConfiguration(attached);
        d1DetailSponsorDelta.install(context, context.getClassLoader());
        d2ReplySponsorReplacement.install(context, context.getClassLoader());
        d3SameTopicReplacement.install(context, context.getClassLoader());
        d5TopicDeviceRecommendDelta.install(context, context.getClassLoader());
        d6AutoCommentDelta.install(context, context.getClassLoader());
        trace = new BootstrapTrace(appContext);
        trace.mark("attachAfter", "context=" + appContext.getPackageName());
        cache = new ResolutionCache(appContext);
        recoveryController.attachContext(appContext);
        recoveryController.attachTrace(trace);
        markState(BootstrapState.WAIT_RUNTIME_DEX);
        log.info("coordinator attachAfter state=" + state
                + " attachElapsedMs=" + (SystemClock.elapsedRealtime() - start));
        ensureIdentityAsync();
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
            trace = new BootstrapTrace(appContext);
        }

        boolean loaderChanged = activeRuntimeLoader != null && activeRuntimeLoader != loader;
        if (activeRuntimeLoader == null || loaderChanged) {
            if (loaderChanged) {
                closeSession("runtimeLoaderChanged");
                lastResolutionIncomplete = false;
            }
            activeRuntimeLoader = loader;
            if (dexKitSession == null && trace != null) {
                dexKitSession = new DexKitSession(log, trace, activeRuntimeLoader);
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
        if (!sessionRunning.compareAndSet(false, true)) {
            log.info("coordinator session already running trigger=" + trigger);
            return;
        }
        int attempt = ++sessionAttempt;
        traceAfterContext("sessionStart", "trigger=" + trigger + " attempt=" + attempt);
        worker.execute(() -> {
            try {
                runSession(trigger, attempt);
            } catch (Throwable throwable) {
                log.error("coordinator resolution session failed", throwable);
                traceAfterContext("sessionError", "trigger=" + trigger
                        + " error=" + throwable
                        + " stack=" + android.util.Log.getStackTraceString(throwable));
                markState(BootstrapState.DEGRADED);
                cleanupTerminal();
            } finally {
                sessionRunning.set(false);
            }
        });
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
            trace = new BootstrapTrace(appContext);
        }
        if (cache == null) {
            cache = new ResolutionCache(appContext);
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

        Map<String, ResolvedTarget> cached = cache.loadTargets(identity);
        trace.mark("cacheLookupStart", "attempt=" + attempt + " trigger=" + trigger);
        Map<String, ResolvedTarget> verified = verifyCacheTargets(cached);
        if (!verified.isEmpty()) {
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
        trace.mark("cacheMiss", "verified=" + verified.size() + " total=" + cached.size()
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
        org.luckypray.dexkit.DexKitBridge bridge = session.ensureBridge(trigger);
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
        markState(BootstrapState.SPLASH_CRITICAL);
        trace.mark("splashResolveStart", "trigger=" + trigger);
        List<ResolvedTarget> splashes = new SplashCriticalResolver(bridge, loader, log).resolve();
        trace.mark("splashResolveEnd", "candidates=" + splashes.size());
        if (!splashes.isEmpty()) {
            Map<String, ResolvedTarget> splashTargets = new LinkedHashMap<>();
            for (int i = 0; i < splashes.size(); i++) {
                String key = TargetResolver.indexedKey(TargetResolver.KEY_SPLASH_BASE, i);
                splashTargets.put(key, splashes.get(i).withKey(key));
            }
            applyTargets(splashTargets, "dexkit");
        } else {
            markState(BootstrapState.WAIT_RUNTIME_DEX);
            rearmObserverForRetry();
            log.info("resolver splash retryable state=WAIT_RUNTIME_DEX"
                    + " reason=zeroOrUnverifiableCandidates trigger=" + trigger);
        }

        markState(BootstrapState.FULL_RESOLVE);
        trace.mark("normalResolveStart", "trigger=" + trigger);
        Map<String, ResolvedTarget> normal = new NormalResolver(bridge, loader, log).resolve();
        trace.mark("normalResolveEnd", "targets=" + normal.keySet());
        applyTargets(normal, "dexkit");

        Map<String, ResolvedTarget> all = currentTargets();
        if (!all.isEmpty()) {
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
                if (isSplashReady()) {
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
            trace = new BootstrapTrace(appContext);
        }
        if (dexKitSession == null) {
            dexKitSession = new DexKitSession(log, trace, resolveLoader());
            dexKitSession.notifyLoaderGenerationChanged("sessionCreated:" + trigger);
        }
        return dexKitSession;
    }

    private ClassLoader resolveLoader() {
        ClassLoader loader = activeRuntimeLoader;
        return loader == null ? primaryLoader : loader;
    }

    private void closeSession(String reason) {
        DexKitSession session = dexKitSession;
        if (session != null) {
            session.close();
            traceAfterContext("bridgeClosed", "reason=" + reason
                    + " generation=" + session.getGeneration());
        }
        dexKitSession = null;
    }

    private void applyTargets(Map<String, ResolvedTarget> targets, String source) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        ClassLoader loader = resolveLoader();
        Map<String, ResolvedTarget> merged;
        synchronized (resolvedTargets) {
            // Descriptor-stable merge: an existing descriptor keeps its key,
            // so candidate order changes across sessions can never overwrite
            // an unrelated persisted entry.
            TargetResolver.mergeTargets(resolvedTargets, targets);
            merged = new LinkedHashMap<>(resolvedTargets);
        }
        // Accessors must be rebuilt from the COMPLETE merged target map: an
        // earlier splash-only increment used to wipe the already verified
        // getters and fail the classifier closed for the first feed batches.
        entityListHooks.updateAccessors(merged, loader);

        for (Map.Entry<String, ResolvedTarget> entry : targets.entrySet()) {
            if (!TargetResolver.isFeedKey(entry.getKey())) {
                continue;
            }
            ResolvedTarget feed = entry.getValue();
            Method method = DescriptorUtils.methodForDescriptor(feed.methodDescriptor, loader);
            if (method != null) {
                entityListHooks.install(method);
            } else {
                log.info("feed descriptor not loadable source=" + source
                        + " key=" + entry.getKey() + " target=" + feed.describe());
            }
        }
        if (entityListHooks.hookedMethodCount() > 0) {
            log.info("installed feed hooks source=" + source
                    + " total=" + entityListHooks.hookedMethodCount());
        }

        for (Map.Entry<String, ResolvedTarget> entry : targets.entrySet()) {
            if (!TargetResolver.isSplashKey(entry.getKey())) {
                continue;
            }
            ResolvedTarget splash = entry.getValue();
            try {
                Class<?> type = DescriptorUtils.classForName(splash.classDescriptor, loader);
                if (type == null) {
                    log.info("splash descriptor not loadable source=" + source
                            + " target=" + splash.describe());
                    continue;
                }
                splashGate.addResolvedSplashClass(type);
                boolean installed = splashHooks.installSpecific(type);
                if (installed) {
                    installedSplashClasses.add(type.getName());
                    traceAfterContext("splashHookInstalled", splash.describe()
                            + " installed=true source=" + source);
                    log.info("installed splash hook source=" + source
                            + " class=" + type.getName());
                } else {
                    traceAfterContext("splashHookInstallFailed", splash.describe()
                            + " source=" + source);
                    log.info("splash specific hook not installed source=" + source
                            + " class=" + type.getName() + " frameworkFallback=true");
                }
            } catch (Throwable throwable) {
                log.info("splash descriptor not loadable yet source=" + source
                        + " target=" + splash.describe());
            }
        }
    }

    private Map<String, ResolvedTarget> currentTargets() {
        synchronized (resolvedTargets) {
            return new LinkedHashMap<>(resolvedTargets);
        }
    }

    private boolean isSplashReady() {
        return !installedSplashClasses.isEmpty();
    }

    /** Core ad-filtering capability: splash covered, live feed hooks, accessors complete. */
    private boolean isCoreReady() {
        return ReadinessPolicy.isCoreReady(isSplashReady(),
                entityListHooks.hookedMethodCount(),
                entityListHooks.isAccessorsComplete());
    }

    /** Feed coverage converged by anchor classes (see {@link ReadinessPolicy}). */
    private boolean isCoverageSettled() {
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
     * Event-driven retire. No polling. If the current call is inside the
     * Instrumentation interceptor, retirement is posted to the next main
     * thread message to avoid self-unhook races.
     */
    private void maybeScheduleBootstrapRetire() {
        if (bootstrapRetired.get()) {
            return;
        }
        if (BootstrapRetirePolicy.canRetire(
                state, splashGate.isMainActivitySeen(), isSplashReady())) {
            mainHandler.post(this::retireBootstrap);
        }
    }

    private void retireBootstrap() {
        if (!BootstrapRetirePolicy.canRetire(
                state, splashGate.isMainActivitySeen(), isSplashReady())) {
            return;
        }
        if (!bootstrapRetired.compareAndSet(false, true)) {
            return;
        }
        BootstrapTrace current = trace;
        if (current != null) {
            current.freeze("terminalState",
                    "state=" + state + " bootstrapRetired=true traceFrozen=true"
                            + " elapsedMs=" + current.elapsedSinceStart());
        }
        // Passive mode: coordinator callbacks stop, but the Instrumentation
        // splash safety net itself is retained for the process lifetime.
        splashHooks.retireBootstrapCallbacks();
        log.info("coordinator bootstrapRetired=true state=" + state
                + " traceFrozen=" + (current != null && current.isFrozen()));
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
        worker.shutdown();
        log.info("coordinator bootstrap lifecycle retired executorShutdown=true"
                + " watcherUnhooked=true");
    }

    private void maybeRecoverAfterSplashResolved() {
        if (!splashCandidateSeenBeforeReady || splashFinishedByHook || !isSplashReady()) {
            return;
        }
        if (cache == null || identity == null) {
            return;
        }
        Map<String, ResolvedTarget> persisted = cache.loadTargets(identity);
        ResolvedTarget splash = persisted.get(TargetResolver.KEY_SPLASH_BASE);
        if (splash == null || TargetVerifier.verify(splash, resolveLoader()) != null) {
            log.info("recovery skipped reason=cacheVerificationFailed");
            return;
        }
        recoveryController.attachIdentity(identity);
        recoveryController.markSplashEscaped();
        recoveryController.onSplashResolved(cache);
    }

    private void watchdog(String reason) {
        traceAfterContext("watchdog", reason);
        log.info("coordinator watchdog fired reason=" + reason + " state=" + state);
        if (state == BootstrapState.READY) {
            return;
        }
        if (WATCHDOG_DEADLINE_REASON.equals(reason)) {
            // Deadline semantics take precedence over ANY intermediate state:
            // a process stuck in WAIT_RUNTIME_DEX/CACHE_VERIFY/... at 20s must
            // still terminate here instead of returning early and suspending
            // forever. Coverage settles by definition at the deadline, so a
            // core-ready process finishes READY; a core-incapable one is
            // DEGRADED. The passive Instrumentation splash safety net is
            // retained in both cases (SplashHooks are never unhooked).
            boolean coreReady = isCoreReady();
            BootstrapState terminal = ReadinessPolicy.deadlineTerminalState(coreReady);
            log.info("resolver watchdog deadline intermediateState=" + state
                    + " coreReady=" + coreReady + " terminal=" + terminal);
            if (terminal == BootstrapState.READY) {
                finishReady("deadline");
            } else {
                markState(BootstrapState.DEGRADED);
                cleanupTerminal();
                maybeScheduleBootstrapRetire();
                log.info("resolver watchdog deadline state=DEGRADED"
                        + " passiveSplashNet=retained");
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

    private void markState(BootstrapState next) {
        synchronized (stateLock) {
            if (state.isTerminal() && next != BootstrapState.READY
                    && next != BootstrapState.DEGRADED) {
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
        worker.execute(() -> {
            if (identity != null || appContext == null) {
                return;
            }
            identity = TargetIdentity.compute(appContext);
            if (trace != null) {
                trace.mark("stableIdentityComputed", identity.describe());
            }
            log.info("coordinator stable identity: " + identity.describe());
        });
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
