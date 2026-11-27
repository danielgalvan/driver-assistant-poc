package com.driverassistant.capture;

/** Guards asynchronous OCR results against offer expiry and capture invalidation. */
public final class CaptureResultPolicy {
    public static final long MAX_RESULT_AGE_MILLIS = 2_500;

    private CaptureResultPolicy() { }

    public static boolean mayApply(long requestGeneration, long currentGeneration,
                                   long requestedAtElapsedMillis, long nowElapsedMillis,
                                   boolean monitoringEnabled) {
        if (!monitoringEnabled || requestGeneration != currentGeneration
                || requestedAtElapsedMillis < 0 || nowElapsedMillis < requestedAtElapsedMillis) {
            return false;
        }
        return nowElapsedMillis - requestedAtElapsedMillis <= MAX_RESULT_AGE_MILLIS;
    }
}
