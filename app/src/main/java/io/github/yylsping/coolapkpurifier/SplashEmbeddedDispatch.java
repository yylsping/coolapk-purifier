package io.github.yylsping.coolapkpurifier;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-fragment-instance dispatch state for the embedded splash UI cleaner.
 *
 * <p>Identity is reference identity through a weak key, never a bare
 * identityHashCode integer: hash collisions cannot alias two live instances,
 * and dead instances are reclaimed instead of accumulating. The PENDING mark
 * is applied synchronously before the runnable is posted, so a lifecycle
 * re-entry inside the pending window cannot enqueue a second finish.
 *
 * <p>SENT is terminal: once {@code setFragmentResult} has been accepted by
 * the FragmentManager the result may still be delivered later, so a delayed
 * "still added" observation (UNCONFIRMED) is NOT proof the signal failed and
 * never triggers an automatic re-send. Only a genuine dispatch exception
 * (DISPATCH_FAILED) makes a later lifecycle re-entry eligible to try again.
 */
final class SplashEmbeddedDispatch {
    enum State {
        NONE,
        /** Finish runnable posted, not yet executed. */
        PENDING,
        /** Finish signal successfully submitted to the FragmentManager. */
        SENT,
        /** setFragmentResult itself failed; a later lifecycle enter may retry. */
        DISPATCH_FAILED,
        /** Signal sent but removal not observed within the confirmation window. */
        UNCONFIRMED
    }

    /** Weak key with reference-identity semantics (== on the referent). */
    private static final class InstanceKey extends WeakReference<Object> {
        private final int hash;

        InstanceKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstanceKey)) {
                return false;
            }
            Object self = get();
            Object theirs = ((InstanceKey) other).get();
            return self != null && self == theirs;
        }
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Map<InstanceKey, State> states = new HashMap<>();

    /**
     * Atomically marks the instance PENDING when a finish may be enqueued.
     * Returns false when a dispatch is pending, was sent, or was left
     * unconfirmed; only NONE and DISPATCH_FAILED are eligible.
     */
    synchronized boolean tryMarkPending(Object instance) {
        if (instance == null) {
            return false;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        State current = states.get(key);
        if (current == State.PENDING || current == State.SENT
                || current == State.UNCONFIRMED) {
            return false;
        }
        states.put(key, State.PENDING);
        return true;
    }

    /** Dispatch succeeded: PENDING → SENT. The instance is done for good. */
    synchronized void markSent(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        states.put(new InstanceKey(instance, queue), State.SENT);
    }

    /** setFragmentResult itself threw: PENDING → DISPATCH_FAILED, retryable. */
    synchronized void markDispatchFailed(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        if (states.get(key) != State.SENT) {
            states.put(key, State.DISPATCH_FAILED);
        }
    }

    /**
     * Confirmation window elapsed with the fragment still added: SENT →
     * UNCONFIRMED. Terminal — the accepted result may still be delivered by
     * the host, so no automatic retry is allowed.
     */
    synchronized void markUnconfirmed(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        if (states.get(key) == State.SENT) {
            states.put(key, State.UNCONFIRMED);
        }
    }

    /**
     * Drops a PENDING claim without sending (e.g. the host already removed
     * the fragment on its own before the posted runnable ran).
     */
    synchronized void clearPending(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        if (states.get(key) == State.PENDING) {
            states.remove(key);
        }
    }

    synchronized State stateOf(Object instance) {
        if (instance == null) {
            return State.NONE;
        }
        expunge();
        State state = states.get(new InstanceKey(instance, queue));
        return state == null ? State.NONE : state;
    }

    /** Live-entry count; only for tests and diagnostics. */
    synchronized int trackedCount() {
        expunge();
        return states.size();
    }

    private void expunge() {
        for (java.lang.ref.Reference<? extends Object> ref = queue.poll();
                ref != null; ref = queue.poll()) {
            states.remove(ref);
        }
    }
}
