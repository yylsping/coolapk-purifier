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
    private final HookLedger ledger;
    private final List<HookHandle> handles = new ArrayList<>();
    private final List<String> hookIds = new ArrayList<>();
    private boolean armed;
    /**
     * Guards against concurrent hook installation. Two threads calling
     * rearm() while the installer is still running (handles not yet
     * registered) used to both observe an empty handle list and install the
     * loadClass hooks twice.
     */
    private boolean installing;

    RuntimeDexObserver(XposedModule module, ModuleLog log, Listener listener,
                       HookLedger ledger) {
        this(module, log, listener, null, ledger);
    }

    RuntimeDexObserver(ModuleLog log, Listener listener, HookInstaller hookInstaller) {
        this(null, log, listener, hookInstaller, null);
    }

    RuntimeDexObserver(ModuleLog log, Listener listener, HookInstaller hookInstaller,
                       HookLedger ledger) {
        this(null, log, listener, hookInstaller, ledger);
    }

    private RuntimeDexObserver(XposedModule module, ModuleLog log, Listener listener,
                               HookInstaller hookInstaller, HookLedger ledger) {
        this.module = module;
        this.log = log;
        this.listener = listener;
        this.hookInstaller = hookInstaller != null ? hookInstaller : this::installDefaultHooks;
        this.ledger = ledger;
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
            // §9: a partial/failed install must not leave the observer armed.
            synchronized (this) {
                armed = false;
            }
            log.error("runtime dex observer install failed reason=" + reason, throwable);
        }
    }

    /** Creates one raw loadClass hook; abstracts module.hook for tests. */
    interface RawHooker {
        HookHandle hook(Method method, String id) throws Throwable;
    }

    /**
     * §9: the two loadClass hooks are ONE logical observer set. Handles and
     * ledger entries are registered only after BOTH hooks exist; when the
     * second hook fails, the first is rolled back so no half-installed
     * observer survives.
     */
    static void installLoadClassSet(RawHooker hooker, RuntimeDexObserver observer)
            throws Throwable {
        Method oneArg = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
        Method twoArgs = ClassLoader.class.getDeclaredMethod(
                "loadClass", String.class, boolean.class);
        HookHandle first = null;
        try {
            first = hooker.hook(oneArg, "coolapk-runtime-dex-1");
            HookHandle second = hooker.hook(twoArgs, "coolapk-runtime-dex-2");
            observer.addHandle(first, "coolapk-runtime-dex-1");
            observer.addHandle(second, "coolapk-runtime-dex-2");
        } catch (Throwable failure) {
            if (first != null) {
                try {
                    first.unhook();
                } catch (Throwable ignored) {
                }
            }
            throw failure;
        }
    }

    private void installDefaultHooks() throws Throwable {
        installLoadClassSet((method, id) -> module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class<?>) {
                        onClassLoaded((Class<?>) result);
                    }
                    return result;
                }), this);
    }

    /** Installers register their handles here so close()/rearm() stay exact. */
    void addHandle(HookHandle handle) {
        addHandle(handle, null);
    }

    void addHandle(HookHandle handle, String id) {
        if (handle == null) {
            return;
        }
        synchronized (this) {
            handles.add(handle);
            if (id != null) {
                hookIds.add(id);
                if (ledger != null) {
                    ledger.record(HookLedger.Layer.FRAMEWORK, "runtimeDex", id,
                            "ClassLoader.loadClass");
                }
            }
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

    /**
     * Release-hardening §4: the observer goes logically inert first
     * ({@code armed=false}); each ledger entry retires only after its unhook
     * actually succeeded. A failed unhook keeps the handle retained and the
     * ledger entry active, and is logged with the remaining count.
     */
    void close() {
        synchronized (this) {
            armed = false;
            List<HookHandle> retained = new ArrayList<>();
            List<String> retainedIds = new ArrayList<>();
            for (int i = 0; i < handles.size(); i++) {
                String id = i < hookIds.size() ? hookIds.get(i) : null;
                try {
                    handles.get(i).unhook();
                    if (id != null && ledger != null) {
                        ledger.retire(id, "observerClosed");
                    }
                } catch (Throwable failure) {
                    retained.add(handles.get(i));
                    if (id != null) {
                        retainedIds.add(id);
                    }
                    if (log != null) {
                        log.error("runtime dex observer unhook failed id=" + id, failure);
                    }
                }
            }
            handles.clear();
            handles.addAll(retained);
            hookIds.clear();
            hookIds.addAll(retainedIds);
            if (!retained.isEmpty() && log != null) {
                log.info("runtime dex observer close partial remaining=" + retainedIds);
            }
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
