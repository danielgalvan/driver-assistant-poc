package com.driverassistant.capture;

import com.driverassistant.parser.AccessibilityNode;
import com.driverassistant.parser.OfferParseResult;
import com.driverassistant.parser.OfferParser;

import java.util.List;

/** Existing structural parser exposed as one source in the hybrid capture pipeline. */
public final class AccessibilityOfferCaptureSource implements OfferCaptureSource {
    private final OfferParser parser;

    public AccessibilityOfferCaptureSource(OfferParser parser) {
        this.parser = parser;
    }

    @Override public String sourceId() { return "accessibility"; }

    public OfferParseResult parse(List<AccessibilityNode> nodes, boolean diagnostics) {
        return parser.parseNodes(nodes, diagnostics);
    }
}
