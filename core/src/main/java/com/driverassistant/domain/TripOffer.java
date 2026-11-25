package com.driverassistant.domain;

/** Dados efêmeros extraídos da árvore de acessibilidade. Campos ausentes permanecem nulos. */
public final class TripOffer {
    public enum Field { AMOUNT, CATEGORY, RATING, PICKUP_DISTANCE, PICKUP_ETA, TRIP_DISTANCE, TRIP_DURATION }
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

    /** Merges a partial refresh; explicit values in the newer snapshot take precedence. */
    public TripOffer merge(TripOffer newer) {
        if (newer == null) return this;
        return new TripOffer(first(newer.offeredAmount, offeredAmount), first(newer.category, category),
                first(newer.passengerRating, passengerRating), first(newer.pickupDistanceKm, pickupDistanceKm),
                first(newer.pickupEtaMinutes, pickupEtaMinutes), first(newer.tripDistanceKm, tripDistanceKm),
                first(newer.tripDurationMinutes, tripDurationMinutes), first(newer.pickupLabel, pickupLabel),
                first(newer.destinationLabel, destinationLabel));
    }

    /** Compatibility for associating partial UI snapshots with the active offer. */
    public boolean hasCompatibleIdentity(TripOffer other) {
        if (other == null) return false;
        int matches = 0;
        int conflicts = 0;
        int[] counts = compare(offeredAmount, other.offeredAmount, 0.02, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compareText(category, other.category, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(pickupDistanceKm, other.pickupDistanceKm, 0.05, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(pickupEtaMinutes, other.pickupEtaMinutes, 1.0, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(tripDistanceKm, other.tripDistanceKm, 0.05, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(tripDurationMinutes, other.tripDurationMinutes, 1.0, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        return conflicts == 0 || (matches >= 2 && conflicts <= 1);
    }

    public boolean hasSameDisplayData(TripOffer other) {
        return other != null && same(offeredAmount, other.offeredAmount, 0.001)
                && equalText(category, other.category) && same(passengerRating, other.passengerRating, 0.001)
                && same(pickupDistanceKm, other.pickupDistanceKm, 0.001)
                && same(pickupEtaMinutes, other.pickupEtaMinutes, 0.001)
                && same(tripDistanceKm, other.tripDistanceKm, 0.001)
                && same(tripDurationMinutes, other.tripDurationMinutes, 0.001);
    }

    public boolean hasValue(Field field) {
        return switch (field) {
            case AMOUNT -> offeredAmount != null;
            case CATEGORY -> category != null;
            case RATING -> passengerRating != null;
            case PICKUP_DISTANCE -> pickupDistanceKm != null;
            case PICKUP_ETA -> pickupEtaMinutes != null;
            case TRIP_DISTANCE -> tripDistanceKm != null;
            case TRIP_DURATION -> tripDurationMinutes != null;
        };
    }

    public TripOffer clear(Field field) {
        return new TripOffer(field == Field.AMOUNT ? null : offeredAmount,
                field == Field.CATEGORY ? null : category,
                field == Field.RATING ? null : passengerRating,
                field == Field.PICKUP_DISTANCE ? null : pickupDistanceKm,
                field == Field.PICKUP_ETA ? null : pickupEtaMinutes,
                field == Field.TRIP_DISTANCE ? null : tripDistanceKm,
                field == Field.TRIP_DURATION ? null : tripDurationMinutes,
                pickupLabel, destinationLabel);
    }

    private static <T> T first(T preferred, T fallback) { return preferred == null ? fallback : preferred; }

    private static int[] compare(Double left, Double right, double tolerance, int matches, int conflicts) {
        return compareCounts(left, right, tolerance, matches, conflicts);
    }

    private static int[] compareCounts(Double left, Double right, double tolerance, int matches, int conflicts) {
        if (left == null || right == null) return new int[]{matches, conflicts};
        if (Math.abs(left - right) <= tolerance) matches++;
        else conflicts++;
        return new int[]{matches, conflicts};
    }

    private static int[] compareText(String left, String right, int matches, int conflicts) {
        if (left == null || right == null) return new int[]{matches, conflicts};
        if (left.trim().equalsIgnoreCase(right.trim())) matches++;
        else conflicts++;
        return new int[]{matches, conflicts};
    }

    private static boolean equalText(String left, String right) {
        return left == null ? right == null : right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean same(Double left, Double right, double tolerance) {
        return left == null ? right == null : right != null && Math.abs(left - right) <= tolerance;
    }
}
