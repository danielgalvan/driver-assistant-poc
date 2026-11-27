package com.driverassistant.capture;

/** Runtime API split for the official AccessibilityService screenshot methods. */
public final class ScreenshotApiPolicy {
    private ScreenshotApiPolicy() { }

    public static Mode modeForApi(int apiLevel) {
        if (apiLevel < 30) return Mode.UNSUPPORTED;
        if (apiLevel < 34) return Mode.DISPLAY_CROP_AFTER_FOREGROUND_CHECK;
        return Mode.TARGET_UBER_WINDOW;
    }

    public enum Mode { UNSUPPORTED, DISPLAY_CROP_AFTER_FOREGROUND_CHECK, TARGET_UBER_WINDOW }
}
