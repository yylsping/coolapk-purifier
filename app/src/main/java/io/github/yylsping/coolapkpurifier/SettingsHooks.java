package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** Native settings model insertion plus a user-launched module configuration Activity. */
final class SettingsHooks {
    static final String EXIT_MESSAGE = "请重启软件以动态适配更改选项";
    private static final int CONFIG_REQUEST_CODE = 0x4350;

    private final XposedModule module;
    private final HookLedger ledger;
    private final ModuleLog log;
    private final PurifierConfig config;
    private final ResolverCacheStore resolverCacheStore;
    private final int coolapkMajor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<ClassLoader, HookHandle> entryHooks = new HashMap<>();
    private final PageInjectionRetry<Activity> injectionRetry;
    private volatile boolean lifecycleCallbacksInstalled;
    private int settingsIcon;

    SettingsHooks(XposedModule module, HookLedger ledger, ModuleLog log,
                  PurifierConfig config, ResolverCacheStore resolverCacheStore,
                  int coolapkMajor) {
        this.module = module;
        this.ledger = ledger;
        this.log = log;
        this.config = config;
        this.resolverCacheStore = resolverCacheStore;
        this.coolapkMajor = coolapkMajor;
        injectionRetry = new PageInjectionRetry<>(
                (task, delay) -> mainHandler.postDelayed(task, delay),
                mainHandler::removeCallbacks, this::ensureNativeEntry,
                activity -> log.info("settings native entry unavailable; no overlay fallback"));
    }

    void install(Context context) {
        if (lifecycleCallbacksInstalled) return;
        try {
            Context candidate = context instanceof Application ? context
                    : context == null ? null : context.getApplicationContext();
            if (!(candidate instanceof Application)) return;
            settingsIcon = candidate.getResources().getIdentifier(
                    "ic_setting", "drawable", CoolapkModule.TARGET_PACKAGE);
            ((Application) candidate).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityPreCreated(Activity activity, Bundle state) {
                            ensureNativeEntry(activity);
                        }
                        @Override public void onActivityCreated(Activity activity, Bundle state) {
                            ensureNativeEntry(activity);
                        }
                        @Override public void onActivityStarted(Activity activity) { }
                        @Override public void onActivityResumed(Activity activity) {
                            if (!ensureNativeEntry(activity)) injectionRetry.start(activity);
                        }
                        @Override public void onActivityPaused(Activity activity) {
                            injectionRetry.cancel(activity);
                        }
                        @Override public void onActivityStopped(Activity activity) { }
                        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                        @Override public void onActivityDestroyed(Activity activity) {
                            injectionRetry.cancel(activity);
                        }
                    });
            lifecycleCallbacksInstalled = true;
            log.info("settings lifecycle callbacks registered frameworkHooks=0");
        } catch (Throwable failure) {
            log.error("settings lifecycle callback registration failed", failure);
        }
    }

    boolean isLifecycleCallbacksInstalled() {
        return lifecycleCallbacksInstalled;
    }

    private synchronized boolean ensureNativeEntry(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return true;
        ClassLoader loader = activity.getClass().getClassLoader();
        if (entryHooks.containsKey(loader)) return true;
        try {
            Class<?> type = Class.forName(SettingsEntryInjector.FRAGMENT, false, loader);
            Method method = SettingsEntryInjector.findInitData(type);
            if (method == null) return false;
            HookHandle handle = module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-settings-native-entry").intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            boolean inserted = SettingsEntryInjector.inject(
                                    chain.getThisObject(), settingsIcon, this::showConfigPage);
                            log.info("settings native entry inserted=" + inserted
                                    + " source=initData listOwned=true");
                        } catch (Throwable failure) {
                            log.error("settings model injection skipped; native settings preserved",
                                    failure);
                        }
                        return result;
                    });
            entryHooks.put(loader, handle);
            ledger.record(HookLedger.Layer.BUSINESS, "settings", "settings-native-initData-"
                    + Integer.toHexString(System.identityHashCode(loader)),
                    method.toGenericString());
            log.info("settings business hook installed method=" + method + " frameworkHooks=0");
            return true;
        } catch (Throwable failure) {
            log.info("settings native entry not ready reason="
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void showConfigPage(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID,
                    ModuleConfigActivity.class.getName()));
            byte[] snapshot = config.serializedSnapshot();
            if (snapshot != null) {
                intent.putExtra(ModuleStorageContract.EXTRA_INITIAL_CONFIG, snapshot);
            }
            byte[] cacheSnapshot = resolverCacheStore.read();
            if (cacheSnapshot != null
                    && cacheSnapshot.length <= ModuleStorageContract.CACHE_HANDOFF_MAX_BYTES) {
                intent.putExtra(ModuleStorageContract.EXTRA_INITIAL_CACHE, cacheSnapshot);
            }
            intent.putExtra(ModuleStorageContract.EXTRA_COOLAPK_MAJOR, coolapkMajor);
            // Starting for result gives the module Activity a platform-authenticated
            // calling package before it considers the one-time legacy snapshot.
            activity.startActivityForResult(intent, CONFIG_REQUEST_CODE);
            log.info("settings config launch requested mode=moduleForegroundActivity"
                    + " callerIdentity=startActivityForResult"
                    + " backgroundProcessStartRequired=false");
        } catch (Throwable failure) {
            Toast.makeText(activity.getApplicationContext(),
                    "配置界面无法打开", Toast.LENGTH_SHORT).show();
            log.error("settings config Activity launch failed", failure);
        }
    }
}
