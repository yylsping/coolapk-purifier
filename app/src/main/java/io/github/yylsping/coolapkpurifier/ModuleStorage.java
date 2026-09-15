package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Single ownership boundary for every byte used by the injected module.
 *
 * <p>The Coolapk process reads configuration directly from the framework-owned
 * libxposed RemotePreferences view. That view is deliberately treated as
 * read-only: configuration writes are performed only by the module's visible
 * {@link ModuleConfigActivity}. Resolver targets use the same read-only
 * framework snapshot plus an in-process write overlay; the visible Activity
 * can persist that bounded overlay on the user's next settings visit. Normal
 * Coolapk startup therefore neither starts the module process nor writes into
 * Coolapk's private directories. Missing or unavailable framework storage
 * fails closed without a host-private fallback.</p>
 */
final class ModuleStorage {
    static final String INVARIANT = "ZERO_HOST_PRIVATE_WRITE";

    private final ConfigStore configStore;
    private final ResolverCacheStore cacheStore;
    private final DiagnosticSink diagnosticSink;
    private final HostDataMutationGuard guard;
    private final ReadOnlyRemoteBridge remoteBridge;
    private final ModuleLog log;

    private ModuleStorage(ConfigStore configStore, ResolverCacheStore cacheStore,
                          DiagnosticSink diagnosticSink, HostDataMutationGuard guard,
                          ReadOnlyRemoteBridge remoteBridge, ModuleLog log) {
        this.configStore = configStore;
        this.cacheStore = cacheStore;
        this.diagnosticSink = diagnosticSink;
        this.guard = guard;
        this.remoteBridge = remoteBridge;
        this.log = log;
    }

