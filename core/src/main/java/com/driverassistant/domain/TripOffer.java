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

    public TripOffer(Double offeredAmount, String category, Double passengerRating,
                     Double pickupDistanceKm, Double pickupEtaMinutes,
                     Double tripDistanceKm, Double tripDurationMinutes) {
        this.offeredAmount = offeredAmount;
        this.category = category;
        this.passengerRating = passengerRating;
        this.pickupDistanceKm = pickupDistanceKm;
        this.pickupEtaMinutes = pickupEtaMinutes;
        this.tripDistanceKm = tripDistanceKm;
        this.tripDurationMinutes = tripDurationMinutes;
    }

    public Double offeredAmount() { return offeredAmount; }
    public String category() { return category; }
    public Double passengerRating() { return passengerRating; }
    public Double pickupDistanceKm() { return pickupDistanceKm; }
    public Double pickupEtaMinutes() { return pickupEtaMinutes; }
    public Double tripDistanceKm() { return tripDistanceKm; }
    public Double tripDurationMinutes() { return tripDurationMinutes; }

    /** Keeps this observation's values and fills only the fields it did not expose. */
    public TripOffer mergeMissingFrom(TripOffer fallback) {
        if (fallback == null) return this;
        return new TripOffer(first(offeredAmount, fallback.offeredAmount), first(category, fallback.category),
                first(passengerRating, fallback.passengerRating), first(pickupDistanceKm, fallback.pickupDistanceKm),
                first(pickupEtaMinutes, fallback.pickupEtaMinutes), first(tripDistanceKm, fallback.tripDistanceKm),
                first(tripDurationMinutes, fallback.tripDurationMinutes));
    }

    /** Compatibility for associating partial UI snapshots with the active offer. */
    public boolean hasCompatibleIdentity(TripOffer other) {
        if (other == null) return false;
        int matches = 0;
        int conflicts = 0;
        int[] counts;
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
        return matches > 0 && (conflicts == 0 || matches >= 2 && conflicts <= 1);
    }

    /**
     * Temporal association inside one active offer window. Missing identity fields are unknown,
     * while conflicting route/category evidence can still start a new offer. Fare alone never
     * decides whether two successive snapshots belong to the same offer.
     */
    public boolean hasCompatibleActiveIdentity(TripOffer other) {
        if (other == null) return false;
        int matches = 0;
        int conflicts = 0;
        int[] counts = compareText(category, other.category, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(pickupDistanceKm, other.pickupDistanceKm, 0.05, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(pickupEtaMinutes, other.pickupEtaMinutes, 1.0, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(tripDistanceKm, other.tripDistanceKm, 0.05, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        counts = compare(tripDurationMinutes, other.tripDurationMinutes, 1.0, matches, conflicts);
        matches = counts[0]; conflicts = counts[1];
        if (matches == 0 && conflicts == 0) return true;
        return conflicts == 0 || matches >= 2 && conflicts <= 1;
    }

    public boolean hasStrongIdentityEvidence() {
        int count = 0;
        if (category != null) count++;
        if (pickupDistanceKm != null) count++;
        if (pickupEtaMinutes != null) count++;
        if (tripDistanceKm != null) count++;
        if (tripDurationMinutes != null) count++;
        return count >= 2;
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

    public Object value(Field field) {
        return switch (field) {
            case AMOUNT -> offeredAmount;
            case CATEGORY -> category;
            case RATING -> passengerRating;
            case PICKUP_DISTANCE -> pickupDistanceKm;
            case PICKUP_ETA -> pickupEtaMinutes;
            case TRIP_DISTANCE -> tripDistanceKm;
            case TRIP_DURATION -> tripDurationMinutes;
        };
    }

    public TripOffer withFieldValue(Field field, Object value) {
        Double number = value instanceof Number numeric ? numeric.doubleValue() : null;
        String text = value instanceof String string ? string : null;
        return new TripOffer(field == Field.AMOUNT ? number : offeredAmount,
                field == Field.CATEGORY ? text : category,
                field == Field.RATING ? number : passengerRating,
                field == Field.PICKUP_DISTANCE ? number : pickupDistanceKm,
                field == Field.PICKUP_ETA ? number : pickupEtaMinutes,
                field == Field.TRIP_DISTANCE ? number : tripDistanceKm,
                field == Field.TRIP_DURATION ? number : tripDurationMinutes);
    }

    public static boolean equivalentValue(Field field, Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (field == Field.CATEGORY) return left.toString().trim().equalsIgnoreCase(right.toString().trim());
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) return false;
        double tolerance = switch (field) {
            case AMOUNT -> 0.02;
            case RATING -> 0.01;
            case PICKUP_DISTANCE, TRIP_DISTANCE -> 0.05;
            case PICKUP_ETA, TRIP_DURATION -> 1.0;
            case CATEGORY -> 0;
        };
        return Math.abs(leftNumber.doubleValue() - rightNumber.doubleValue()) <= tolerance;
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
