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

    private static final class RecordingInstaller implements RuntimeDexObserver.HookInstaller {
        int installCount;
        boolean slowMode;
        RuntimeDexObserver observer;

        @Override
        public void installLoadClassHooks() {
            installCount++;
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
