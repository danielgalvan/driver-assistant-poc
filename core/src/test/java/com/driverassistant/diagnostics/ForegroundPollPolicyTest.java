package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ForegroundPollPolicyTest {
    @Test public void startsFastAndBacksOffAfterStableSnapshots() {
        ForegroundPollPolicy policy = new ForegroundPollPolicy();
        policy.enable();

        assertEquals(350, policy.nextDelayMillis());
        assertEquals(1_000, policy.finish(true, false));

        assertEquals(350, policy.finish(true, true));
    }

    @Test public void allowsOnlyOnePollAtATimeAndResumesAfterCompletion() {
        ForegroundPollPolicy policy = new ForegroundPollPolicy();
        policy.enable();

        assertTrue(policy.begin());
        assertFalse(policy.begin());
        assertTrue(policy.inFlight());
        policy.finish(true, true);
        assertFalse(policy.inFlight());
        assertTrue(policy.begin());
    }

    @Test public void backsOffWhenUberIsUnavailableAndCanResumeAfterInterruption() {
        ForegroundPollPolicy policy = new ForegroundPollPolicy();
        policy.enable();
        assertEquals(1_500, policy.finish(false, false));

        policy.disable();
        assertFalse(policy.begin());
        policy.enable();
        assertTrue(policy.begin());
        assertEquals(350, policy.finish(true, true));
    }

    @Test public void accessibilityActivityRestoresFastPolling() {
        ForegroundPollPolicy policy = new ForegroundPollPolicy();
        policy.enable();
        policy.finish(true, false);
        assertEquals(1_000, policy.nextDelayMillis());

        policy.signalActivity();

        assertEquals(350, policy.nextDelayMillis());
    }
}
