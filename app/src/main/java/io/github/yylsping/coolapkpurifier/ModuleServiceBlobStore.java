package io.github.yylsping.coolapkpurifier;

import android.os.Bundle;

import java.nio.charset.StandardCharsets;

/** Writable blob store reachable only from code running in the module process. */
final class ModuleServiceBlobStore implements ConfigStore, ResolverCacheStore {
    private final String key;
    private int writeCount;

    ModuleServiceBlobStore(String key) {
        if (!ModuleStorageContract.isValidKey(key)) {
            throw new IllegalArgumentException("unsupported key");
        }
        this.key = key;
    }

    static ModuleStorage.ProbeResult probe() {
        Bundle result = ModuleStorageServiceCore.dispatch(
                ModuleStorageContract.METHOD_PROBE, null, null);
        boolean available = result.getBoolean(ModuleStorageContract.RESULT_SUCCESS, false);
        return new ModuleStorage.ProbeResult(available,
                result.getString(ModuleStorageContract.RESULT_DETAIL, "unspecified"));
    }

    @Override
    public synchronized byte[] read() {
        Bundle result = ModuleStorageServiceCore.dispatch(
                ModuleStorageContract.METHOD_READ, key, null);
        if (!result.getBoolean(ModuleStorageContract.RESULT_SUCCESS, false)
                || !result.getBoolean(ModuleStorageContract.RESULT_PRESENT, false)) {
            return null;
        }
        String value = result.getString(ModuleStorageContract.EXTRA_VALUE);
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= ModuleStorageContract.maxBytesForKey(key) ? bytes : null;
    }

    @Override
    public synchronized boolean write(byte[] value, String reason) {
        if (value == null || value.length > ModuleStorageContract.maxBytesForKey(key)
                || !ModuleStorageContract.isValidWriteReason(key, reason)) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString(ModuleStorageContract.EXTRA_VALUE,
                new String(value, StandardCharsets.UTF_8));
        extras.putString(ModuleStorageContract.EXTRA_REASON, reason);
        Bundle result = ModuleStorageServiceCore.dispatch(
                ModuleStorageContract.METHOD_WRITE, key, extras);
        boolean success = result.getBoolean(ModuleStorageContract.RESULT_SUCCESS, false);
        if (success && result.getBoolean(ModuleStorageContract.RESULT_WROTE, false)) {
            writeCount++;
        }
        return success;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String backendName() {
        return "libxposedServiceRemotePreferencesForeground";
    }

    @Override
    public synchronized int persistentWriteCount() {
        return writeCount;
    }
}
