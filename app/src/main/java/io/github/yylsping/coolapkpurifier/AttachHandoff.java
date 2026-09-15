package io.github.yylsping.coolapkpurifier;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application.attach handoff transaction state (release-hardening §8).
 *
 * <pre>
 * UNCLAIMED → IN_PROGRESS → COMPLETE
 *                       ↘ FAILED (terminal degraded; never retried, so a
 *                         partial handoff can never produce duplicate hooks)
 * </pre>
 *
 * Late wrapper/packer attach calls observe a non-UNCLAIMED state and are
 * no-ops: they never rewrite coordinator state and never reset a terminal
 * READY/DEGRADED.
 */
final class AttachHandoff {
    enum State {
        UNCLAIMED,
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    private final AtomicInteger state = new AtomicInteger(State.UNCLAIMED.ordinal());

    /**
     * @return true exactly once, for the caller that may run the handoff.
     */
    boolean claim() {
        return state.compareAndSet(State.UNCLAIMED.ordinal(), State.IN_PROGRESS.ordinal());
    }

    /** The minimal stable state is established; the handoff is done. */
    void complete() {
        state.set(State.COMPLETE.ordinal());
    }

    /**
     * Mid-handoff failure: explicitly terminal (fail-closed). Not retried —
     * hooks installed so far are idempotent but a second handoff attempt could
     * still duplicate partial side effects.
     */
    void fail() {
        state.set(State.FAILED.ordinal());
    }

    State state() {
        return State.values()[state.get()];
    }
}
