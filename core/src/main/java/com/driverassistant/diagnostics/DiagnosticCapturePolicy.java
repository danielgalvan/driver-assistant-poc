package com.driverassistant.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Android-free limits and counters for an explicitly started diagnostic capture session. */
public final class DiagnosticCapturePolicy {
    public static final long DEFAULT_MAX_DURATION_MILLIS = 8L * 60 * 60 * 1000;
    public static final long MIN_SNAPSHOT_INTERVAL_MILLIS = 1_000;
    public static final long OFFER_SNAPSHOT_REFRESH_MILLIS = 10_000;

    public enum AppendResult { APPENDED, INACTIVE, DURATION_LIMIT, STORAGE_LIMIT }

    private boolean active;
    private long startedAtMillis;
    private long maxDurationMillis;
    private long maxStorageBytes;
    private long reservedBytes;
    private long eventCount;
    private String stopReason;
    private final Map<String, SnapshotState> snapshotStates = new LinkedHashMap<>();

    private static final class SnapshotState {
        String lastFingerprint;
        String lastOfferFingerprint;
        long lastAtMillis;
    }

    public synchronized boolean start(long nowMillis, long existingBytes,
                                      long maxStorageBytes, long maxDurationMillis) {
        if (active || nowMillis < 0 || existingBytes < 0 || maxStorageBytes <= existingBytes
                || maxDurationMillis <= 0) return false;
        this.active = true;
        this.startedAtMillis = nowMillis;
        this.maxDurationMillis = maxDurationMillis;
        this.maxStorageBytes = maxStorageBytes;
        this.reservedBytes = existingBytes;
        this.eventCount = 0;
        this.stopReason = null;
        snapshotStates.clear();
        return true;
    }

    /** Reconstructs an active session after Android restarts the application process. */
    public synchronized boolean resume(long nowMillis, long startedAtMillis, long eventCount,
                                       long existingBytes, long maxStorageBytes, long maxDurationMillis) {
        if (active || nowMillis < startedAtMillis || eventCount < 0 || existingBytes < 0
                || maxStorageBytes <= existingBytes || maxDurationMillis <= 0
                || nowMillis - startedAtMillis >= maxDurationMillis) return false;
        this.active = true;
        this.startedAtMillis = startedAtMillis;
        this.maxDurationMillis = maxDurationMillis;
        this.maxStorageBytes = maxStorageBytes;
        this.reservedBytes = existingBytes;
        this.eventCount = eventCount;
        this.stopReason = null;
        snapshotStates.clear();
        return true;
    }

    /** Captures changed trees at a bounded rate while preserving every newly distinct offer candidate. */
    public synchronized boolean shouldPersistSnapshot(String fingerprint, boolean offerCandidate, long nowMillis) {
        return shouldPersistSnapshot(fingerprint, offerCandidate, false, nowMillis);
    }

    /** Also preserves distinct trees associated with explicit window or relevant text changes. */
    public synchronized boolean shouldPersistSnapshot(String fingerprint, boolean offerCandidate,
                                                       boolean relevantWindowChange, long nowMillis) {
        return shouldPersistSnapshot("default", fingerprint, offerCandidate, relevantWindowChange, nowMillis);
    }

    /** Deduplicates independently for each accessible window so one window cannot suppress another. */
    public synchronized boolean shouldPersistSnapshot(String scope, String fingerprint, boolean offerCandidate,
                                                       boolean relevantWindowChange, long nowMillis) {
        if (!active || fingerprint == null || nowMillis < startedAtMillis) return false;
        String safeScope = scope == null ? "default" : scope;
        SnapshotState state = snapshotStates.get(safeScope);
        if (state == null) {
            if (snapshotStates.size() >= 32) {
                String oldest = snapshotStates.keySet().iterator().next();
                snapshotStates.remove(oldest);
            }
            state = new SnapshotState();
            snapshotStates.put(safeScope, state);
        }
        boolean first = state.lastFingerprint == null;
        boolean changed = !fingerprint.equals(state.lastFingerprint);
        boolean newOfferCandidate = offerCandidate && !fingerprint.equals(state.lastOfferFingerprint);
        boolean relevantChange = changed && relevantWindowChange;
        boolean ordinarySnapshotDue = changed && nowMillis - state.lastAtMillis >= MIN_SNAPSHOT_INTERVAL_MILLIS;
        boolean offerRefreshDue = offerCandidate && nowMillis - state.lastAtMillis >= OFFER_SNAPSHOT_REFRESH_MILLIS;
        boolean shouldPersist = first || newOfferCandidate || relevantChange || ordinarySnapshotDue || offerRefreshDue;
        if (shouldPersist) {
            state.lastFingerprint = fingerprint;
            state.lastAtMillis = nowMillis;
        }
        if (offerCandidate) state.lastOfferFingerprint = fingerprint;
        return shouldPersist;
    }

    public synchronized AppendResult reserve(long nowMillis, long additionalBytes) {
        if (!active) return AppendResult.INACTIVE;
        if (nowMillis < startedAtMillis || nowMillis - startedAtMillis >= maxDurationMillis) {
            stop("duration_limit");
            return AppendResult.DURATION_LIMIT;
        }
        if (additionalBytes < 0 || additionalBytes > maxStorageBytes - reservedBytes) {
            stop("storage_limit");
            return AppendResult.STORAGE_LIMIT;
        }
        reservedBytes += additionalBytes;
        eventCount++;
        return AppendResult.APPENDED;
    }

    public synchronized String expireIfNeeded(long nowMillis) {
        if (!active) return stopReason;
        if (nowMillis < startedAtMillis || nowMillis - startedAtMillis >= maxDurationMillis) {
            stop("duration_limit");
        } else if (reservedBytes >= maxStorageBytes) {
            stop("storage_limit");
        }
        return stopReason;
    }

    public synchronized void stop(String reason) {
        if (!active) return;
        active = false;
        stopReason = reason == null ? "stopped" : reason;
    }

    public synchronized boolean active() { return active; }
    public synchronized long startedAtMillis() { return startedAtMillis; }
    public synchronized long eventCount() { return eventCount; }
    public synchronized long reservedBytes() { return reservedBytes; }
    public synchronized long maxStorageBytes() { return maxStorageBytes; }
    public synchronized long maxDurationMillis() { return maxDurationMillis; }
    public synchronized String stopReason() { return stopReason; }
}
