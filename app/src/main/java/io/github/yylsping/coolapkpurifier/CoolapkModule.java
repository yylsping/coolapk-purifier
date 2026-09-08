package io.github.yylsping.coolapkpurifier;

import android.app.Application;
import android.os.Build;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class CoolapkModule extends XposedModule {
    static final String TARGET_PACKAGE = "com.coolapk.market";

    private static final AtomicBoolean initialized = new AtomicBoolean();
    // HookHandle lifetime is tied to its owning object. Keep the complete graph reachable
    // from the framework-owned module entry for the target process lifetime.
    private static volatile HookCoordinator coordinator;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        String processName = currentProcessName();
        if (!TARGET_PACKAGE.equals(processName) || !initialized.compareAndSet(false, true)) {
            return;
        }

        ModuleLog log = new ModuleLog(this);
        try {
            HookCoordinator created = new HookCoordinator(this, log, param.getClassLoader());
            created.install();
            coordinator = created;
            log.info("initialized API 102 hooks in " + processName);
        } catch (Throwable throwable) {
            initialized.set(false);
            log.error("initialization failed", throwable);
        }
    }

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        try (FileInputStream stream = new FileInputStream("/proc/self/cmdline")) {
            byte[] bytes = new byte[256];
            int length = stream.read(bytes);
            if (length <= 0) {
                return "";
            }
            int end = 0;
            while (end < length && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
