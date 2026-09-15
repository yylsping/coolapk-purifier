package io.github.yylsping.coolapkpurifier;

import android.os.SystemClock;

import java.util.List;
import java.util.Locale;

/** Monotonic-clock startup trace, frozen permanently after bootstrap ends. */
final class BootstrapTrace {
    private final DiagnosticSink sink;
    private final long startRealtime = SystemClock.elapsedRealtime();
    private final TraceGate gate = new TraceGate();

    BootstrapTrace(DiagnosticSink sink) {
        this.sink = sink;
    }

    boolean isFrozen() {
        return gate.isFrozen();
    }

    long elapsedSinceStart() {
        return SystemClock.elapsedRealtime() - startRealtime;
    }

    synchronized void mark(String event, String detail) {
        if (gate.isFrozen()) {
            return;
        }
        writeLine(event, detail);
    }

    /** Records one final terminal line, then freezes all future diagnostics. */
    synchronized void freeze(String event, String detail) {
        if (gate.isFrozen()) {
            return;
        }
        writeLine(event, detail);
        gate.freeze();
    }

    private void writeLine(String event, String detail) {
        long now = SystemClock.elapsedRealtime();
        String line = String.format(Locale.US,
                "rel=%6dms evt=%-22s %s%n", now - startRealtime, event, detail);
        try {
            sink.record(line);
        } catch (Throwable ignored) {
            // Diagnostics must never affect host behavior.
        }
    }

    List<String> snapshot() {
        return sink.snapshot();
    }
}
