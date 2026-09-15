package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Release-hardening §8: the attach handoff is a one-shot transaction —
 * exactly one claim, explicit completion, and a mid-handoff failure is
 * terminal (never silently retried into duplicate hooks).
 */
public final class AttachHandoffTest {
    @Test
    public void claimSucceedsExactlyOnce() {
        AttachHandoff handoff = new AttachHandoff();
        assertTrue(handoff.claim());
        assertEquals(AttachHandoff.State.IN_PROGRESS, handoff.state());
        // Late wrapper/packer attach calls are no-ops.
        assertFalse(handoff.claim());
        assertFalse(handoff.claim());
    }

    @Test
    public void successfulHandoffCompletes() {
        AttachHandoff handoff = new AttachHandoff();
        assertTrue(handoff.claim());
        handoff.complete();
        assertEquals(AttachHandoff.State.COMPLETE, handoff.state());
        // A late attach after completion must not re-claim or reset anything.
        assertFalse(handoff.claim());
        assertEquals(AttachHandoff.State.COMPLETE, handoff.state());
    }

    @Test
    public void midHandoffFailureIsTerminalAndNeverRetried() {
        AttachHandoff handoff = new AttachHandoff();
        assertTrue(handoff.claim());
        handoff.fail();
        assertEquals(AttachHandoff.State.FAILED, handoff.state());
        // No retry: a second claim could duplicate partially installed hooks.
        assertFalse(handoff.claim());
        assertEquals(AttachHandoff.State.FAILED, handoff.state());
    }
}
