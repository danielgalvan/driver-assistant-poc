package com.driverassistant.domain;

/** Dados efêmeros extraídos da árvore de acessibilidade. Campos ausentes permanecem nulos. */
public final class TripOffer {
    private final Double offeredAmount;
    private final String category;
    private final Double passengerRating;
    private final Double pickupDistanceKm;
    private final Double pickupEtaMinutes;
    private final Double tripDistanceKm;
    private final Double tripDurationMinutes;
    private final String pickupLabel;
    private final String destinationLabel;

    public TripOffer(Double offeredAmount, String category, Double passengerRating,
                     Double pickupDistanceKm, Double pickupEtaMinutes,
                     Double tripDistanceKm, Double tripDurationMinutes,
                     String pickupLabel, String destinationLabel) {
        this.offeredAmount = offeredAmount;
        this.category = category;
        this.passengerRating = passengerRating;
        this.pickupDistanceKm = pickupDistanceKm;
        this.pickupEtaMinutes = pickupEtaMinutes;
        this.tripDistanceKm = tripDistanceKm;
        this.tripDurationMinutes = tripDurationMinutes;
        this.pickupLabel = pickupLabel;
        this.destinationLabel = destinationLabel;
    }

    public Double offeredAmount() { return offeredAmount; }
    public String category() { return category; }
    public Double passengerRating() { return passengerRating; }
    public Double pickupDistanceKm() { return pickupDistanceKm; }
    public Double pickupEtaMinutes() { return pickupEtaMinutes; }
    public Double tripDistanceKm() { return tripDistanceKm; }
    public Double tripDurationMinutes() { return tripDurationMinutes; }
    public String pickupLabel() { return pickupLabel; }
    public String destinationLabel() { return destinationLabel; }
}
