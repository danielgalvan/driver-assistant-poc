package com.driverassistant.lifecycle;

import com.driverassistant.domain.TripOffer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Android-free lifecycle for associating successive partial accessibility and OCR observations. */
public final class OfferLifecycleMachine {
    private static final long OCR_CONFIRMATION_WINDOW_MILLIS = 3_000;

    private final long disappearanceToleranceMillis;
    private final Map<TripOffer.Field, PendingValue> pendingFieldValues = new EnumMap<>(TripOffer.Field.class);
    private final EnumSet<TripOffer.Field> reportedMissingFields = EnumSet.noneOf(TripOffer.Field.class);
    private OfferLifecycleState state = OfferLifecycleState.IDLE;
    private TripOffer activeOffer;
    private TripOffer pendingReplacement;
    private int pendingReplacementCount;
    private long pendingReplacementAt = -1;
    private long sessionId;
    private long missingSince = -1;

    public enum ObservationSource { ACCESSIBILITY, OCR, MIXED }

    public OfferLifecycleMachine(long disappearanceToleranceMillis) {
        if (disappearanceToleranceMillis < 0) throw new IllegalArgumentException("tolerance must be non-negative");
        this.disappearanceToleranceMillis = disappearanceToleranceMillis;
    }

    public OfferLifecycleEvent observe(Optional<TripOffer> candidate, long nowMillis) {
        return observe(candidate, nowMillis, ObservationSource.ACCESSIBILITY);
    }

    public OfferLifecycleEvent observe(Optional<TripOffer> candidate, long nowMillis, ObservationSource source) {
        Optional<TripOffer> safeCandidate = candidate == null ? Optional.empty() : candidate;
        ObservationSource safeSource = source == null ? ObservationSource.ACCESSIBILITY : source;
        if (safeCandidate.isEmpty()) {
            clearPendingObservations();
            return observeMissing(nowMillis);
        }

        TripOffer incoming = safeCandidate.get();
        missingSince = -1;
        if (activeOffer == null) return startNew(incoming, safeSource, "new_offer");

        if (!activeOffer.hasCompatibleActiveIdentity(incoming)) {
            if (safeSource != ObservationSource.ACCESSIBILITY) {
                if (!activeOffer.hasStrongIdentityEvidence() || !incoming.hasStrongIdentityEvidence()) {
                    clearPendingObservations();
                    return event(false, "replacement_identity_insufficient", List.of(
                            new OfferLifecycleEvent.FieldDecision(TripOffer.Field.AMOUNT,
                                    activeOffer.offeredAmount(), incoming.offeredAmount(), activeOffer.offeredAmount(),
                                    "reject_replacement_insufficient_stable_fields", 0, safeSource.name())));
                }
                return observePossibleReplacement(incoming, nowMillis, safeSource);
            }
            return startNew(incoming, safeSource, "new_offer_identity_changed");
        }

        clearPendingReplacement();
        List<OfferLifecycleEvent.FieldDecision> decisions = new ArrayList<>();
        TripOffer merged = activeOffer;
        for (TripOffer.Field field : TripOffer.Field.values()) {
            Object previous = merged.value(field);
            Object observed = incoming.value(field);
            if (observed == null) {
                pendingFieldValues.remove(field);
                if (previous != null && reportedMissingFields.add(field)) {
                    decisions.add(new OfferLifecycleEvent.FieldDecision(field, previous, null, previous,
                            "retain_valid_value_after_partial_capture", 0, safeSource.name()));
                }
                continue;
            }
            reportedMissingFields.remove(field);
            if (previous == null) {
                merged = merged.withFieldValue(field, observed);
                pendingFieldValues.remove(field);
                decisions.add(new OfferLifecycleEvent.FieldDecision(field, null, observed, observed,
                        "accepted_enrichment", 1, safeSource.name()));
                continue;
            }
            if (TripOffer.equivalentValue(field, previous, observed)) {
                pendingFieldValues.remove(field);
                if (!previous.equals(observed)) {
                    decisions.add(new OfferLifecycleEvent.FieldDecision(field, previous, observed, previous,
                            "kept_existing_within_tolerance", 1, safeSource.name()));
                }
                continue;
            }

            if (safeSource == ObservationSource.ACCESSIBILITY) {
                merged = merged.withFieldValue(field, observed);
                pendingFieldValues.remove(field);
                decisions.add(new OfferLifecycleEvent.FieldDecision(field, previous, observed, observed,
                        "accepted_accessibility_change", 1, safeSource.name()));
                continue;
            }

            PendingValue pending = pendingFieldValues.get(field);
            if (pending != null && nowMillis - pending.lastObservedAt <= OCR_CONFIRMATION_WINDOW_MILLIS
                    && TripOffer.equivalentValue(field, pending.value, observed)) {
                pending = new PendingValue(observed, pending.confirmations + 1, nowMillis);
            } else {
                pending = new PendingValue(observed, 1, nowMillis);
            }
            if (pending.confirmations >= 2) {
                merged = merged.withFieldValue(field, observed);
                pendingFieldValues.remove(field);
                decisions.add(new OfferLifecycleEvent.FieldDecision(field, previous, observed, observed,
                        "accepted_repeated_ocr_change", pending.confirmations, safeSource.name()));
            } else {
                pendingFieldValues.put(field, pending);
                decisions.add(new OfferLifecycleEvent.FieldDecision(field, previous, observed, previous,
                        "held_for_ocr_confirmation", pending.confirmations, safeSource.name()));
            }
        }

        if (activeOffer.hasSameDisplayData(merged)) {
            activeOffer = merged;
            String reason = decisions.stream().anyMatch(decision -> decision.decision().equals("held_for_ocr_confirmation"))
                    ? "field_change_pending_confirmation" : "duplicate_snapshot";
            return event(false, reason, decisions);
        }
        activeOffer = merged;
        state = OfferLifecycleState.OFFER_UPDATED;
        return event(true, "relevant_fields_updated", decisions);
    }

