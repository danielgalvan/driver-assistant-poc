package com.driverassistant.domain;

/** Resultado dos cálculos. Um campo nulo significa que os dados necessários estavam ausentes. */
public final class RideMetrics {
    private final Double totalDistanceKm;
    private final Double totalDurationMinutes;
    private final Double earningsPerKm;
    private final Double earningsPerHour;

    public RideMetrics(Double totalDistanceKm, Double totalDurationMinutes,
                       Double earningsPerKm, Double earningsPerHour) {
        this.totalDistanceKm = totalDistanceKm;
        this.totalDurationMinutes = totalDurationMinutes;
        this.earningsPerKm = earningsPerKm;
        this.earningsPerHour = earningsPerHour;
    }

    public Double totalDistanceKm() { return totalDistanceKm; }
    public Double totalDurationMinutes() { return totalDurationMinutes; }
    public Double earningsPerKm() { return earningsPerKm; }
    public Double earningsPerHour() { return earningsPerHour; }
}
