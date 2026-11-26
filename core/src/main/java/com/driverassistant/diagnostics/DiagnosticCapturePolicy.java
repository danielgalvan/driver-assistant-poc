package com.driverassistant.diagnostics;

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
    private String lastSnapshotFingerprint;
    private String lastOfferSnapshotFingerprint;
    private long lastSnapshotAtMillis;

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
        this.lastSnapshotFingerprint = null;
        this.lastOfferSnapshotFingerprint = null;
        this.lastSnapshotAtMillis = 0;
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
        this.lastSnapshotFingerprint = null;
        this.lastOfferSnapshotFingerprint = null;
        this.lastSnapshotAtMillis = 0;
        return true;
    }

    /** Captures changed trees at a bounded rate while preserving every newly distinct offer candidate. */
    public synchronized boolean shouldPersistSnapshot(String fingerprint, boolean offerCandidate, long nowMillis) {
        if (!active || fingerprint == null || nowMillis < startedAtMillis) return false;
        boolean first = lastSnapshotFingerprint == null;
        boolean changed = !fingerprint.equals(lastSnapshotFingerprint);
        boolean newOfferCandidate = offerCandidate && !fingerprint.equals(lastOfferSnapshotFingerprint);
        boolean ordinarySnapshotDue = changed && nowMillis - lastSnapshotAtMillis >= MIN_SNAPSHOT_INTERVAL_MILLIS;
        boolean offerRefreshDue = offerCandidate && nowMillis - lastSnapshotAtMillis >= OFFER_SNAPSHOT_REFRESH_MILLIS;
        boolean shouldPersist = first || newOfferCandidate || ordinarySnapshotDue || offerRefreshDue;
        if (shouldPersist) {
            lastSnapshotFingerprint = fingerprint;
            lastSnapshotAtMillis = nowMillis;
        }
        if (offerCandidate) lastOfferSnapshotFingerprint = fingerprint;
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
