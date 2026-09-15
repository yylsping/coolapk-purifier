package io.github.yylsping.coolapkpurifier;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Module-process core used only by the visible configuration Activity. It owns
 * the official libxposed service binding and executes the fixed probe/read/write
 * grammar against framework-owned RemotePreferences. Never logs payloads.
 */
final class ModuleStorageServiceCore {
    private static final long SERVICE_WAIT_MILLIS = 2_500L;
    private static final Object SERVICE_LOCK = new Object();
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();
    private static volatile XposedService service;

    private ModuleStorageServiceCore() {
    }

    static void ensureServiceListener() {
        if (LISTENER_REGISTERED.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService bound) {
                    synchronized (SERVICE_LOCK) {
                        service = bound;
                        SERVICE_LOCK.notifyAll();
                    }
                }

                @Override
                public void onServiceDied(XposedService dead) {
                    synchronized (SERVICE_LOCK) {
                        if (service == dead) {
                            service = null;
                        }
                        SERVICE_LOCK.notifyAll();
                    }
                }
            });
        }
    }

    static Bundle dispatch(String method, String key, Bundle extras) {
        ensureServiceListener();
        if (!ModuleStorageContract.isValidMethod(method)) {
            return failure("methodRejected");
        }
        XposedService current = awaitService();
        if (current == null) {
            return failure("serviceUnavailable");
        }
        try {
            if (current.getApiVersion() < XposedService.API_101
                    || (current.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0L) {
                return failure("remoteCapabilityUnavailable");
            }
            SharedPreferences preferences = current.getRemotePreferences(
                    ModuleStorageContract.REMOTE_GROUP);
            if (ModuleStorageContract.METHOD_PROBE.equals(method)) {
                return probe(preferences, current);
            }
            if (!ModuleStorageContract.isValidKey(key)) {
                return failure("keyRejected");
            }
            if (ModuleStorageContract.METHOD_READ.equals(method)) {
                return read(preferences, key);
            }
            return write(preferences, key, extras);
        } catch (Throwable failure) {
            return failure("serviceError:" + failure.getClass().getSimpleName());
        }
    }

    private static Bundle probe(SharedPreferences preferences, XposedService current) {
        String before = preferences.getString(ModuleStorageContract.PROBE_KEY, null);
        boolean wrote = false;
        boolean committed = true;
        if (!ModuleStorageContract.PROBE_VALUE.equals(before)) {
            committed = preferences.edit().putString(
                    ModuleStorageContract.PROBE_KEY,
                    ModuleStorageContract.PROBE_VALUE).commit();
            wrote = committed;
        }
        boolean accepted = committed && ModuleStorageContract.PROBE_VALUE.equals(
                preferences.getString(ModuleStorageContract.PROBE_KEY, null));
        Bundle result = accepted ? success() : failure("probeRejected");
        result.putBoolean(ModuleStorageContract.RESULT_WROTE, wrote);
        if (accepted) {
            result.putString(ModuleStorageContract.RESULT_DETAIL,
                    "serviceApi=" + current.getApiVersion()
                            + ",framework=" + bounded(current.getFrameworkName(), 48));
        }
        return result;
    }

    private static Bundle read(SharedPreferences preferences, String key) {
        String value = preferences.getString(key, null);
        if (value != null && utf8Length(value) > ModuleStorageContract.maxBytesForKey(key)) {
            return failure("valueTooLarge");
        }
        Bundle result = success();
        result.putBoolean(ModuleStorageContract.RESULT_PRESENT, value != null);
        if (value != null) {
            result.putString(ModuleStorageContract.EXTRA_VALUE, value);
        }
        return result;
    }

    private static Bundle write(SharedPreferences preferences, String key, Bundle extras) {
        if (extras == null) {
            return failure("extrasMissing");
        }
        String value = extras.getString(ModuleStorageContract.EXTRA_VALUE);
        String reason = extras.getString(ModuleStorageContract.EXTRA_REASON);
        if (value == null || utf8Length(value) > ModuleStorageContract.maxBytesForKey(key)) {
            return failure("valueRejected");
        }
        if (!ModuleStorageContract.isValidWriteReason(key, reason)) {
            return failure("reasonRejected");
        }
        boolean committed = preferences.edit().putString(key, value).commit();
        boolean accepted = committed && value.equals(preferences.getString(key, null));
        Bundle result = accepted ? success() : failure("commitRejected");
        result.putBoolean(ModuleStorageContract.RESULT_WROTE, committed);
        return result;
    }

    private static XposedService awaitService() {
        long deadline = SystemClock.elapsedRealtime() + SERVICE_WAIT_MILLIS;
        synchronized (SERVICE_LOCK) {
            while (service == null) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    SERVICE_LOCK.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return service;
        }
    }

    private static Bundle success() {
        Bundle result = new Bundle();
        result.putBoolean(ModuleStorageContract.RESULT_SUCCESS, true);
        result.putString(ModuleStorageContract.RESULT_DETAIL, "ok");
        return result;
    }

    static Bundle failure(String detail) {
        Bundle result = new Bundle();
        result.putBoolean(ModuleStorageContract.RESULT_SUCCESS, false);
        result.putString(ModuleStorageContract.RESULT_DETAIL, bounded(detail, 96));
        return result;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return "unknown";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }
}
