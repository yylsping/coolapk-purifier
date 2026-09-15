package io.github.yylsping.coolapkpurifier;

/**
 * Single-owner lifecycle for one physical native bridge (release-hardening §3).
 *
 * <p>The resolver worker runs queries inside transactions and is the only
 * thread that may physically close the bridge. Any other thread (watchdog
 * deadline, loader change, terminal cleanup) publishes a logical close
 * request; the physical close happens either immediately when no transaction
 * is active, or in the owner transaction's {@link #endTransaction()} —
 * exactly once.
 *
 * <p>Invariants: 1 bridge → 1 owner; query and close never run concurrently;
 * close happens exactly once. Bridge-agnostic so tests can drive it with a
 * fake close action, latches and barriers instead of a real JNI race.
 */
final class BridgeOwnership {
    /** Physical close; invoked exactly once, never while a query runs. */
    interface CloseAction {
        void close();
    }

    private final CloseAction closeAction;
    private final Object lock = new Object();
    private int activeTransactions;
    private boolean closeRequested;
    private boolean closed;

    BridgeOwnership(CloseAction closeAction) {
        this.closeAction = closeAction;
    }

    /**
     * Starts a query transaction on the calling (owner) thread.
     *
     * @return false when a close was already requested: no new work starts.
     */
    boolean beginTransaction() {
        synchronized (lock) {
            if (closeRequested) {
                return false;
            }
            activeTransactions++;
            return true;
        }
    }

    /** Ends a query transaction; physically closes when a close is pending. */
    void endTransaction() {
        CloseAction action = null;
        synchronized (lock) {
            activeTransactions--;
            if (closeRequested && activeTransactions == 0 && !closed) {
                closed = true;
                action = closeAction;
            }
        }
        if (action != null) {
            action.close();
        }
    }

    /**
     * Logical close from ANY thread. Physically closes immediately only when
     * no query is in flight; otherwise the owner transaction's
     * {@link #endTransaction()} performs it.
     */
    void requestClose() {
        CloseAction action = null;
        synchronized (lock) {
            closeRequested = true;
            if (activeTransactions == 0 && !closed) {
                closed = true;
                action = closeAction;
            }
        }
        if (action != null) {
            action.close();
        }
    }

    boolean isCloseRequested() {
        synchronized (lock) {
            return closeRequested;
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }
}
