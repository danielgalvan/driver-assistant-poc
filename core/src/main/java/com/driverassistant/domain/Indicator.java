package com.driverassistant.domain;

public enum Indicator {
    EARNINGS_PER_KM(true), EARNINGS_PER_HOUR(true), PASSENGER_RATING(false);

    private final boolean financial;

    Indicator(boolean financial) { this.financial = financial; }

    public boolean isFinancial() { return financial; }
}
