package com.driverassistant.calculator;

import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.TripOffer;

/** Regras puras de cálculo, sem dependência do framework Android. */
public final class ProfitabilityCalculator {
    public RideMetrics calculate(TripOffer offer) {
        if (offer == null) return new RideMetrics(null, null, null, null);

        Double totalDistance = sumIfValid(offer.pickupDistanceKm(), offer.tripDistanceKm());
        Double totalMinutes = sumIfValid(offer.pickupEtaMinutes(), offer.tripDurationMinutes());
        Double amount = usable(offer.offeredAmount()) ? offer.offeredAmount() : null;

        Double perKm = amount != null && totalDistance != null && totalDistance > 0
                ? amount / totalDistance : null;
        Double perHour = amount != null && totalMinutes != null && totalMinutes > 0
                ? amount / (totalMinutes / 60.0) : null;

        return new RideMetrics(totalDistance, totalMinutes, validResult(perKm), validResult(perHour));
    }

    private static Double sumIfValid(Double left, Double right) {
        if (!usable(left) || !usable(right)) return null;
        return validResult(left + right);
    }

    private static boolean usable(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private static Double validResult(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 ? value : null;
    }
}
