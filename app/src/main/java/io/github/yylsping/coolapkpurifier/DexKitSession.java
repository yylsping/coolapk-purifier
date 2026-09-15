package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * DexKitBridge lifecycle. A bridge is bound to the runtime ClassLoader
 * generation it was created from. It is created only after runtime DEX ready
 * and rebuilt at most once when the observed loader generation changes.
 *
 * <p>The session owns the application context captured at creation time; it
 * never re-queries mutable global state after terminal cleanup. A permanent
 * native bootstrap failure is sticky for the session: later ensureBridge
 * calls rethrow the same {@link DexKitNativeLoader.LoadFailure} instead of
 * repeating the deterministically failing native load.
 *
 * <p>Single-owner close (release-hardening §3): other threads publish a
 * logical close via {@link #requestClose(String)}; the physical
 * {@code DexKitBridge.close()} runs only inside the resolver worker's query
 * transaction (or immediately when no query is in flight), exactly once.
 */
final class DexKitSession {
    interface NativeLibraryLoader {
        void ensureLoaded(Context appContext);
    }

    interface BridgeOpener {
        DexKitBridge create(ClassLoader loader);
    }

    private final ModuleLog log;
    private final BootstrapTrace trace;
    private final ClassLoader loader;
    private final Context appContext;
    private final NativeLibraryLoader nativeLibraryLoader;
    private final BridgeOpener bridgeOpener;
    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger();
    private final BridgeOwnership ownership = new BridgeOwnership(this::closeBridgePhysical);

    private DexKitBridge bridge;
    private int bridgeGeneration = -1;
    private int rebuildCount;
    private long loaderIdentity = -1L;
    private String closeReason;
    private DexKitNativeLoader.LoadFailure nativeFailure;

    DexKitSession(ModuleLog log, BootstrapTrace trace, ClassLoader loader,
                  Context appContext) {
        this(log, trace, loader, appContext,
                context -> DexKitNativeLoader.ensureLoaded(log, trace),
                candidateLoader -> DexKitBridge.create(candidateLoader, true));
    }

    DexKitSession(ModuleLog log, BootstrapTrace trace, ClassLoader loader,
                  Context appContext, NativeLibraryLoader nativeLibraryLoader,
                  BridgeOpener bridgeOpener) {
        this.log = log;
        this.trace = trace;
        this.loader = loader;
        this.appContext = appContext;
        this.nativeLibraryLoader = nativeLibraryLoader;
        this.bridgeOpener = bridgeOpener;
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
            if (nativeFailure != null) {
                throw nativeFailure;
            }
            if (ownership.isCloseRequested()) {
                // A logically closed session never creates or reuses a bridge.
                log.info("resolver dexkit bridge refused reason=closeRequested"
                        + " trigger=" + trigger);
                return null;
            }
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
                if (appContext == null) {
                    trace("bridgeCreateEnd", "trigger=" + trigger
                            + " failed=applicationContextUnavailable");
                    log.info("resolver dexkit bridge creation skipped trigger=" + trigger
                            + " reason=applicationContextUnavailable");
                    return null;
                }
                try {
                    nativeLibraryLoader.ensureLoaded(appContext);
                } catch (Exception | LinkageError failure) {
                    nativeFailure = failure instanceof DexKitNativeLoader.LoadFailure
                            ? (DexKitNativeLoader.LoadFailure) failure
                            : new DexKitNativeLoader.LoadFailure("nativeLibraryLoader", failure);
                    trace("nativeBootstrapFailed", nativeFailure.getMessage());
                    log.error("resolver NATIVE_BOOTSTRAP_FAILED", nativeFailure);
                    throw nativeFailure;
                }
                trace("bridgeCreateStart", "trigger=" + trigger
                        + " generation=" + bridgeGeneration
                        + " loaderIdentity=" + loaderIdentity);
                bridge = bridgeOpener.create(loader);
                if (bridge == null) {
                    trace("bridgeCreateEnd", "trigger=" + trigger
                            + " failed=bridgeOpenerReturnedNull");
                    return null;
                }
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
            } catch (DexKitNativeLoader.LoadFailure failure) {
                throw failure;
            } catch (Throwable throwable) {
                trace("bridgeCreateEnd", "trigger=" + trigger + " failed=" + throwable);
                log.error("resolver dexkit bridge creation failed trigger=" + trigger, throwable);
                return null;
            }
        }
    }

    /**
     * Starts the resolver worker's query transaction. The bridge may only be
     * created/queried inside it; {@link #endQuery()} performs the deferred
     * physical close when another thread requested one meanwhile.
     *
     * @return false when the session is already closing: no new work starts.
     */
    boolean beginQuery() {
        return ownership.beginTransaction();
    }

    void endQuery() {
        ownership.endTransaction();
    }

    boolean isCloseRequested() {
        return ownership.isCloseRequested();
    }

    /**
     * Logical close, safe from any thread. The physical bridge close happens
     * immediately only when no query transaction is active; otherwise the
     * owner transaction closes it exactly once on {@link #endQuery()}.
     */
    void requestClose(String reason) {
        synchronized (lock) {
            closeReason = reason;
        }
        ownership.requestClose();
    }

    /** Physical close; only ever invoked by {@link BridgeOwnership}. */
    private void closeBridgePhysical() {
        synchronized (lock) {
            if (bridge != null) {
                trace("resolverClosed", "reason=" + closeReason
                        + " dexNum=" + (bridge.isValid() ? bridge.getDexNum() : -1));
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

}
