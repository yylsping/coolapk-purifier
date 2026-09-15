package io.github.yylsping.coolapkpurifier;

import android.os.SystemClock;

import java.util.EnumMap;

/**
 * Low-noise, process-local record of this module's feature exposure.
 *
 * <p>No host payload is accepted by this API. Callers can only report the
 * feature and one of four bounded lifecycle events. The default clock is
 * elapsed realtime so entries remain useful for ordering without recording
 * wall-clock or account data. A line is emitted only for the first occurrence
 * of each event; complete snapshots are emitted explicitly by the coordinator
 * at terminal lifecycle points.
 */
final class FeatureExposureLedger {
    static final int DEFAULT_MAX_MODIFIED_COUNT = 1_000_000;
    private static final long UNSET = -1L;

    interface Clock {
        long elapsedRealtime();
    }

    interface Emitter {
        void emit(String line);
    }

    private static final class Entry {
        boolean hookInstalled;
        long firstHookInstalledAtElapsed = UNSET;
        long firstEnteredAtElapsed = UNSET;
        long firstSampleSeenAtElapsed = UNSET;
        long firstModifiedAtElapsed = UNSET;
        long lastModifiedAtElapsed = UNSET;
        int modifiedCount;
        boolean modifiedCountCapped;
    }

    private final EnumMap<PurifierConfig.Feature, Entry> entries =
            new EnumMap<>(PurifierConfig.Feature.class);
    private final Clock clock;
    private final Emitter emitter;
    private final long originElapsed;
    private final int maxModifiedCount;

    FeatureExposureLedger(Emitter emitter) {
        this(SystemClock::elapsedRealtime, emitter, DEFAULT_MAX_MODIFIED_COUNT);
    }

    FeatureExposureLedger(Clock clock, Emitter emitter, int maxModifiedCount) {
        if (clock == null) {
            throw new IllegalArgumentException("clock required");
        }
        if (maxModifiedCount < 1) {
            throw new IllegalArgumentException("positive count bound required");
        }
        this.clock = clock;
        this.emitter = emitter;
        this.maxModifiedCount = maxModifiedCount;
        this.originElapsed = clock.elapsedRealtime();
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            entries.put(feature, new Entry());
        }
    }

    synchronized void recordHookInstalled(PurifierConfig.Feature feature) {
        Entry entry = entry(feature);
        if (entry.hookInstalled) {
            return;
        }
        entry.hookInstalled = true;
        entry.firstHookInstalledAtElapsed = nowRelative();
        emitFirst("HOOK_INSTALLED", feature, entry);
    }

    synchronized void recordEntered(PurifierConfig.Feature feature) {
        Entry entry = entry(feature);
        if (entry.firstEnteredAtElapsed != UNSET) {
            return;
        }
        entry.firstEnteredAtElapsed = nowRelative();
        emitFirst("ENTERED", feature, entry);
    }

    synchronized void recordSampleSeen(PurifierConfig.Feature feature) {
        Entry entry = entry(feature);
        if (entry.firstSampleSeenAtElapsed != UNSET) {
            return;
        }
        entry.firstSampleSeenAtElapsed = nowRelative();
        emitFirst("SAMPLE_SEEN", feature, entry);
    }

    synchronized void recordModified(PurifierConfig.Feature feature) {
        Entry entry = entry(feature);
        long now = nowRelative();
        boolean first = entry.firstModifiedAtElapsed == UNSET;
        if (first) {
            entry.firstModifiedAtElapsed = now;
        }
        entry.lastModifiedAtElapsed = now;
        if (entry.modifiedCount < maxModifiedCount) {
            entry.modifiedCount++;
        } else {
            entry.modifiedCountCapped = true;
        }
        if (first) {
            emitFirst("MODIFIED", feature, entry);
        }
    }

    /** Complete, deterministic one-line snapshot; no host data is accepted. */
    synchronized String summaryLine(String phase) {
        StringBuilder sb = new StringBuilder("featureExposure phase=")
                .append(phase == null ? "explicit" : phase)
                .append(" originElapsed=").append(originElapsed);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            sb.append(' ').append(feature.key).append('=');
            appendEntry(sb, entries.get(feature));
        }
        return sb.toString();
    }

    private Entry entry(PurifierConfig.Feature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("feature required");
        }
        return entries.get(feature);
    }

    private long nowRelative() {
        return Math.max(0L, clock.elapsedRealtime() - originElapsed);
    }

    private void emitFirst(String event, PurifierConfig.Feature feature, Entry entry) {
        if (emitter == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("featureExposure event=FIRST_")
                .append(event).append(" feature=").append(feature.key).append(' ');
        appendEntry(sb, entry);
        try {
            emitter.emit(sb.toString());
        } catch (Throwable ignored) {
            // Diagnostics must never change host behavior or hook outcomes.
        }
    }

    private static void appendEntry(StringBuilder sb, Entry entry) {
        sb.append("{hookInstalled=").append(entry.hookInstalled)
                .append(" firstHookInstalledAtElapsed=")
                .append(display(entry.firstHookInstalledAtElapsed))
                .append(" firstEnteredAtElapsed=").append(display(entry.firstEnteredAtElapsed))
                .append(" firstSampleSeenAtElapsed=")
                .append(display(entry.firstSampleSeenAtElapsed))
                .append(" firstModifiedAtElapsed=")
                .append(display(entry.firstModifiedAtElapsed))
                .append(" modifiedCount=").append(entry.modifiedCount)
                .append(" modifiedCountCapped=").append(entry.modifiedCountCapped)
                .append(" lastModifiedAtElapsed=")
                .append(display(entry.lastModifiedAtElapsed))
                .append('}');
    }

    private static String display(long value) {
        return value == UNSET ? "-" : Long.toString(value);
    }
}
