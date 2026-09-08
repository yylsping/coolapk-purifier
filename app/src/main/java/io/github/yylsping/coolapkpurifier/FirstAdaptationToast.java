package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Pure UI notification for the first on-device DexKit adaptation of the
 * current stableTargetIdentity. One shot per process, posted to the main
 * thread without delay and without waiting for the Toast.
 */
final class FirstAdaptationToast {
    static final String MESSAGE = "首次适配中，开屏广告可能显示一次，后续自动净化";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OnceFlag shown = new OnceFlag();
    private final ModuleLog log;

    FirstAdaptationToast(ModuleLog log) {
        this.log = log;
    }

    void showOnce(Context context) {
        if (!shown.tryOnce()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        boolean posted = mainHandler.post(() -> {
            try {
                Toast.makeText(appContext, MESSAGE, Toast.LENGTH_LONG).show();
                log.info("firstAdaptationToast shown=true once=true");
            } catch (Throwable throwable) {
                log.info("firstAdaptationToast shown=false once=true error=" + throwable);
            }
        });
        if (!posted) {
            log.info("firstAdaptationToast shown=false once=true error=postRejected");
        }
    }
}
