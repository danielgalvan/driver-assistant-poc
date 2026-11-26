package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DiagnosticCapturePolicyTest {
    @Test
    public void isOffUntilManuallyStartedAndReservesRecordsInOrder() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertFalse(policy.active());
        assertTrue(policy.start(1_000, 0, 1_000, 10_000));
        assertEquals(DiagnosticCapturePolicy.AppendResult.APPENDED, policy.reserve(1_001, 100));
        assertEquals(DiagnosticCapturePolicy.AppendResult.APPENDED, policy.reserve(1_002, 200));
        assertEquals(2, policy.eventCount());
        assertEquals(300, policy.reservedBytes());
    }

    @Test
    public void stopsAtStorageLimitWithoutOverwritingEarlierRecords() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertTrue(policy.start(10, 100, 250, 10_000));
        assertEquals(DiagnosticCapturePolicy.AppendResult.APPENDED, policy.reserve(11, 100));
        assertEquals(DiagnosticCapturePolicy.AppendResult.STORAGE_LIMIT, policy.reserve(12, 60));
        assertFalse(policy.active());
        assertEquals(200, policy.reservedBytes());
        assertEquals(1, policy.eventCount());
    }

    @Test
    public void resumesAfterProcessRestartAndStopsAtSessionDuration() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertTrue(policy.resume(5_000, 1_000, 4, 500, 10_000, 10_000));
        assertEquals(4, policy.eventCount());
        assertEquals(DiagnosticCapturePolicy.AppendResult.DURATION_LIMIT, policy.reserve(11_000, 10));
        assertFalse(policy.active());
        assertEquals("duration_limit", policy.stopReason());
    }

    @Test
    public void preservesExistingBytesWhenResumingAndRefusesAnExpiredSession() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertFalse(policy.resume(20_000, 1_000, 2, 1_000, 50_000, 10_000));
        assertFalse(policy.start(20_000, 50_000, 50_000, 10_000));
    }

    @Test
    public void snapshotPolicyDeduplicatesTreesButPreservesDistinctOfferCandidates() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertFalse(policy.shouldPersistSnapshot("map", false, 1_000));
        assertTrue(policy.start(1_000, 0, 1_000_000, 100_000));
        assertTrue(policy.shouldPersistSnapshot("map", false, 1_001));
        assertFalse(policy.shouldPersistSnapshot("map", false, 1_100));
        assertFalse(policy.shouldPersistSnapshot("map-changed", false, 1_500));
        assertTrue(policy.shouldPersistSnapshot("map-changed", false, 2_001));
        assertTrue(policy.shouldPersistSnapshot("offer-a", true, 2_100));
        assertFalse(policy.shouldPersistSnapshot("offer-a", true, 3_000));
        assertTrue(policy.shouldPersistSnapshot("offer-b", true, 3_001));
    }

    @Test
    public void unchangedOfferSnapshotRefreshIsBoundedToTenSeconds() {
        DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
        assertTrue(policy.start(1_000, 0, 1_000_000, 100_000));
        assertTrue(policy.shouldPersistSnapshot("offer-a", true, 1_001));
        assertFalse(policy.shouldPersistSnapshot("offer-a", true, 10_999));
        assertTrue(policy.shouldPersistSnapshot("offer-a", true, 11_001));
    }
}
