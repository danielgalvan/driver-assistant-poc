package com.driverassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Allows at most one repeated event with a given key in a configured time window. */
public final class EventRateLimiter {
    private final long intervalMillis;
    private final int maxKeys;
    private final LinkedHashMap<String, Long> lastSeen = new LinkedHashMap<>();

    public EventRateLimiter(long intervalMillis, int maxKeys) {
        if (intervalMillis < 0 || maxKeys < 1) throw new IllegalArgumentException("invalid rate limit");
        this.intervalMillis = intervalMillis;
        this.maxKeys = maxKeys;
    }

    public synchronized boolean allow(String key, long nowMillis) {
        if (key == null) key = "";
        Long previous = lastSeen.get(key);
        if (previous != null && nowMillis - previous < intervalMillis) return false;
        lastSeen.put(key, nowMillis);
        while (lastSeen.size() > maxKeys) {
            String oldest = lastSeen.keySet().iterator().next();
            lastSeen.remove(oldest);
        }
        return true;
    }

    public synchronized Map<String, Long> snapshot() { return Map.copyOf(lastSeen); }
}
