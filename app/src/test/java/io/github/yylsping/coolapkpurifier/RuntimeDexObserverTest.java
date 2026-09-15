package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.coolapk.market.view.main.MainActivity;
import com.coolapk.market.shell.Stub;

import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for the lost-observer defect: after close(), rearm() must
 * reinstall the loadClass hooks instead of silently keeping the observer dead.
 */
public final class RuntimeDexObserverTest {
    private RecordingInstaller installer;
    private RecordingListener listener;
    private RuntimeDexObserver observer;

    @Before
    public void setUp() {
        installer = new RecordingInstaller();
        listener = new RecordingListener();
        observer = new RuntimeDexObserver(new ModuleLog(null), listener, installer);
        installer.observer = observer;
    }

    @Test
    public void installArmsObserverAndInstallsHooksOnce() {
        observer.install();
        observer.install();

        assertEquals(1, installer.installCount);
        assertTrue(observer.isArmed());
    }

    @Test
    public void businessClassFiresOnceAndDisarms() {
        observer.install();

        observer.onClassLoaded(MainActivity.class);
        observer.onClassLoaded(MainActivity.class);

        assertEquals(1, listener.triggers.size());
        assertFalse(observer.isArmed());
    }

    @Test
    public void nonBusinessClassDoesNotConsumeTheArming() {
        observer.install();

        observer.onClassLoaded(String.class);
        observer.onClassLoaded(Stub.class);

        assertEquals(0, listener.triggers.size());
        assertTrue(observer.isArmed());
    }

    @Test
    public void rearmAfterFireReinstallsHooksAndCanFireAgain() {
        observer.install();
        observer.onClassLoaded(MainActivity.class);
        assertEquals(1, installer.installCount);

        observer.rearm();

        // The defect: rearm() used to leave the observer armed but hookless.
        assertEquals(2, installer.installCount);
        assertTrue(observer.isArmed());

        observer.onClassLoaded(MainActivity.class);
        assertEquals(2, listener.triggers.size());
    }

    @Test
    public void rearmWhileHooksInstalledDoesNotDuplicateHooks() {
        observer.install();

        observer.rearm();

        assertEquals(1, installer.installCount);
        assertTrue(observer.isArmed());
    }

    @Test
    public void notifyFirstActivityPreIsSingleShot() {
        observer.install();

        observer.notifyFirstActivityPre(getClass().getClassLoader());
        observer.notifyFirstActivityPre(getClass().getClassLoader());

        assertEquals(1, listener.triggers.size());
        assertEquals("firstActivityPre", listener.triggers.get(0));
    }

    /**
     * Tightened race: two threads rearming while the installer is still
     * running (handles not yet registered) used to both observe an empty
     * handle list and install the loadClass hooks twice.
     */
    @Test
    public void concurrentRearmsInstallHooksExactlyOnce() throws Exception {
        installer.slowMode = true;
        observer.install();
        // Close the hooks (single-shot fire) so rearm actually has to
        // reinstall; the race window is between rearm() and handle
        // registration inside the still-running installer.
        observer.onClassLoaded(MainActivity.class);
        assertEquals(1, installer.installCount);

        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        Runnable rearm = () -> {
            try {
                barrier.await();
                observer.rearm();
            } catch (Exception ignored) {
            }
        };
        Thread first = new Thread(rearm, "rearm-1");
        Thread second = new Thread(rearm, "rearm-2");
        first.start();
        second.start();
        first.join(5_000);
        second.join(5_000);

        assertEquals(2, installer.installCount);
        assertTrue(observer.isArmed());
    }

    /**
     * §4: a failed unhook must NOT retire the ledger entry — the handle stays
     * active/retained; a later successful close retires it for real.
     */
    @Test
    public void failedUnhookKeepsLedgerActiveUntilRealUnhook() {
        HookLedger ledger = new HookLedger();
        RuntimeDexObserver observed = new RuntimeDexObserver(
                new ModuleLog(null), listener, installer, ledger);
        installer.observer = observed;
        ThrowingHandle flaky = new ThrowingHandle();
        observed.addHandle(flaky, "coolapk-runtime-dex-1");
        observed.addHandle(new FakeHandle(), "coolapk-runtime-dex-2");

        flaky.fail = true;
        observed.close();

        assertTrue("failed unhook must keep the ledger entry active",
                ledger.isActive("coolapk-runtime-dex-1"));
        assertFalse(ledger.isActive("coolapk-runtime-dex-2"));
        assertTrue(ledger.hasActiveFrameworkHooks());

        flaky.fail = false;
        observed.close();

        assertFalse("successful unhook retires the ledger entry",
                ledger.isActive("coolapk-runtime-dex-1"));
        assertFalse(ledger.hasActiveFrameworkHooks());
    }