    private OfferLifecycleEvent observePossibleReplacement(TripOffer incoming, long nowMillis,
                                                            ObservationSource source) {
        if (pendingReplacement != null && nowMillis - pendingReplacementAt <= OCR_CONFIRMATION_WINDOW_MILLIS
                && pendingReplacement.hasCompatibleIdentity(incoming)) {
            pendingReplacementCount++;
            pendingReplacement = incoming;
            pendingReplacementAt = nowMillis;
        } else {
            pendingReplacement = incoming;
            pendingReplacementCount = 1;
            pendingReplacementAt = nowMillis;
        }
        List<OfferLifecycleEvent.FieldDecision> decisions = List.of(
                new OfferLifecycleEvent.FieldDecision(TripOffer.Field.AMOUNT,
                        activeOffer.offeredAmount(), incoming.offeredAmount(), activeOffer.offeredAmount(),
                        pendingReplacementCount >= 2 ? "confirmed_new_offer_identity" : "held_replacement_for_confirmation",
                        pendingReplacementCount, source.name()));
        if (pendingReplacementCount < 2) return event(false, "replacement_pending_confirmation", decisions);
        return startNew(incoming, source, "confirmed_new_offer_replacement");
    }

    private OfferLifecycleEvent startNew(TripOffer incoming, ObservationSource source, String reason) {
        TripOffer previous = activeOffer;
        activeOffer = incoming;
        sessionId++;
        state = OfferLifecycleState.OFFER_DETECTED;
        missingSince = -1;
        clearPendingObservations();
        List<OfferLifecycleEvent.FieldDecision> decisions = new ArrayList<>();
        for (TripOffer.Field field : TripOffer.Field.values()) {
            Object value = incoming.value(field);
            decisions.add(new OfferLifecycleEvent.FieldDecision(field,
                    previous == null ? null : previous.value(field), value, value,
                    value == null ? "unavailable_at_initial_detection" : "accepted_initial_value",
                    1, source.name()));
        }
        return event(true, reason, decisions);
    }

    public OfferLifecycleEvent observeMissing(long nowMillis) {
        if (activeOffer == null) {
            state = OfferLifecycleState.IDLE;
            return event(false, "no_active_offer", List.of());
        }
        if (missingSince < 0) missingSince = nowMillis;
        if (nowMillis - missingSince < disappearanceToleranceMillis) {
            return event(false, "transient_missing", List.of());
        }
        activeOffer = null;
        reportedMissingFields.clear();
        missingSince = -1;
        clearPendingObservations();
        state = OfferLifecycleState.OFFER_DISAPPEARED;
        return event(true, "offer_disappeared", List.of());
    }

    /** Called when monitoring is explicitly disabled; disappearance does not imply acceptance. */
    public OfferLifecycleEvent disable() {
        if (activeOffer == null) {
            missingSince = -1;
            clearPendingObservations();
            state = OfferLifecycleState.IDLE;
            return event(false, "monitoring_disabled", List.of());
        }
        activeOffer = null;
        reportedMissingFields.clear();
        missingSince = -1;
        clearPendingObservations();
        state = OfferLifecycleState.OFFER_DISAPPEARED;
        return event(true, "monitoring_disabled", List.of());
    }

    public TripOffer activeOffer() { return activeOffer; }
    public OfferLifecycleState state() { return state; }
    public long sessionId() { return sessionId; }

    private void clearPendingObservations() {
        pendingFieldValues.clear();
        clearPendingReplacement();
    }

    private void clearPendingReplacement() {
        pendingReplacement = null;
        pendingReplacementCount = 0;
        pendingReplacementAt = -1;
    }

    private OfferLifecycleEvent event(boolean transitioned, String reason,
                                      List<OfferLifecycleEvent.FieldDecision> decisions) {
        return new OfferLifecycleEvent(state, activeOffer, sessionId, transitioned, reason, decisions);
    }

    private record PendingValue(Object value, int confirmations, long lastObservedAt) { }
}
