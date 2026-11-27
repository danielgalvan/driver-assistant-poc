package com.driverassistant.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ScreenshotApiPolicyTest {
    @Test public void structuralCaptureRemainsAvailableBelowScreenshotApi() {
        assertEquals(ScreenshotApiPolicy.Mode.UNSUPPORTED, ScreenshotApiPolicy.modeForApi(29));
    }

    @Test public void api30Through33RequiresWholeDisplaySafetyAndCrop() {
        assertEquals(ScreenshotApiPolicy.Mode.DISPLAY_CROP_AFTER_FOREGROUND_CHECK,
                ScreenshotApiPolicy.modeForApi(30));
        assertEquals(ScreenshotApiPolicy.Mode.DISPLAY_CROP_AFTER_FOREGROUND_CHECK,
                ScreenshotApiPolicy.modeForApi(33));
    }

    @Test public void api34AndNewerCanTargetOnlyTheUberWindow() {
        assertEquals(ScreenshotApiPolicy.Mode.TARGET_UBER_WINDOW, ScreenshotApiPolicy.modeForApi(34));
        assertEquals(ScreenshotApiPolicy.Mode.TARGET_UBER_WINDOW, ScreenshotApiPolicy.modeForApi(37));
    }
}
