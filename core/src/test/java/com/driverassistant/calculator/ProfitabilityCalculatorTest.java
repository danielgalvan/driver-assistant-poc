package com.driverassistant.calculator;

import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.TripOffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ProfitabilityCalculatorTest {
    private final ProfitabilityCalculator calculator = new ProfitabilityCalculator();

    @Test public void calculatesTotalsAndRates() {
        RideMetrics metrics = calculator.calculate(offer(30.0, 2.0, 8.0, 10.0, 20.0));
        assertEquals(10.0, metrics.totalDistanceKm(), 0.0001);
        assertEquals(30.0, metrics.totalDurationMinutes(), 0.0001);
        assertEquals(3.0, metrics.earningsPerKm(), 0.0001);
        assertEquals(60.0, metrics.earningsPerHour(), 0.0001);
    }

    @Test public void missingInputsDoNotBecomeZero() {
        RideMetrics metrics = calculator.calculate(offer(30.0, null, 8.0, 10.0, null));
        assertNull(metrics.totalDistanceKm());
        assertNull(metrics.totalDurationMinutes());
        assertNull(metrics.earningsPerKm());
        assertNull(metrics.earningsPerHour());
    }

    @Test public void zeroTotalsAvoidDivisionByZero() {
        RideMetrics metrics = calculator.calculate(offer(30.0, 0.0, 0.0, 0.0, 0.0));
        assertEquals(0.0, metrics.totalDistanceKm(), 0.0001);
        assertEquals(0.0, metrics.totalDurationMinutes(), 0.0001);
        assertNull(metrics.earningsPerKm());
        assertNull(metrics.earningsPerHour());
    }

    @Test public void negativeAndNonFiniteValuesAreUnavailable() {
        RideMetrics metrics = calculator.calculate(offer(Double.NaN, -1.0, 4.0, 10.0, -3.0));
        assertNull(metrics.totalDistanceKm());
        assertNull(metrics.totalDurationMinutes());
        assertNull(metrics.earningsPerKm());
        assertNull(metrics.earningsPerHour());
    }

    private static TripOffer offer(Double amount, Double pickupKm, Double tripKm,
                                   Double pickupMinutes, Double tripMinutes) {
        return new TripOffer(amount, null, null, pickupKm, pickupMinutes,
                tripKm, tripMinutes, null, null);
    }
}
