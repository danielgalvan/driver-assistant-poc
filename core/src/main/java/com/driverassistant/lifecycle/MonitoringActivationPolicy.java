package com.driverassistant.lifecycle;

/** Guards monitoring and capture actions against an enabled but disconnected accessibility service. */
public final class MonitoringActivationPolicy {
    private MonitoringActivationPolicy() {}

    public enum BlockReason {
        NONE,
        SERVICE_NOT_ENABLED,
        SERVICE_NOT_CONNECTED
    }

    public static BlockReason evaluate(boolean serviceEnabledInSettings, boolean serviceConnected) {
        if (!serviceEnabledInSettings) return BlockReason.SERVICE_NOT_ENABLED;
        if (!serviceConnected) return BlockReason.SERVICE_NOT_CONNECTED;
        return BlockReason.NONE;
    }
}
