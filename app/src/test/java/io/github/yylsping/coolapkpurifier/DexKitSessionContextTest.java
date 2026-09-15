package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class DexKitSessionContextTest {
    @Test
    public void capturedAppContextIsUsedWhenCurrentApplicationIsUnavailable() {
        Context context = new ContextWrapper(null);
        AtomicReference<Context> nativeContext = new AtomicReference<>();
        AtomicBoolean bridgeOpenAttempted = new AtomicBoolean();
        DexKitSession session = new DexKitSession(
                new ModuleLog(null), null, loader(), context,
                nativeContext::set,
                ignored -> {
                    bridgeOpenAttempted.set(true);
                    return null;
                });

        // The host JVM has no live ActivityThread/Application. Initialization
        // still reaches the bridge opener with the captured attach Context.
        assertNull(HookCoordinator.currentApplication());
        assertNull(session.ensureBridge("test"));
        assertSame(context, nativeContext.get());
        assertTrue(bridgeOpenAttempted.get());
    }

    @Test
    public void missingAppContextFailsGracefullyBeforeNativeInitialization() {
        AtomicBoolean nativeLoadAttempted = new AtomicBoolean();
        AtomicBoolean bridgeOpenAttempted = new AtomicBoolean();
        DexKitSession session = new DexKitSession(
                new ModuleLog(null), null, loader(), null,
                ignored -> nativeLoadAttempted.set(true),
                ignored -> {
                    bridgeOpenAttempted.set(true);
                    return null;
                });

        assertNull(session.ensureBridge("missing-context"));
        assertTrue(!nativeLoadAttempted.get());
        assertTrue(!bridgeOpenAttempted.get());
    }

    @Test
    public void nativeFailurePropagatesOnceAndNeverRetriesNativeLoad() {
        AtomicInteger attempts = new AtomicInteger();
        DexKitSession session = new DexKitSession(new ModuleLog(null), null, loader(),
                new ContextWrapper(null), ignored -> {
                    attempts.incrementAndGet();
                    throw new UnsatisfiedLinkError("dlopen namespace failure");
                }, ignored -> {
                    throw new AssertionError("bridge must not open");
                });
        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class, () -> session.ensureBridge("native-failure"));
        // BUG-E: the same sticky failure is rethrown; the native load is not
        // repeated by watchdog/later sessions.
        assertSame(failure, assertThrows(DexKitNativeLoader.LoadFailure.class,
                () -> session.ensureBridge("watchdog")));
        assertSame(failure, assertThrows(DexKitNativeLoader.LoadFailure.class,
                () -> session.ensureBridge("another-session")));
        assertEquals(1, attempts.get());
        assertTrue(failure.getMessage().contains(DexKitNativeLoader.FAILURE_REASON));
    }

    @Test
    public void bridgeFailureAfterNativeSuccessIsNotMisclassifiedAsNativeFailure() {
        AtomicInteger opens = new AtomicInteger();
        DexKitSession session = new DexKitSession(new ModuleLog(null), null, loader(),
                new ContextWrapper(null), ignored -> {
                }, ignored -> {
                    opens.incrementAndGet();
                    throw new IllegalStateException("bridge unavailable");
                });
        // Retryable: no sticky native failure, the opener may be retried.
        assertNull(session.ensureBridge("bridge-error"));
        assertNull(session.ensureBridge("runtime-change"));
        assertEquals(2, opens.get());
    }

    private static ClassLoader loader() {
        return new ClassLoader(null) {
        };
    }
}
