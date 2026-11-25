package com.driverassistant.lifecycle;

/** Pure timing policy used by the Android overlay and covered by JVM tests. */
public final class OverlaySessionPolicy {
    public enum Decision { NEW_SESSION, UPDATED, DUPLICATE, EXPIRED }

    private long sessionId = Long.MIN_VALUE;
    private long lastExpiredSessionId = Long.MIN_VALUE;
    private long deadlineMillis;
    private String fingerprint;

    public Decision show(long nextSessionId, String nextFingerprint, long nowMillis, long durationMillis) {
        if (durationMillis <= 0) throw new IllegalArgumentException("duration must be positive");
        if (nextSessionId == lastExpiredSessionId) return Decision.EXPIRED;
        if (sessionId != nextSessionId) {
            sessionId = nextSessionId;
            fingerprint = nextFingerprint;
            deadlineMillis = nowMillis + durationMillis;
            return Decision.NEW_SESSION;
        }
        if (nowMillis >= deadlineMillis) {
            lastExpiredSessionId = sessionId;
            clearActive();
            return Decision.EXPIRED;
        }
        if (java.util.Objects.equals(fingerprint, nextFingerprint)) return Decision.DUPLICATE;
        fingerprint = nextFingerprint;
        return Decision.UPDATED;
    }

    public boolean expire(long expectedSessionId, long nowMillis) {
        if (sessionId != expectedSessionId || nowMillis < deadlineMillis) return false;
        lastExpiredSessionId = sessionId;
        clearActive();
        return true;
    }

    public long remainingMillis(long nowMillis) { return Math.max(0, deadlineMillis - nowMillis); }
    public long sessionId() { return sessionId; }
    public boolean active() { return sessionId != Long.MIN_VALUE; }

    public void clear() {
        lastExpiredSessionId = Long.MIN_VALUE;
        clearActive();
    }

    private void clearActive() {
        sessionId = Long.MIN_VALUE;
        deadlineMillis = 0;
        fingerprint = null;
    }
}
