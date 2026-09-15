package io.github.yylsping.coolapkpurifier;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

final class ModuleLog {
    private static final String TAG = "CoolapkAdBlock";

    private final XposedModule module;

    ModuleLog(XposedModule module) {
        this.module = module;
    }

    void info(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // Diagnostics must never affect host behavior.
        }
        if (module != null) {
            try {
                module.log(Log.INFO, TAG, message);
            } catch (Throwable failure) {
                androidLogFailure("LSPosed info log sink failed", failure);
            }
        }
    }

    void error(String message, Throwable throwable) {
        try {
            if (throwable == null) {
                Log.e(TAG, message);
            } else {
                Log.e(TAG, message, throwable);
            }
        } catch (Throwable ignored) {
            // Preserve the framework sink attempt below.
        }
        if (module != null) {
            try {
                if (throwable == null) {
                    module.log(Log.ERROR, TAG, message);
                } else {
                    module.log(Log.ERROR, TAG, message, throwable);
                }
            } catch (Throwable failure) {
                androidLogFailure("LSPosed error log sink failed", failure);
            }
        }
    }

    private static void androidLogFailure(String message, Throwable failure) {
        try {
            Log.e(TAG, message, failure);
        } catch (Throwable ignored) {
            // There is no remaining safe diagnostics sink.
        }
    }
}
