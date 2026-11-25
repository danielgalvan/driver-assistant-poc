package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Parsing outcome with privacy-safe field diagnostics. */
public final class OfferParseResult {
    private final TripOffer offer;
    private final String rejectionReason;
    private final Set<String> foundFields;
    private final Set<String> unavailableFields;

    public OfferParseResult(TripOffer offer, String rejectionReason,
                            Set<String> foundFields, Set<String> unavailableFields) {
        this.offer = offer;
        this.rejectionReason = rejectionReason;
        this.foundFields = Collections.unmodifiableSet(new LinkedHashSet<>(foundFields));
        this.unavailableFields = Collections.unmodifiableSet(new LinkedHashSet<>(unavailableFields));
    }

    public TripOffer offer() { return offer; }
    public String rejectionReason() { return rejectionReason; }
    public Set<String> foundFields() { return foundFields; }
    public Set<String> unavailableFields() { return unavailableFields; }
    public boolean accepted() { return offer != null; }
}