    /**
     * §9: the second loadClass hook failing must roll back the first — no
     * half-installed observer, no ledger entry, not armed.
     */
    @Test
    public void partialInstallRollsBackFirstHook() {
        HookLedger ledger = new HookLedger();
        RuntimeDexObserver observed = new RuntimeDexObserver(
                new ModuleLog(null), listener, installer, ledger);
        ThrowingHandle first = new ThrowingHandle();
        try {
            RuntimeDexObserver.installLoadClassSet(new RuntimeDexObserver.RawHooker() {
                private int calls;

                @Override
                public io.github.libxposed.api.XposedInterface.HookHandle hook(
                        java.lang.reflect.Method method, String id) {
                    calls++;
                    if (calls == 2) {
                        throw new IllegalStateException("second hook install failed");
                    }
                    return first;
                }
            }, observed);
            throw new AssertionError("install must propagate the failure");
        } catch (Throwable expected) {
            assertEquals("second hook install failed", expected.getMessage());
        }
        assertEquals(1, first.unhookCount);
        assertFalse(observed.isArmed());
        assertFalse(ledger.hasActiveFrameworkHooks());
    }

    /**
     * §9: an installer that throws mid-install leaves the observer disarmed,
     * and a later rearm retries the install instead of skipping it.
     */
    @Test
    public void failedInstallDisarmsAndRearmRetries() {
        HookLedger ledger = new HookLedger();
        RecordingInstaller flakyInstaller = new RecordingInstaller();
        RuntimeDexObserver observed = new RuntimeDexObserver(
                new ModuleLog(null), listener, flakyInstaller, ledger);
        flakyInstaller.observer = observed;
        flakyInstaller.failNext = true;

        observed.install();
        assertFalse("failed install must not leave the observer armed",
                observed.isArmed());

        observed.rearm();
        assertTrue(observed.isArmed());
        assertEquals(2, flakyInstaller.installCount);
    }

    private static final class ThrowingHandle
            implements io.github.libxposed.api.XposedInterface.HookHandle {
        boolean fail;
        int unhookCount;

        @Override
        public java.lang.reflect.Executable getExecutable() {
            return null;
        }

        @Override
        public void unhook() {
            unhookCount++;
            if (fail) {
                throw new IllegalStateException("unhook failed");
            }
        }

        @Override
        public String getId() {
            return "flaky";
        }

        @Override
        public io.github.libxposed.api.XposedInterface.HookHandle replaceHook(
                io.github.libxposed.api.XposedInterface.Hooker hooker) {
            return this;
        }
    }

    private static final class RecordingInstaller implements RuntimeDexObserver.HookInstaller {
        int installCount;
        boolean slowMode;
        boolean failNext;
        RuntimeDexObserver observer;

        @Override
        public void installLoadClassHooks() {
            installCount++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("install failed");
            }
            if (slowMode) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // Mirror the real installer contract: register both handles.
            observer.addHandle(new FakeHandle());
            observer.addHandle(new FakeHandle());
        }
    }

    private static final class FakeHandle
            implements io.github.libxposed.api.XposedInterface.HookHandle {
        @Override
        public java.lang.reflect.Executable getExecutable() {
            return null;
        }

        @Override
        public void unhook() {
        }

        @Override
        public String getId() {
            return "fake";
        }

        @Override
        public io.github.libxposed.api.XposedInterface.HookHandle replaceHook(
                io.github.libxposed.api.XposedInterface.Hooker hooker) {
            return this;
        }
    }

    private static final class RecordingListener implements RuntimeDexObserver.Listener {
        final List<String> triggers = new ArrayList<>();

        @Override
        public void onRuntimeDexReady(String trigger, ClassLoader runtimeClassLoader) {
            triggers.add(trigger == null ? "" : trigger.split(":")[0]);
        }
    }
}
