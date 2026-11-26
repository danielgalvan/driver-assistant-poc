package com.driverassistant.lifecycle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MonitoringActivationPolicyTest {
    @Test public void blocksWhenAccessibilityPermissionIsDisabled() {
        assertEquals(MonitoringActivationPolicy.BlockReason.SERVICE_NOT_ENABLED,
                MonitoringActivationPolicy.evaluate(false, false));
        assertEquals(MonitoringActivationPolicy.BlockReason.SERVICE_NOT_ENABLED,
                MonitoringActivationPolicy.evaluate(false, true));
    }

    @Test public void blocksWhenServiceIsEnabledButDisconnected() {
        assertEquals(MonitoringActivationPolicy.BlockReason.SERVICE_NOT_CONNECTED,
                MonitoringActivationPolicy.evaluate(true, false));
    }

    @Test public void allowsActivationOnlyWhenServiceIsEnabledAndConnected() {
        assertEquals(MonitoringActivationPolicy.BlockReason.NONE,
                MonitoringActivationPolicy.evaluate(true, true));
    }
}
