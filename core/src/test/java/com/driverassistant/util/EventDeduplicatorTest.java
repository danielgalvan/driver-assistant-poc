package com.driverassistant.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EventDeduplicatorTest {
    @Test public void suppressesRepeatedFingerprintDuringDebounceWindow() {
        EventDeduplicator deduplicator = new EventDeduplicator(1_000);
        assertTrue(deduplicator.shouldProcess("offer-a", 5_000));
        assertFalse(deduplicator.shouldProcess("offer-a", 5_999));
        assertTrue(deduplicator.shouldProcess("offer-a", 6_000));
    }

    @Test public void changedFingerprintIsProcessedImmediately() {
        EventDeduplicator deduplicator = new EventDeduplicator(1_000);
        assertTrue(deduplicator.shouldProcess("offer-a", 5_000));
        assertTrue(deduplicator.shouldProcess("offer-b", 5_001));
    }

    @Test public void emptyFingerprintsAreRejected() {
        EventDeduplicator deduplicator = new EventDeduplicator(1_000);
        assertFalse(deduplicator.shouldProcess(null, 1));
        assertFalse(deduplicator.shouldProcess("   ", 2));
    }
}
