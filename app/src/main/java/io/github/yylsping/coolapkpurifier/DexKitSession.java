package io.github.yylsping.coolapkpurifier;

import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * DexKitBridge lifecycle. A bridge is bound to the runtime ClassLoader
 * generation it was created from. It is created only after runtime DEX ready
 * and rebuilt at most once when the observed loader generation changes.
 */
final class DexKitSession {
    private final ModuleLog log;
    private final BootstrapTrace trace;
    private final ClassLoader loader;
    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger();

    private DexKitBridge bridge;
    private int bridgeGeneration = -1;
    private int rebuildCount;
    private long loaderIdentity = -1L;

    DexKitSession(ModuleLog log, BootstrapTrace trace, ClassLoader loader) {
        this.log = log;
        this.trace = trace;
        this.loader = loader;
    }

    int getGeneration() {
        return generation.get();
    }

    long getLoaderIdentity() {
        return System.identityHashCode(loader);
    }

    void notifyLoaderGenerationChanged(String reason) {
        generation.incrementAndGet();
        trace("loaderGeneration", "reason=" + reason
                + " generation=" + generation.get()
                + " loaderIdentity=" + getLoaderIdentity());
        log.info("resolver loaderGeneration reason=" + reason
                + " generation=" + generation.get()
                + " loaderIdentity=" + getLoaderIdentity());
    }

    DexKitBridge ensureBridge(String trigger) {
        synchronized (lock) {
            long loaderId = System.identityHashCode(loader);
            if (bridge != null && bridge.isValid()
                    && bridgeGeneration == generation.get()
                    && loaderIdentity == loaderId) {
                return bridge;
            }
            // Bounded rebuild budget: the runtime loader may append DEX in
            // several stages, and each incomplete session bumps the
            // generation to force one rescan.
            if (rebuildCount >= 4) {
                log.info("resolver dexkit rebuild refused rebuildCount=" + rebuildCount
                        + " trigger=" + trigger);
                return bridge != null && bridge.isValid() ? bridge : null;
            }
            closeBridge();
            rebuildCount++;
            bridgeGeneration = generation.get();
            loaderIdentity = loaderId;
            long start = SystemClock.elapsedRealtime();
            try {
                DexKitNativeLoader.ensureLoaded(appContext());
                trace("bridgeCreateStart", "trigger=" + trigger
                        + " generation=" + bridgeGeneration
                        + " loaderIdentity=" + loaderIdentity);
                bridge = DexKitBridge.create(loader, true);
                long end = SystemClock.elapsedRealtime();
                trace("bridgeCreateEnd", "trigger=" + trigger
                        + " elapsedMs=" + (end - start)
                        + " dexNum=" + (bridge.isValid() ? bridge.getDexNum() : -1)
                        + " rebuild=" + rebuildCount);
                if (bridge.isValid()) {
                    bridge.setThreadNum(2);
                    log.info("resolver dexkit bridge created trigger=" + trigger
                            + " generation=" + bridgeGeneration
                            + " loaderIdentity=" + loaderIdentity
                            + " elapsedMs=" + (end - start)
                            + " dexNum=" + bridge.getDexNum()
                            + " rebuild=" + rebuildCount);
                }
                return bridge.isValid() ? bridge : null;
            } catch (Throwable throwable) {
                trace("bridgeCreateEnd", "trigger=" + trigger + " failed=" + throwable);
                log.error("resolver dexkit bridge creation failed trigger=" + trigger, throwable);
                return null;
            }
        }
    }

    boolean maybeRebuildIfStale(int expectedGeneration) {
        synchronized (lock) {
            if (bridge == null || expectedGeneration != bridgeGeneration) {
                closeBridge();
                return true;
            }
            return false;
        }
    }

    void close() {
        synchronized (lock) {
            if (bridge != null) {
                trace("resolverClosed", "dexNum="
                        + (bridge.isValid() ? bridge.getDexNum() : -1));
            }
            closeBridge();
        }
    }

    private void closeBridge() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }

    private void trace(String event, String detail) {
        BootstrapTrace current = trace;
        if (current != null) {
            current.mark(event, detail);
        }
    }

    private android.content.Context appContext() {
        return HookCoordinator.currentApplication();
    }
}
