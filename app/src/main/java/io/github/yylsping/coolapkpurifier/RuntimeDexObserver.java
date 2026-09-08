package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Temporary ClassLoader watcher that emits runtimeDexReady as soon as a real
 * Coolapk business class is loaded. Fixed sleeps are explicitly not used.
 *
 * <p>Lifecycle: {@link #install()} arms the observer and hooks
 * {@code ClassLoader.loadClass}. The first business-class event fires once and
 * closes the hooks. {@link #rearm()} re-arms and, when the hooks were closed,
 * reinstalls them, so a later runtime DEX generation is still observed.
 */
final class RuntimeDexObserver {
    interface Listener {
        void onRuntimeDexReady(String trigger, ClassLoader runtimeClassLoader);
    }

    /** Installs the raw loadClass hooks into the observer's handle list. */
    interface HookInstaller {
        void installLoadClassHooks() throws Throwable;
    }

    private final ModuleLog log;
    private final Listener listener;
    private final HookInstaller hookInstaller;
    private final XposedModule module;
    private final List<HookHandle> handles = new ArrayList<>();
    private boolean armed;
    /**
     * Guards against concurrent hook installation. Two threads calling
     * rearm() while the installer is still running (handles not yet
     * registered) used to both observe an empty handle list and install the
     * loadClass hooks twice.
     */
    private boolean installing;

    RuntimeDexObserver(XposedModule module, ModuleLog log, Listener listener) {
        this(module, log, listener, null);
    }

    RuntimeDexObserver(ModuleLog log, Listener listener, HookInstaller hookInstaller) {
        this(null, log, listener, hookInstaller);
    }

    private RuntimeDexObserver(XposedModule module, ModuleLog log, Listener listener,
                               HookInstaller hookInstaller) {
        this.module = module;
        this.log = log;
        this.listener = listener;
        this.hookInstaller = hookInstaller != null ? hookInstaller : this::installDefaultHooks;
    }

    void install() {
        ensureArmedAndHooked("install");
    }

    /**
     * Re-arm after a retryable miss so a later loader generation can retry.
     * Fixes the lost-observer defect: the previous implementation set
     * {@code armed = true} before delegating to {@code install()}, whose own
     * {@code armed} guard then returned without ever reinstalling the closed
     * loadClass hooks, permanently blinding the observer.
     */
    void rearm() {
        ensureArmedAndHooked("rearm");
    }

    private void ensureArmedAndHooked(String reason) {
        boolean needInstall;
        synchronized (this) {
            armed = true;
            needInstall = handles.isEmpty() && !installing;
            if (needInstall) {
                installing = true;
            }
        }
        if (!needInstall) {
            return;
        }
        try {
            installHooks(reason);
        } finally {
            synchronized (this) {
                installing = false;
            }
        }
    }

    private void installHooks(String reason) {
        try {
            hookInstaller.installLoadClassHooks();
            log.info("runtime dex observer installed reason=" + reason);
        } catch (Throwable throwable) {
            log.error("runtime dex observer install failed reason=" + reason, throwable);
        }
    }

    private void installDefaultHooks() throws ReflectiveOperationException {
        Method oneArg = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
        Method twoArgs = ClassLoader.class.getDeclaredMethod(
                "loadClass", String.class, boolean.class);
        addHandle(module.hook(oneArg)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("coolapk-runtime-dex-1")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class<?>) {
                        onClassLoaded((Class<?>) result);
                    }
                    return result;
                }));
        addHandle(module.hook(twoArgs)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("coolapk-runtime-dex-2")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class<?>) {
                        onClassLoaded((Class<?>) result);
                    }
                    return result;
                }));
    }

    /** Installers register their handles here so close()/rearm() stay exact. */
    void addHandle(HookHandle handle) {
        if (handle == null) {
            return;
        }
        synchronized (this) {
            handles.add(handle);
        }
    }

    /** Sink for the loadClass interceptors; single-shot per arming. */
    void onClassLoaded(Class<?> loadedClass) {
        if (!isBusinessClass(loadedClass.getName())) {
            return;
        }
        ClassLoader loader;
        synchronized (this) {
            if (!armed) {
                return;
            }
            armed = false;
            loader = loadedClass.getClassLoader();
            close();
        }
        log.info("runtime dex ready trigger=loadClass class=" + loadedClass.getName()
                + " loaderIdentity=" + System.identityHashCode(loader));
        listener.onRuntimeDexReady("loadClass:" + loadedClass.getName(), loader);
    }

    void notifyFirstActivityPre(ClassLoader activityLoader) {
        synchronized (this) {
            if (!armed) {
                return;
            }
            armed = false;
            close();
        }
        log.info("runtime dex ready trigger=firstActivityPre loaderIdentity="
                + System.identityHashCode(activityLoader));
        listener.onRuntimeDexReady("firstActivityPre", activityLoader);
    }

    void close() {
        synchronized (this) {
            for (HookHandle handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            handles.clear();
        }
    }

    boolean isArmed() {
        synchronized (this) {
            return armed;
        }
    }

    private static boolean isBusinessClass(String name) {
        if (name == null || !name.startsWith("com.coolapk.market.")) {
            return false;
        }
        // Shell DEX keeps a few loader classes in the root package. Business
        // code appears under feature packages such as .view and .model.
        return name.startsWith("com.coolapk.market.view.")
                || name.startsWith("com.coolapk.market.model.")
                || name.startsWith("com.coolapk.market.manager.")
                || name.startsWith("com.coolapk.market.util.");
    }
}
