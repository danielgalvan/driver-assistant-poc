package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EventRateLimiterTest {
    @Test public void limitsRepeatedEventsButAllowsThemAfterTheWindow() {
        EventRateLimiter limiter = new EventRateLimiter(1_000, 4);
        assertTrue(limiter.allow("window_changed", 100));
        assertFalse(limiter.allow("window_changed", 500));
        assertTrue(limiter.allow("window_changed", 1_100));
        assertTrue(limiter.allow("text_changed", 500));
    }
}
