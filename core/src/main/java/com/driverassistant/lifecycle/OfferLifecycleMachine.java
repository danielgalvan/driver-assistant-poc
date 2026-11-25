package com.driverassistant.lifecycle;

import com.driverassistant.domain.TripOffer;

import java.util.Optional;
import java.util.EnumMap;
import java.util.Map;

/** Android-free lifecycle for associating successive partial accessibility snapshots. */
public final class OfferLifecycleMachine {
    private final long disappearanceToleranceMillis;
    private final Map<TripOffer.Field, Long> missingFieldsSince = new EnumMap<>(TripOffer.Field.class);
    private OfferLifecycleState state = OfferLifecycleState.IDLE;
    private TripOffer activeOffer;
    private long sessionId;
    private long missingSince = -1;

    public OfferLifecycleMachine(long disappearanceToleranceMillis) {
        if (disappearanceToleranceMillis < 0) throw new IllegalArgumentException("tolerance must be non-negative");
        this.disappearanceToleranceMillis = disappearanceToleranceMillis;
    }

    public OfferLifecycleEvent observe(Optional<TripOffer> candidate, long nowMillis) {
        Optional<TripOffer> safeCandidate = candidate == null ? Optional.empty() : candidate;
        if (safeCandidate.isEmpty()) return observeMissing(nowMillis);

        TripOffer incoming = safeCandidate.get();
        missingSince = -1;
        if (activeOffer == null || !activeOffer.hasCompatibleIdentity(incoming)) {
            missingFieldsSince.clear();
            activeOffer = incoming;
            sessionId++;
            state = OfferLifecycleState.OFFER_DETECTED;
            return event(true, "new_offer");
        }

        TripOffer previous = activeOffer;
        TripOffer merged = previous.merge(incoming);
        for (TripOffer.Field field : TripOffer.Field.values()) {
            if (incoming.hasValue(field)) {
                missingFieldsSince.remove(field);
            } else if (previous.hasValue(field)) {
                long absentSince = missingFieldsSince.computeIfAbsent(field, ignored -> nowMillis);
                if (nowMillis - absentSince >= disappearanceToleranceMillis) {
                    merged = merged.clear(field);
                    missingFieldsSince.remove(field);
                }
            }
        }
        if (activeOffer.hasSameDisplayData(merged)) {
            activeOffer = merged;
            return event(false, "duplicate_snapshot");
        }
        activeOffer = merged;
        state = OfferLifecycleState.OFFER_UPDATED;
        return event(true, "relevant_fields_updated");
    }

    public OfferLifecycleEvent observeMissing(long nowMillis) {
        if (activeOffer == null) {
            state = OfferLifecycleState.IDLE;
            return event(false, "no_active_offer");
        }
        if (missingSince < 0) missingSince = nowMillis;
        if (nowMillis - missingSince < disappearanceToleranceMillis) {
            return event(false, "transient_missing");
        }
        activeOffer = null;
        missingFieldsSince.clear();
        missingSince = -1;
        state = OfferLifecycleState.OFFER_DISAPPEARED;
        return event(true, "offer_disappeared");
    }

    /** Called when monitoring is explicitly disabled; disappearance does not imply acceptance. */
    public OfferLifecycleEvent disable() {
        if (activeOffer == null) {
            missingSince = -1;
            state = OfferLifecycleState.IDLE;
            return event(false, "monitoring_disabled");
        }
        activeOffer = null;
        missingFieldsSince.clear();
        missingSince = -1;
        state = OfferLifecycleState.OFFER_DISAPPEARED;
        return event(true, "monitoring_disabled");
    }

    public TripOffer activeOffer() { return activeOffer; }
    public OfferLifecycleState state() { return state; }
    public long sessionId() { return sessionId; }
    public long disappearanceToleranceMillis() { return disappearanceToleranceMillis; }

    private OfferLifecycleEvent event(boolean transitioned, String reason) {
        return new OfferLifecycleEvent(state, activeOffer, sessionId, transitioned, reason);
    }
}
