package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-instance dispatch dedup: reference identity (never hash aliasing),
 * PENDING claimed atomically before posting, SENT is terminal, UNCONFIRMED
 * never auto-retries, only DISPATCH_FAILED is eligible again.
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
        dispatch.markDispatchFailed(fragment);
        assertEquals("SENT must survive a late dispatch-failed mark",
                SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(fragment));
    }

    @Test
    public void dispatchFailureIsRetryableExactlyOncePerReentry() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markDispatchFailed(fragment);
        assertEquals(SplashEmbeddedDispatch.State.DISPATCH_FAILED,
                dispatch.stateOf(fragment));
        assertTrue("DISPATCH_FAILED instance may be claimed again",
                dispatch.tryMarkPending(fragment));
        assertEquals(SplashEmbeddedDispatch.State.PENDING, dispatch.stateOf(fragment));
    }

    @Test
    public void unconfirmedIsTerminalAndNeverAutoRetried() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markSent(fragment);
        dispatch.markUnconfirmed(fragment);
        assertEquals(SplashEmbeddedDispatch.State.UNCONFIRMED, dispatch.stateOf(fragment));
        assertFalse("UNCONFIRMED must not auto-retry; the accepted result may "
                        + "still be delivered by the host",
                dispatch.tryMarkPending(fragment));
    }

    @Test
    public void unconfirmedOnlyAppliesAfterSent() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markUnconfirmed(fragment);
        assertEquals("PENDING must not become UNCONFIRMED",
                SplashEmbeddedDispatch.State.PENDING, dispatch.stateOf(fragment));
    }

    @Test
    public void clearPendingDropsUnsentClaim() {
        SplashEmbeddedDispatch dispatch = new SplashEmbeddedDispatch();
        Object fragment = new Object();

        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.clearPending(fragment);
        assertEquals(SplashEmbeddedDispatch.State.NONE, dispatch.stateOf(fragment));
        assertTrue(dispatch.tryMarkPending(fragment));
        dispatch.markSent(fragment);
        dispatch.clearPending(fragment);
        assertEquals("SENT must survive clearPending",
                SplashEmbeddedDispatch.State.SENT, dispatch.stateOf(fragment));
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
        dispatch.markDispatchFailed(null);
        dispatch.markUnconfirmed(null);
        dispatch.clearPending(null);
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
