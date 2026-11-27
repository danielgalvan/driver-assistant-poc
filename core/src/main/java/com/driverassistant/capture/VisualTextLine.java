package com.driverassistant.capture;

/** Transient OCR line and its screenshot-relative bounds. Text must not be persisted unsanitized. */
public record VisualTextLine(String text, int left, int top, int right, int bottom, int order) {
    public int width() { return Math.max(0, right - left); }
    public int height() { return Math.max(0, bottom - top); }
    public int centerY() { return top + height() / 2; }
}
