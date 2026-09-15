package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Release-hardening §3: one bridge has exactly one owner; queries and the
 * physical close never run concurrently; close happens exactly once. Driven
 * by latches and a fake close action — no real JNI race required.
 */
public final class BridgeOwnershipTest {
    private static final class FakeBridge {
        final AtomicInteger closeCount = new AtomicInteger();
    }

    @Test
    public void closeDuringQueryIsDeferredToOwnerAndHappensExactlyOnce() throws Exception {
        FakeBridge bridge = new FakeBridge();
        BridgeOwnership ownership = new BridgeOwnership(bridge.closeCount::incrementAndGet);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);

        Thread owner = new Thread(() -> {
            assertTrue(ownership.beginTransaction());
            queryStarted.countDown();
            try {
                assertTrue("closer must request close",
                        closeRequested.await(5, TimeUnit.SECONDS));
                // Still inside the query: the bridge must NOT be closed yet.
                assertEquals(0, bridge.closeCount.get());
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                ownership.endTransaction();
            }
        }, "resolver-worker");
        owner.start();

        assertTrue(queryStarted.await(5, TimeUnit.SECONDS));
        ownership.requestClose();
        closeRequested.countDown();
        // Give the owner a fair chance to misbehave; the close must stay deferred.
        assertEquals(0, bridge.closeCount.get());
        owner.join(5_000);
        assertEquals(1, bridge.closeCount.get());
        assertTrue(ownership.isClosed());
    }

    @Test
    public void idleCloseIsImmediateAndExactlyOnce() {
        FakeBridge bridge = new FakeBridge();
        BridgeOwnership ownership = new BridgeOwnership(bridge.closeCount::incrementAndGet);
        ownership.requestClose();
        ownership.requestClose();
        assertEquals(1, bridge.closeCount.get());
        assertTrue(ownership.isClosed());
    }

    @Test
    public void noNewTransactionAfterCloseRequested() {
        BridgeOwnership ownership = new BridgeOwnership(() -> {
        });
        ownership.requestClose();
        assertFalse(ownership.beginTransaction());
        assertTrue(ownership.isCloseRequested());
    }

    @Test
    public void loaderGenerationChangeClosesOldBridgeBeforeNewTransaction() throws Exception {
        // Old generation: a query is in flight when the loader changes.
        FakeBridge oldBridge = new FakeBridge();
        BridgeOwnership oldOwnership =
                new BridgeOwnership(oldBridge.closeCount::incrementAndGet);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch invalidated = new CountDownLatch(1);
        AtomicInteger oldResultsApplied = new AtomicInteger();

        Thread oldOwner = new Thread(() -> {
            assertTrue(oldOwnership.beginTransaction());
            queryStarted.countDown();
            try {
                assertTrue(invalidated.await(5, TimeUnit.SECONDS));
                // Old-generation results must not be applied.
                if (!oldOwnership.isCloseRequested()) {
                    oldResultsApplied.incrementAndGet();
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                oldOwnership.endTransaction();
            }
        }, "resolver-worker-old");
        oldOwner.start();

        assertTrue(queryStarted.await(5, TimeUnit.SECONDS));
        // Loader generation changed: logical close only, no physical race.
        oldOwnership.requestClose();
        invalidated.countDown();
        oldOwner.join(5_000);

        assertEquals(0, oldResultsApplied.get());
        assertEquals(1, oldBridge.closeCount.get());

        // Only now may the new transaction create the new bridge.
        FakeBridge newBridge = new FakeBridge();
        BridgeOwnership newOwnership =
                new BridgeOwnership(newBridge.closeCount::incrementAndGet);
        assertTrue(newOwnership.beginTransaction());
        newOwnership.endTransaction();
        assertEquals(0, newBridge.closeCount.get());
    }

    @Test
    public void repeatedRacesNeverDoubleClose() throws Exception {
        for (int round = 0; round < 50; round++) {
            FakeBridge bridge = new FakeBridge();
            BridgeOwnership ownership =
                    new BridgeOwnership(bridge.closeCount::incrementAndGet);
            CountDownLatch started = new CountDownLatch(1);
            Thread owner = new Thread(() -> {
                assertTrue(ownership.beginTransaction());
                started.countDown();
                Thread.yield();
                ownership.endTransaction();
            });
            owner.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ownership.requestClose();
            owner.join(5_000);
            assertEquals("round " + round, 1, bridge.closeCount.get());
        }
    }
}