    /** Builds the graph without touching the target Context or filesystem. */
    static ModuleStorage framework(XposedModule module, ModuleLog log) {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        DiagnosticSink diagnostics = new RingDiagnosticSink(128, log);
        boolean advertised = false;
        try {
            advertised = module != null
                    && (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0L;
        } catch (Throwable failure) {
            info(log, "storage injected capability read failed error=" + describe(failure));
        }
        ReadOnlyRemoteBridge bridge = new FrameworkReadOnlyBridge(module, advertised, log);
        ConfigStore config = new ReadOnlyRemoteBlobStore(
                bridge, ModuleStorageContract.CONFIG_KEY);
        ResolverCacheStore cache = new ReadThroughMemoryBlobStore(
                bridge, ModuleStorageContract.CACHE_KEY);
        return new ModuleStorage(config, cache, diagnostics, guard, bridge, log);
    }

    static ModuleStorage inMemoryForTest() {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        MemoryBlobStore config = new MemoryBlobStore("testMemory", guard);
        MemoryBlobStore cache = new MemoryBlobStore("testMemory", guard);
        return new ModuleStorage(config, cache, new RingDiagnosticSink(128, null),
                guard, null, null);
    }

    static ModuleStorage readOnlyBackedForTest(ReadOnlyRemoteBridge bridge) {
        HostDataMutationGuard guard = new HostDataMutationGuard();
        ConfigStore config = new ReadOnlyRemoteBlobStore(
                bridge, ModuleStorageContract.CONFIG_KEY);
        return new ModuleStorage(config, new ReadThroughMemoryBlobStore(
                bridge, ModuleStorageContract.CACHE_KEY),
                new RingDiagnosticSink(128, null), guard, bridge, null);
    }

    /** Performs one read-only framework capability probe at Application.attach. */
    void attachContext(Context ignored) {
        if (remoteBridge == null) {
            return;
        }
        ProbeResult probe = remoteBridge.connect();
        info(log, "storage capability backend=" + remoteBridge.backendName()
                + " available=" + probe.available
                + " probeWrite=false"
                + " detail=" + probe.detail
                + " configMode=frameworkReadOnly"
                + " cacheMode=frameworkReadOnly+processMemoryPendingHandoff"
                + " moduleProcessStartRequired=false"
                + " fallback=" + (probe.available ? "none" : "defaultsOrLegacyReadOnly"));
    }

    ConfigStore configStore() {
        return configStore;
    }

    ResolverCacheStore resolverCacheStore() {
        return cacheStore;
    }

    DiagnosticSink diagnosticSink() {
        return diagnosticSink;
    }

    HostDataMutationGuard mutationGuard() {
        return guard;
    }

    boolean isRemoteAvailable() {
        return configStore.isAvailable();
    }

    String auditLine() {
        return "invariant=" + INVARIANT + " " + guard.snapshot().summaryLine()
                + " configBackend=" + configStore.backendName()
                + " cacheBackend=" + cacheStore.backendName()
                + " diagnosticBackend=" + diagnosticSink.backendName();
    }

    interface ReadOnlyRemoteBridge {
        ProbeResult connect();
        byte[] read(String key);
        boolean isAvailable();
        String backendName();
    }

    static final class ProbeResult {
        final boolean available;
        final String detail;

        ProbeResult(boolean available, String detail) {
            this.available = available;
            this.detail = detail;
        }
    }

    /** Direct framework reader used only inside the injected target process. */
    private static final class FrameworkReadOnlyBridge implements ReadOnlyRemoteBridge {
        private final XposedModule module;
        private final boolean capabilityAdvertised;
        private final ModuleLog log;
        private SharedPreferences preferences;
        private boolean attempted;
        private boolean available;

        FrameworkReadOnlyBridge(XposedModule module, boolean capabilityAdvertised, ModuleLog log) {
            this.module = module;
            this.capabilityAdvertised = capabilityAdvertised;
            this.log = log;
        }

        @Override
        public synchronized ProbeResult connect() {
            if (attempted) {
                return new ProbeResult(available, "alreadyAttempted");
            }
            attempted = true;
            if (!capabilityAdvertised || module == null) {
                return new ProbeResult(false, "injectedCapabilityNotAdvertised");
            }
            try {
                SharedPreferences candidate = module.getRemotePreferences(
                        ModuleStorageContract.REMOTE_GROUP);
                // Force one bounded keyed read. Merely obtaining the proxy would
                // not prove that the framework-side channel is usable, while
                // getAll() could needlessly marshal the resolver cache.
                candidate.getString(ModuleStorageContract.CONFIG_KEY, null);
                preferences = candidate;
                available = true;
                return new ProbeResult(true, "frameworkRemotePreferencesReadable");
            } catch (Throwable failure) {
                preferences = null;
                available = false;
                info(log, "storage direct remote read probe failed error=" + describe(failure));
                return new ProbeResult(false, describe(failure));
            }
        }

        @Override
        public synchronized byte[] read(String key) {
            if (!available || preferences == null
                    || !ModuleStorageContract.isValidKey(key)) {
                return null;
            }
            try {
                String value = preferences.getString(key, null);
                if (value == null) {
                    return null;
                }
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                return bytes.length <= ModuleStorageContract.maxBytesForKey(key)
                        ? bytes : null;
            } catch (Throwable failure) {
                info(log, "storage direct remote read failed key=" + key
                        + " error=" + describe(failure));
                return null;
            }
        }

        @Override
        public synchronized boolean isAvailable() {
            return available;
        }

        @Override
        public synchronized String backendName() {
            return available ? "libxposedFrameworkRemotePreferencesReadOnly"
                    : "remotePreferencesReadUnavailable";
        }
    }

    private static final class ReadOnlyRemoteBlobStore implements ConfigStore {
        private final ReadOnlyRemoteBridge bridge;
        private final String key;

        ReadOnlyRemoteBlobStore(ReadOnlyRemoteBridge bridge, String key) {
            this.bridge = bridge;
            this.key = key;
        }

        @Override
        public byte[] read() {
            return bridge.read(key);
        }

        /** Injected code never owns a writable framework preference handle. */
        @Override
        public boolean write(byte[] value, String reason) {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return bridge.isAvailable();
        }

        @Override
        public String backendName() {
            return bridge.backendName();
        }

        @Override
        public int persistentWriteCount() {
            return 0;
        }
    }

    /**
     * Read-through resolver cache with a process-local write overlay. Resolver
     * work remains immediately reusable in the current process, while a later
     * user-opened configuration Activity may hand the bounded snapshot to the
     * module-owned writer. No injected write reaches framework or host disk.
     */
    static final class ReadThroughMemoryBlobStore implements ResolverCacheStore {
        private final ReadOnlyRemoteBridge bridge;
        private final String key;
        private byte[] overlay;

        ReadThroughMemoryBlobStore(ReadOnlyRemoteBridge bridge, String key) {
            this.bridge = bridge;
            this.key = key;
        }

        @Override
        public synchronized byte[] read() {
            if (overlay != null) {
                return overlay.clone();
            }
            byte[] remote = bridge.read(key);
            return remote == null ? null : remote.clone();
        }

        @Override
        public synchronized boolean write(byte[] value, String reason) {
            if (value == null || value.length > ModuleStorageContract.maxBytesForKey(key)
                    || !ModuleStorageContract.isValidWriteReason(key, reason)) {
                return false;
            }
            overlay = value.clone();
            return true;
        }

        @Override
        public boolean isAvailable() {
            return bridge.isAvailable();
        }

        @Override
        public String backendName() {
            return bridge.isAvailable()
                    ? "frameworkReadOnly+processMemoryPendingHandoff"
                    : "processMemoryPendingHandoff";
        }

        @Override
        public int persistentWriteCount() {
            return 0;
        }
    }

    /** Writable fake store used by serialization and policy unit tests only. */
    static final class MemoryBlobStore implements ConfigStore, ResolverCacheStore {
        private final String backend;
        private final HostDataMutationGuard guard;
        private byte[] bytes;
        private boolean failWrites;
        private int writeCount;

        MemoryBlobStore(String backend, HostDataMutationGuard guard) {
            this.backend = backend;
            this.guard = guard;
        }

        synchronized void seed(byte[] value) {
            bytes = value == null ? null : value.clone();
        }

        synchronized void setFailWrites(boolean value) {
            failWrites = value;
        }

        @Override
        public synchronized byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public synchronized boolean write(byte[] value, String reason) {
            if (failWrites || value == null) {
                return false;
            }
            bytes = value.clone();
            writeCount++;
            guard.recordRemoteWrite(reason);
            return true;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String backendName() {
            return backend;
        }

        @Override
        public synchronized int persistentWriteCount() {
            return writeCount;
        }
    }

    static final class RingDiagnosticSink implements DiagnosticSink {
        private final int capacity;
        private final ModuleLog log;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        RingDiagnosticSink(int capacity, ModuleLog log) {
            this.capacity = Math.max(1, capacity);
            this.log = log;
        }

        @Override
        public synchronized void record(String line) {
            if (lines.size() == capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
            info(log, "bootstrapTrace " + line.trim());
        }

        @Override
        public synchronized List<String> snapshot() {
            return new ArrayList<>(lines);
        }

        @Override
        public String backendName() {
            return "boundedMemory+frameworkLog";
        }

        @Override
        public int persistentWriteCount() {
            return 0;
        }
    }

    private static void info(ModuleLog log, String message) {
        if (log != null) {
            log.info(message);
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + ":"
                + (message == null ? "<null>" : sanitize(message));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "<null>";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
    }
}

interface AtomicBlobStore {
    byte[] read();
    boolean write(byte[] value, String reason);
    boolean isAvailable();
    String backendName();
    int persistentWriteCount();
}

interface ConfigStore extends AtomicBlobStore { }

interface ResolverCacheStore extends AtomicBlobStore { }

interface DiagnosticSink {
    void record(String line);
    List<String> snapshot();
    String backendName();
    int persistentWriteCount();
}
