package com.driverassistant.capture;

/** Capture strategy selected by the user. The safe default keeps the visual source disabled. */
public enum OfferCaptureStrategy {
    ACCESSIBILITY_ONLY,
    OCR_ONLY,
    HYBRID,
    COMPARATIVE;

    public static OfferCaptureStrategy fromStoredValue(String value) {
        if (value == null) return ACCESSIBILITY_ONLY;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ACCESSIBILITY_ONLY;
        }
    }

    public boolean usesVisualCapture() {
        return this != ACCESSIBILITY_ONLY;
    }

    public boolean usesAccessibilityParsing() {
        return this != OCR_ONLY;
    }
}
