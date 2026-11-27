package com.driverassistant.diagnostics;

import java.util.concurrent.atomic.AtomicBoolean;

/** Android-free backoff and single-flight policy for checking a visible Uber window. */
public final class ForegroundPollPolicy {
    public static final long FAST_INTERVAL_MILLIS = 350;
    public static final long STABLE_INTERVAL_MILLIS = 1_000;
    public static final long NOT_VISIBLE_INTERVAL_MILLIS = 1_500;
    private static final int STABLE_POLLS_BEFORE_BACKOFF = 1;

    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean enabled;
    private int stablePolls;
    private volatile long nextDelayMillis = FAST_INTERVAL_MILLIS;

    public synchronized void enable() {
        enabled = true;
        stablePolls = 0;
        nextDelayMillis = FAST_INTERVAL_MILLIS;
    }

    public synchronized void disable() {
        enabled = false;
        inFlight.set(false);
        stablePolls = 0;
    }

    /** An accessibility change returns the next poll to the fast interval. */
    public synchronized void signalActivity() {
        stablePolls = 0;
        nextDelayMillis = FAST_INTERVAL_MILLIS;
    }

    public synchronized boolean begin() {
        return enabled && inFlight.compareAndSet(false, true);
    }

    public synchronized long finish(boolean uberVisible, boolean snapshotChanged) {
        inFlight.set(false);
        if (!enabled) return NOT_VISIBLE_INTERVAL_MILLIS;
        if (!uberVisible) {
            stablePolls = 0;
            nextDelayMillis = NOT_VISIBLE_INTERVAL_MILLIS;
        } else if (snapshotChanged) {
            stablePolls = 0;
            nextDelayMillis = FAST_INTERVAL_MILLIS;
        } else {
            stablePolls++;
            nextDelayMillis = stablePolls >= STABLE_POLLS_BEFORE_BACKOFF
                    ? STABLE_INTERVAL_MILLIS : FAST_INTERVAL_MILLIS;
        }
        return nextDelayMillis;
    }

    public boolean enabled() { return enabled; }
    public boolean inFlight() { return inFlight.get(); }
    public long nextDelayMillis() { return nextDelayMillis; }
}
