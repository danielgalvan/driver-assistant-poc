package com.driverassistant.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CaptureResultPolicyTest {
    @Test public void acceptsRecentResultFromCurrentOfferGeneration() {
        assertTrue(CaptureResultPolicy.mayApply(4, 4, 10_000, 12_000, true));
        assertTrue(CaptureResultPolicy.mayApply(4, 4, 10_000,
                10_000 + CaptureResultPolicy.MAX_RESULT_AGE_MILLIS, true));
    }

    @Test public void rejectsExpiredOrClockInconsistentResult() {
        assertFalse(CaptureResultPolicy.mayApply(4, 4, 10_000,
                10_001 + CaptureResultPolicy.MAX_RESULT_AGE_MILLIS, true));
        assertFalse(CaptureResultPolicy.mayApply(4, 4, 10_001, 10_000, true));
    }

    @Test public void disappearanceInvalidatesPendingOcrThroughGenerationChange() {
        assertFalse(CaptureResultPolicy.mayApply(4, 5, 10_000, 10_500, true));
    }

    @Test public void disabledMonitoringRejectsEvenCurrentResult() {
        assertFalse(CaptureResultPolicy.mayApply(4, 4, 10_000, 10_500, false));
    }
}
