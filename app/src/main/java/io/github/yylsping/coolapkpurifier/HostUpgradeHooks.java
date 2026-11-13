package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** Blocks self-upgrades at their own check, prompt and download entry points. */
final class HostUpgradeHooks {
    static final String[] HOOK_IDS = {
            "coolapk-self-update-check", "coolapk-self-update-popup",
            "coolapk-self-update-manual", "coolapk-self-update-download"};
    private static final PurifierConfig.Feature FEATURE = PurifierConfig.Feature.BLOCK_SELF_UPDATE;
    private final List<HookHandle> handles = new ArrayList<>();
    private final AtomicBoolean[] logged = {new AtomicBoolean(), new AtomicBoolean(),
            new AtomicBoolean(), new AtomicBoolean()};
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final HookLedger ledger;
    private final FeatureExposureLedger exposure;
    // A partial installation is inert even if rollback cannot unhook a handle.
    private volatile boolean active;

    HostUpgradeHooks(XposedModule module, ModuleLog log, FeatureGate gate,
                    HookLedger ledger, FeatureExposureLedger exposure) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.ledger = ledger;
        this.exposure = exposure;
    }

    static final class Runtime {
        final Method check, popup, manual, download, just, packageName, extraPackage, context;
        final Class<?> mobileApp;
        final Constructor<?> result;

        Runtime(HostUpgradeTargetSpec spec, ClassLoader loader) throws ReflectiveOperationException {
            Class<?> observable = load(spec.observableClass, loader);
            Class<?> extra = load("com.coolapk.market.model.Extra", loader);
            mobileApp = load("com.coolapk.market.model.MobileApp", loader);
            check = exact(load(spec.checkOwner, loader), spec.checkMethod, observable,
                    false, List.class, boolean.class);
            popup = exact(load("com.coolapk.market.CoolMarketApplication", loader),
                    spec.popupMethod, void.class, false);
            Class<?> about = load("com.coolapk.market.view.settings.AboutFragment", loader);
            manual = exact(about, spec.manualCheckMethod, void.class, false);
            context = about.getMethod("getContext");
            if (context.getReturnType() != Context.class) throw new NoSuchMethodException("getContext");
            download = exact(load("com.coolapk.market.manager.ActionManager", loader),
                    spec.downloadMethod, boolean.class, true,
                    Context.class, String.class, extra, int.class, boolean.class);
            just = exact(observable, spec.justMethod, observable, true, Object.class);
            // MobileApp is an abstract AutoValue model; invoke its virtual getter on concrete rows.
            packageName = mobileApp.getMethod("getPackageName");
            if (packageName.getReturnType() != String.class || Modifier.isStatic(packageName.getModifiers())) {
                throw new NoSuchMethodException("MobileApp.getPackageName");
            }
            extraPackage = exact(extra, "getString", String.class, false, String.class);
            Class<?> resultClass = load("com.coolapk.market.network.Result", loader);
            result = resultClass.getConstructor(Integer.class, String.class, String.class, Object.class);
            exact(resultClass, "getData", Object.class, false);
            exact(resultClass, "isSuccess", boolean.class, false);
        }

        private static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }

        static Method exact(Class<?> owner, String name, Class<?> returns, boolean isStatic,
                            Class<?>... parameters) throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, parameters);
            int flags = method.getModifiers();
            if (method.getReturnType() != returns || Modifier.isStatic(flags) != isStatic
                    || Modifier.isAbstract(flags) || Modifier.isNative(flags)
                    || method.isBridge() || method.isSynthetic()) {
                throw new NoSuchMethodException("hostUpgrade contract: " + method);
            }
            method.setAccessible(true);
            return method;
        }

        String packageOf(Object value) throws ReflectiveOperationException {
            return mobileApp.isInstance(value) ? (String) packageName.invoke(value) : null;
        }

        Object emptyCheckResult() throws ReflectiveOperationException {
            Object empty = result.newInstance(1, null, null, Collections.emptyList());
            return just.invoke(null, empty);
        }
    }

    synchronized InstallResult install(HostUpgradeTargetSpec spec, ClassLoader loader) {
        if (active) return InstallResult.ALREADY_INSTALLED;
        if (!handles.isEmpty()) return InstallResult.INSTALL_FAILED;
        if (spec == null || loader == null) return InstallResult.TARGET_MISSING;
        final Runtime runtime;
        try {
            runtime = new Runtime(spec, loader);
        } catch (Throwable failure) {
            log.error("hostUpgrade contract rejected", failure);
            return InstallResult.TARGET_MISSING;
        }
        try {
            remember(module.hook(runtime.check).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(HOOK_IDS[0]).intercept(chain -> {
                        if (!enabled() || !(chain.getArg(0) instanceof List<?>)) return chain.proceed();
                        List<?> original = (List<?>) chain.getArg(0);
                        final List<?> filtered;
                        final Object empty;
                        try {
                            filtered = HostUpgradePolicy.withoutHost(original, runtime::packageOf);
                            empty = filtered != original && filtered.isEmpty()
                                    ? runtime.emptyCheckResult() : null;
                        } catch (Throwable failure) {
                            return chain.proceed();
                        }
                        if (filtered == original) return chain.proceed();
                        suppressed(0, "check");
                        if (filtered.isEmpty()) return empty;
                        return chain.proceed(new Object[]{filtered, chain.getArg(1)});
                    }), 0, runtime.check);
            remember(module.hook(runtime.popup).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(HOOK_IDS[1]).intercept(chain -> {
                        if (!enabled()) return chain.proceed();
                        suppressed(1, "popup");
                        return null;
                    }), 1, runtime.popup);
            remember(module.hook(runtime.manual).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(HOOK_IDS[2]).intercept(chain -> {
                        if (!enabled()) return chain.proceed();
                        Object context = runtime.context.invoke(chain.getThisObject());
                        if (context instanceof Context) {
                            Toast.makeText((Context) context, "已屏蔽酷安更新检查", Toast.LENGTH_SHORT).show();
                        }
                        suppressed(2, "manual");
                        return null;
                    }), 2, runtime.manual);
            remember(module.hook(runtime.download).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(HOOK_IDS[3]).intercept(chain -> {
                        if (!enabled() || chain.getArg(2) == null) return chain.proceed();
                        final String name;
                        try {
                            name = (String) runtime.extraPackage.invoke(chain.getArg(2), "PACKAGE_NAME");
                        } catch (Throwable failure) {
                            return chain.proceed();
                        }
                        if (!HostUpgradePolicy.isHost(name)) return chain.proceed();
                        suppressed(3, "download");
                        return false;
                    }), 3, runtime.download);
            active = true;
            exposure.recordHookInstalled(FEATURE);
            return InstallResult.INSTALLED;
        } catch (Throwable failure) {
            active = false;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try {
                    handles.get(i).unhook();
                    ledger.retire(HOOK_IDS[i], "installRollback");
                    handles.remove(i);
                } catch (Throwable rollbackFailure) {
                    log.error("hostUpgrade rollback failed; interceptor remains inert", rollbackFailure);
                }
            }
            log.error("hostUpgrade installation failed", failure);
            return InstallResult.INSTALL_FAILED;
        }
    }

    private boolean enabled() {
        return active && gate.isEffectiveEnabled(FEATURE);
    }

    private void remember(HookHandle handle, int index, Method target) {
        handles.add(handle);
        ledger.record(HookLedger.Layer.BUSINESS, FEATURE.key, HOOK_IDS[index], target.toGenericString());
    }

    private void suppressed(int index, String layer) {
        exposure.recordEntered(FEATURE);
        exposure.recordSampleSeen(FEATURE);
        exposure.recordModified(FEATURE);
        if (logged[index].compareAndSet(false, true)) log.info("hostUpgrade suppressed layer=" + layer);
    }
}
