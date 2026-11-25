package com.driverassistant.lifecycle;

import com.driverassistant.domain.TripOffer;

public final class OfferLifecycleEvent {
    private final OfferLifecycleState state;
    private final TripOffer offer;
    private final long sessionId;
    private final boolean transitioned;
    private final String reason;

    OfferLifecycleEvent(OfferLifecycleState state, TripOffer offer, long sessionId,
                        boolean transitioned, String reason) {
        this.state = state;
        this.offer = offer;
        this.sessionId = sessionId;
        this.transitioned = transitioned;
        this.reason = reason;
    }

    public OfferLifecycleState state() { return state; }
    public TripOffer offer() { return offer; }
    public long sessionId() { return sessionId; }
    public boolean transitioned() { return transitioned; }
    public String reason() { return reason; }
}
