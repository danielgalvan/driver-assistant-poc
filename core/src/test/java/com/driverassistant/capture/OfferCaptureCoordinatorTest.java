package com.driverassistant.capture;

import com.driverassistant.domain.TripOffer;
import org.junit.Test;

import static org.junit.Assert.*;

public final class OfferCaptureCoordinatorTest {
    private final OfferCaptureCoordinator coordinator = new OfferCaptureCoordinator();

    @Test public void accessibilityIsTheSafeDefaultAndDoesNotRequestImages() {
        assertEquals(OfferCaptureStrategy.ACCESSIBILITY_ONLY,
                OfferCaptureStrategy.fromStoredValue(null));
        assertFalse(coordinator.shouldRequestVisual(OfferCaptureStrategy.ACCESSIBILITY_ONLY,
                false, true, true));
        assertTrue(coordinator.shouldUseAccessibility(OfferCaptureStrategy.ACCESSIBILITY_ONLY));
    }

    @Test public void hybridRequestsVisualOnlyWhenStructuralParsingDidNotAccept() {
        assertTrue(coordinator.shouldRequestVisual(OfferCaptureStrategy.HYBRID, false, true, false));
        assertFalse(coordinator.shouldRequestVisual(OfferCaptureStrategy.HYBRID, true, true, true));
        assertFalse(coordinator.shouldRequestVisual(OfferCaptureStrategy.HYBRID, false, false, false));
    }

    @Test public void ocrOnlyAndComparativeRequireRelevantUberWindowActivity() {
        assertTrue(coordinator.shouldRequestVisual(OfferCaptureStrategy.OCR_ONLY, false, true, false));
        assertFalse(coordinator.shouldRequestVisual(OfferCaptureStrategy.OCR_ONLY, false, false, true));
        assertTrue(coordinator.shouldRequestVisual(OfferCaptureStrategy.COMPARATIVE, true, true, false));
        assertFalse(coordinator.shouldRequestVisual(OfferCaptureStrategy.COMPARATIVE, true, false, true));
        assertFalse(coordinator.shouldUseAccessibility(OfferCaptureStrategy.OCR_ONLY));
    }

    @Test public void compatibleSourcesMergeMissingFieldsAndRetainFieldOrigins() {
        TripOffer accessibility = offer(29.09, "UberX", 5.0, 0.8, null, 10.8, null);
        TripOffer ocr = offer(29.09, "UberX", 5.0, 0.8, 7.0, 10.8, 33.0);
        OfferCaptureCoordinator.Reconciliation result = coordinator.reconcile(accessibility, ocr);

        assertEquals(OfferCaptureCoordinator.Status.MERGED, result.status());
        assertEquals(Double.valueOf(7.0), result.offer().pickupEtaMinutes());
        assertEquals(Double.valueOf(33.0), result.offer().tripDurationMinutes());
        assertEquals("accessibility", result.fieldOrigins().get(TripOffer.Field.AMOUNT));
        assertEquals("ocr", result.fieldOrigins().get(TripOffer.Field.PICKUP_ETA));
    }

    @Test public void incompatibleSourcesAreNeverCombined() {
        TripOffer accessibility = offer(29.09, "UberX", 5.0, 0.8, 7.0, 10.8, 33.0);
        TripOffer ocr = offer(36.03, "UberX", 5.0, 0.6, 5.0, 20.3, 45.0);
        OfferCaptureCoordinator.Reconciliation result = coordinator.reconcile(accessibility, ocr);

        assertEquals(OfferCaptureCoordinator.Status.DIVERGENT, result.status());
        assertNull(result.offer());
    }

    @Test public void unknownStoredStrategyFallsBackToAccessibilityOnly() {
        assertEquals(OfferCaptureStrategy.ACCESSIBILITY_ONLY,
                OfferCaptureStrategy.fromStoredValue("FUTURE_UNKNOWN"));
    }

    private static TripOffer offer(double amount, String category, double rating,
                                   double pickup, Double pickupEta, double trip, Double duration) {
        return new TripOffer(amount, category, rating, pickup, pickupEta, trip, duration, null, null);
    }
}
