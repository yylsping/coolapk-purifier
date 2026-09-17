package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-instance dispatch dedup: reference identity (never hash aliasing),
 * PENDING claimed atomically before posting, SENT is terminal, RETRYABLE
 * allows exactly one later retry.
 */
public final class SplashEmbeddedDispatchTest {
    @Test
    public void sameInstanceEntersPendingOnlyOnce() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        assertFalse("re-entry while PENDING must not re-enqueue",
                dispatch.tryMarkPending(fragment));
        assertEquals(SplashEmbeddedDispatch.State.PENDING, dispatch.stateOf(fragment));
        assertEquals(1, dispatch.trackedCount());
    }

    @Test
    public void sentIsTerminalAndNeverRetried() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markSent(fragment);
        assertEquals(SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(fragment));
        assertFalse(dispatch.tryMarkPending(fragment));
        dispatch.markRetryable(fragment);
        assertEquals("SENT must survive a late retryable mark",
                SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(fragment));
    }

    @Test
    public void failedDispatchBecomesRetryableExactlyOnce() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markRetryable(fragment);
        assertEquals(SplashEmbeddedDispatch.State.RETRYABLE, dispatch.stateOf(fragment));
        assertTrue("RETRYABLE instance may be claimed again",
                dispatch.tryMarkPending(fragment));
        assertEquals(SplashEmbeddedDispatch.State.PENDING, dispatch.stateOf(fragment));
    }

    @Test
    public void distinctInstancesWithEqualHashAndEqualsNeverAlias() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        String first = new String("splash");
        String second = new String("splash");
        assertEquals(first, second);
        assertFalse("must be two distinct instances", first == second);
        assertEquals("equal content means equal String hashCode",
                first.hashCode(), second.hashCode());

        assertTrue(dispatch.tryMarkPending(first));
        assertTrue("a distinct instance must not be blocked by identity collision",
                dispatch.tryMarkPending(second));
        dispatch.markSent(first);
        assertEquals(SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(first));
        assertEquals(SplashEmbeddedDispatch.State.PENDING, dispatch.stateOf(second));
        assertEquals(2, dispatch.trackedCount());
    }

    @Test
    public void nullAndUnknownInstancesAreInert() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();

        assertFalse(dispatch.tryMarkPending(null));
        dispatch.markSent(null);
        dispatch.markRetryable(null);
        assertEquals(SplashEmbeddedDispatch.State.NONE, dispatch.stateOf(null));
        assertEquals(SplashEmbeddedDispatch.State.NONE, dispatch.stateOf(new Object()));
        assertEquals(0, dispatch.trackedCount());
    }

    @Test
    public void clearedInstancesAreReclaimed() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();
        assertTrue(dispatch.tryMarkPending(fragment));
        assertEquals(1, dispatch.trackedCount());

        fragment = null;
        for (int i = 0; i < 10 && dispatch.trackedCount() > 0; i++) {
            System.gc();
            System.runFinalization();
        }
        assertEquals("dead instance keys must be expunged", 0, dispatch.trackedCount());
    }
}
