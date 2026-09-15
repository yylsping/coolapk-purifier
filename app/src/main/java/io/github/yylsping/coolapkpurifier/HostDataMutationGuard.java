package io.github.yylsping.coolapkpurifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observable ownership boundary for persistence.
 *
 * <p>Production persistence records framework/module-owned writes here. A
 * host-private backend would have to record its mutation kind through the
 * same seam; no such backend is wired into production. The snapshot is also
 * emitted in module logs so device runs can assert the architecture invariant
 * without reading any Coolapk-owned data.</p>
 */
final class HostDataMutationGuard {
    enum HostMutation {
        FILES_DIR,
        CACHE_DIR,
        CODE_CACHE,
        RENAME,
        DELETE,
        TEMP_CREATE
    }

    private final AtomicLong hostTotal = new AtomicLong();
    private final AtomicLong filesDir = new AtomicLong();
    private final AtomicLong cacheDir = new AtomicLong();
    private final AtomicLong codeCache = new AtomicLong();
    private final AtomicLong rename = new AtomicLong();
    private final AtomicLong delete = new AtomicLong();
    private final AtomicLong tempCreate = new AtomicLong();
    private final AtomicLong remoteTotal = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> remoteReasons =
            new ConcurrentHashMap<>();

    void recordHostMutation(HostMutation mutation) {
        hostTotal.incrementAndGet();
        switch (mutation) {
            case FILES_DIR:
                filesDir.incrementAndGet();
                break;
            case CACHE_DIR:
                cacheDir.incrementAndGet();
                break;
            case CODE_CACHE:
                codeCache.incrementAndGet();
                break;
            case RENAME:
                rename.incrementAndGet();
                break;
            case DELETE:
                delete.incrementAndGet();
                break;
            case TEMP_CREATE:
                tempCreate.incrementAndGet();
                break;
            default:
                throw new AssertionError(mutation);
        }
    }

    void recordRemoteWrite(String reason) {
        remoteTotal.incrementAndGet();
        String key = reason == null || reason.isEmpty() ? "unspecified" : reason;
        remoteReasons.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    Snapshot snapshot() {
        Map<String, Long> reasons = new LinkedHashMap<>();
        remoteReasons.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reasons.put(entry.getKey(), entry.getValue().get()));
        return new Snapshot(hostTotal.get(), filesDir.get(), cacheDir.get(),
                codeCache.get(), rename.get(), delete.get(), tempCreate.get(),
                remoteTotal.get(), reasons);
    }

    static final class Snapshot {
        final long hostPrivateWrites;
        final long filesDirWrites;
        final long cacheDirWrites;
        final long codeCacheWrites;
        final long renames;
        final long deletes;
        final long tempCreates;
        final long remoteWrites;
        final Map<String, Long> remoteWriteReasons;

        Snapshot(long hostPrivateWrites, long filesDirWrites, long cacheDirWrites,
                 long codeCacheWrites, long renames, long deletes, long tempCreates,
                 long remoteWrites, Map<String, Long> remoteWriteReasons) {
            this.hostPrivateWrites = hostPrivateWrites;
            this.filesDirWrites = filesDirWrites;
            this.cacheDirWrites = cacheDirWrites;
            this.codeCacheWrites = codeCacheWrites;
            this.renames = renames;
            this.deletes = deletes;
            this.tempCreates = tempCreates;
            this.remoteWrites = remoteWrites;
            this.remoteWriteReasons = Collections.unmodifiableMap(
                    new LinkedHashMap<>(remoteWriteReasons));
        }

        String summaryLine() {
            return "hostStorageAudit"
                    + " hostPrivateWrites.total=" + hostPrivateWrites
                    + " hostPrivateWrites.filesDir=" + filesDirWrites
                    + " hostPrivateWrites.cacheDir=" + cacheDirWrites
                    + " hostPrivateWrites.codeCache=" + codeCacheWrites
                    + " hostPrivateWrites.rename=" + renames
                    + " hostPrivateWrites.delete=" + deletes
                    + " hostPrivateWrites.tempCreate=" + tempCreates
                    + " remoteWrites.total=" + remoteWrites
                    + " remoteWrites.reasons=" + remoteWriteReasons;
        }
    }
}
