package com.driverassistant.domain;

public final class IndicatorResult {
    private final Indicator indicator;
    private final Double value;
    private final Signal signal;
    private final boolean enabled;

    public IndicatorResult(Indicator indicator, Double value, Signal signal, boolean enabled) {
        this.indicator = indicator;
        this.value = value;
        this.signal = signal;
        this.enabled = enabled;
    }

    public Indicator indicator() { return indicator; }
    public Double value() { return value; }
    public Signal signal() { return signal; }
    public boolean enabled() { return enabled; }
}
