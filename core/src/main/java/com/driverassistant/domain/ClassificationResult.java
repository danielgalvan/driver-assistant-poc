package com.driverassistant.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class ClassificationResult {
    private final Map<Indicator, IndicatorResult> indicators;
    private final Signal overallSignal;
    private final boolean incomplete;
    private final boolean individualOnly;

    public ClassificationResult(Map<Indicator, IndicatorResult> indicators, Signal overallSignal,
                                boolean incomplete, boolean individualOnly) {
        EnumMap<Indicator, IndicatorResult> copy = new EnumMap<>(Indicator.class);
        copy.putAll(indicators);
        this.indicators = Collections.unmodifiableMap(copy);
        this.overallSignal = overallSignal;
        this.incomplete = incomplete;
        this.individualOnly = individualOnly;
    }

    public Map<Indicator, IndicatorResult> indicators() { return indicators; }
    public Signal overallSignal() { return overallSignal; }
    public boolean incomplete() { return incomplete; }
    public boolean individualOnly() { return individualOnly; }
}
