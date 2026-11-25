package com.driverassistant.util;

/** Deduplicação determinística, isolada para permitir testes sem Android. */
public final class EventDeduplicator {
    private final long debounceMillis;
    private String previousFingerprint;
    private long previousTimestamp = Long.MIN_VALUE;

    public EventDeduplicator(long debounceMillis) {
        this.debounceMillis = Math.max(0, debounceMillis);
    }

    public synchronized boolean shouldProcess(String fingerprint, long timestampMillis) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) return false;
        boolean duplicate = fingerprint.equals(previousFingerprint)
                && timestampMillis >= previousTimestamp
                && timestampMillis - previousTimestamp < debounceMillis;
        if (!duplicate) {
            previousFingerprint = fingerprint;
            previousTimestamp = timestampMillis;
        }
        return !duplicate;
    }
}
