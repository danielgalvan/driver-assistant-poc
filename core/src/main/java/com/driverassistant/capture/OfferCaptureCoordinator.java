package com.driverassistant.capture;

import com.driverassistant.domain.TripOffer;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure strategy and cross-source reconciliation rules shared by the Android service and tests. */
public final class OfferCaptureCoordinator {
    public boolean shouldRequestVisual(OfferCaptureStrategy strategy, boolean accessibilityAccepted,
                                       boolean relevantWindowChange, boolean structuralEvidence) {
        if (strategy == null || strategy == OfferCaptureStrategy.ACCESSIBILITY_ONLY) return false;
        return switch (strategy) {
            case ACCESSIBILITY_ONLY -> false;
            case OCR_ONLY, COMPARATIVE -> relevantWindowChange;
            case HYBRID -> !accessibilityAccepted && (relevantWindowChange || structuralEvidence);
        };
    }

    public boolean shouldUseAccessibility(OfferCaptureStrategy strategy) {
        return strategy == null || strategy.usesAccessibilityParsing();
    }

    /** Does not merge incompatible offers; source identity must agree before fields are combined. */
    public Reconciliation reconcile(TripOffer accessibility, TripOffer ocr) {
        if (accessibility == null && ocr == null) return new Reconciliation(null, Status.NO_OFFER, Map.of(), List.of());
        if (accessibility == null) return new Reconciliation(ocr, Status.OCR_ONLY, origins(null, ocr), List.of());
        if (ocr == null) return new Reconciliation(accessibility, Status.ACCESSIBILITY_ONLY,
                origins(accessibility, null), List.of());
        List<FieldConflict> conflicts = conflicts(accessibility, ocr);
        if (!accessibility.hasCompatibleIdentity(ocr)) {
            return new Reconciliation(null, Status.DIVERGENT, Map.of(), conflicts);
        }
        TripOffer merged = accessibility.mergeMissingFrom(ocr);
        return new Reconciliation(merged, Status.MERGED, origins(accessibility, ocr), conflicts);
    }

    private static List<FieldConflict> conflicts(TripOffer accessibility, TripOffer ocr) {
        List<FieldConflict> result = new ArrayList<>();
        for (TripOffer.Field field : TripOffer.Field.values()) {
            Object accessValue = accessibility.value(field);
            Object ocrValue = ocr.value(field);
            if (accessValue != null && ocrValue != null
                    && !TripOffer.equivalentValue(field, accessValue, ocrValue)) {
                result.add(new FieldConflict(field, accessValue, ocrValue, "accessibility"));
            }
        }
        return List.copyOf(result);
    }

    private static Map<TripOffer.Field, String> origins(TripOffer accessibility, TripOffer ocr) {
        EnumMap<TripOffer.Field, String> origins = new EnumMap<>(TripOffer.Field.class);
        for (TripOffer.Field field : TripOffer.Field.values()) {
            if (accessibility != null && accessibility.hasValue(field)) origins.put(field, "accessibility");
            else if (ocr != null && ocr.hasValue(field)) origins.put(field, "ocr");
        }
        return Map.copyOf(origins);
    }

    public enum Status { NO_OFFER, ACCESSIBILITY_ONLY, OCR_ONLY, MERGED, DIVERGENT }

    public record Reconciliation(TripOffer offer, Status status, Map<TripOffer.Field, String> fieldOrigins,
                                 List<FieldConflict> fieldConflicts) { }

    public record FieldConflict(TripOffer.Field field, Object accessibilityValue, Object ocrValue,
                                String selectedSource) { }
}
