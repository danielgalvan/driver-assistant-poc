package com.driverassistant.lifecycle;

import com.driverassistant.domain.TripOffer;

import java.util.List;

public final class OfferLifecycleEvent {
    private final OfferLifecycleState state;
    private final TripOffer offer;
    private final long sessionId;
    private final boolean transitioned;
    private final String reason;
    private final List<FieldDecision> fieldDecisions;

    OfferLifecycleEvent(OfferLifecycleState state, TripOffer offer, long sessionId,
                        boolean transitioned, String reason) {
        this(state, offer, sessionId, transitioned, reason, List.of());
    }

    OfferLifecycleEvent(OfferLifecycleState state, TripOffer offer, long sessionId,
                        boolean transitioned, String reason, List<FieldDecision> fieldDecisions) {
        this.state = state;
        this.offer = offer;
        this.sessionId = sessionId;
        this.transitioned = transitioned;
        this.reason = reason;
        this.fieldDecisions = List.copyOf(fieldDecisions == null ? List.of() : fieldDecisions);
    }

    public OfferLifecycleState state() { return state; }
    public TripOffer offer() { return offer; }
    public long sessionId() { return sessionId; }
    public boolean transitioned() { return transitioned; }
    public String reason() { return reason; }
    public List<FieldDecision> fieldDecisions() { return fieldDecisions; }

    public record FieldDecision(TripOffer.Field field, Object previousValue, Object observedValue,
                                Object acceptedValue, String decision, int confirmations, String source) { }
}
