package com.driverassistant.lifecycle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OverlaySessionPolicyTest {
    @Test public void duplicateAndUpdatedOfferDoNotRestartDisplayDeadline() {
        OverlaySessionPolicy policy = new OverlaySessionPolicy();
        assertEquals(OverlaySessionPolicy.Decision.NEW_SESSION, policy.show(7, "a", 100, 12_000));
        assertEquals(OverlaySessionPolicy.Decision.DUPLICATE, policy.show(7, "a", 2_000, 12_000));
        assertEquals(10_100, policy.remainingMillis(2_000));
        assertEquals(OverlaySessionPolicy.Decision.UPDATED, policy.show(7, "b", 3_000, 12_000));
        assertEquals(9_100, policy.remainingMillis(3_000));
    }

    @Test public void expiresOnlyExpectedSessionAndDoesNotReappearAfterExpiry() {
        OverlaySessionPolicy policy = new OverlaySessionPolicy();
        policy.show(12, "offer", 1_000, 1_000);
        assertFalse(policy.expire(11, 2_100));
        assertFalse(policy.expire(12, 1_999));
        assertTrue(policy.expire(12, 2_000));
        assertEquals(OverlaySessionPolicy.Decision.EXPIRED, policy.show(12, "offer", 2_100, 1_000));
        assertEquals(OverlaySessionPolicy.Decision.NEW_SESSION, policy.show(13, "similar", 2_100, 1_000));
    }

    @Test public void explicitHideAllowsARealSessionToBeRestored() {
        OverlaySessionPolicy policy = new OverlaySessionPolicy();
        policy.show(99, "simulated", 100, 5_000);
        policy.clear();
        assertEquals(OverlaySessionPolicy.Decision.NEW_SESSION, policy.show(4, "real", 300, 5_000));
    }

    @Test public void activeOfferObservationsExtendTheStaleCardWatchdog() {
        OverlaySessionPolicy policy = new OverlaySessionPolicy();
        policy.show(15, "offer", 1_000, 10_000);

        assertTrue(policy.refresh(15, 9_000, 10_000));
        assertFalse(policy.expire(15, 10_001));
        assertEquals(8_999, policy.remainingMillis(10_001));
    }

    @Test public void expiredSessionCannotBeRefreshed() {
        OverlaySessionPolicy policy = new OverlaySessionPolicy();
        policy.show(16, "offer", 1_000, 1_000);
        assertTrue(policy.expire(16, 2_000));

        assertFalse(policy.refresh(16, 2_100, 1_000));
    }
}
