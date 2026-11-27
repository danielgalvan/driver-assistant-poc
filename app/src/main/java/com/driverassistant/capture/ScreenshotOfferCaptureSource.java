package com.driverassistant.capture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Official AccessibilityService screenshot source. It never reads text from foreign windows. */
public final class ScreenshotOfferCaptureSource implements OfferCaptureSource, AutoCloseable {
    private static final long MIN_INTERVAL_MILLIS = 700;
    private final AccessibilityService service;
    private final String uberPackage;
    private final Executor callbackExecutor;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile long lastRequestAt;
    private volatile boolean closed;

    public ScreenshotOfferCaptureSource(AccessibilityService service, String uberPackage, Executor callbackExecutor) {
        this.service = service;
        this.uberPackage = uberPackage;
        this.callbackExecutor = callbackExecutor;
    }

    @Override public String sourceId() { return "screenshot_ocr"; }

    // Screenshot API references are reached only after ScreenshotApiPolicy rejects API < 30.
    @SuppressLint("NewApi")
    public void capture(int windowId, String trigger, Callback callback) {
        long requestedAt = SystemClock.elapsedRealtime();
        if (closed) { dispatch(callback, Capture.failure("source_closed", 0, windowId, false)); return; }
        ScreenshotApiPolicy.Mode apiMode = ScreenshotApiPolicy.modeForApi(Build.VERSION.SDK_INT);
        if (apiMode == ScreenshotApiPolicy.Mode.UNSUPPORTED) {
            dispatch(callback, Capture.failure("api_below_30", 0, windowId, false));
            return;
        }
        long previous = lastRequestAt;
        if (previous != 0 && requestedAt - previous < MIN_INTERVAL_MILLIS) {
            dispatch(callback, Capture.failure("rate_limited", 0, windowId, false));
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            dispatch(callback, Capture.failure("capture_already_in_flight", 0, windowId, false));
            return;
        }
        lastRequestAt = requestedAt;
        long requestGeneration = generation.get();
        boolean windowSpecific = apiMode == ScreenshotApiPolicy.Mode.TARGET_UBER_WINDOW;
        long captureStarted = SystemClock.elapsedRealtime();
        WindowContext context = inspectWindow(windowId, !windowSpecific);
        if (context.reason != null) {
            finish(callback, Capture.failure(context.reason, elapsed(captureStarted), windowId, windowSpecific));
            return;
        }
        try {
            AccessibilityService.TakeScreenshotCallback resultCallback = new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    callbackExecutor.execute(() -> handleSuccess(result, context, trigger, requestGeneration,
                            captureStarted, windowId, windowSpecific, callback));
                }

                @Override public void onFailure(int errorCode) {
                    callbackExecutor.execute(() -> finish(callback, Capture.failure(
                            screenshotError(errorCode), elapsed(captureStarted), windowId, windowSpecific)));
                }
            };
            if (windowSpecific) service.takeScreenshotOfWindow(windowId, callbackExecutor, resultCallback);
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, resultCallback);
        } catch (RuntimeException exception) {
            finish(callback, Capture.failure(exception.getClass().getSimpleName(), elapsed(captureStarted),
                    windowId, windowSpecific));
        }
    }

    // This callback is supplied only by the guarded API 30+ screenshot calls above.
    @SuppressLint("NewApi")
    private void handleSuccess(ScreenshotResult result, WindowContext before, String trigger, long requestGeneration,
                               long startedAt, int windowId, boolean windowSpecific, Callback callback) {
        Bitmap hardwareBitmap = null;
        Bitmap softwareBitmap = null;
        HardwareBuffer buffer = null;
        try {
            if (closed || generation.get() != requestGeneration) {
                finish(callback, Capture.failure("capture_invalidated", elapsed(startedAt), windowId, windowSpecific));
                return;
            }
            WindowContext after = inspectWindow(windowId, !windowSpecific);
            if (after.reason != null) {
                finish(callback, Capture.failure("context_changed_" + after.reason, elapsed(startedAt), windowId, windowSpecific));
                return;
            }
            buffer = result.getHardwareBuffer();
            ColorSpace colorSpace = result.getColorSpace();
            if (colorSpace == null) colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardwareBitmap == null) {
                finish(callback, Capture.failure("hardware_buffer_unavailable", elapsed(startedAt), windowId, windowSpecific));
                return;
            }
            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) {
                finish(callback, Capture.failure("software_copy_unavailable", elapsed(startedAt), windowId, windowSpecific));
                return;
            }
            Bitmap isolated = windowSpecific ? softwareBitmap : cropToUberWindow(softwareBitmap, before.targetBounds);
            if (isolated == null) {
                finish(callback, Capture.failure("target_window_crop_failed", elapsed(startedAt), windowId, windowSpecific));
                return;
            }
            if (isolated != softwareBitmap) softwareBitmap.recycle();
            softwareBitmap = null;
            Capture capture = Capture.success(isolated, elapsed(startedAt), windowId, windowSpecific,
                    trigger == null ? "window_change" : trigger, before.foreignWindowCount);
            finish(callback, capture);
        } catch (RuntimeException exception) {
            finish(callback, Capture.failure(exception.getClass().getSimpleName(), elapsed(startedAt),
                    windowId, windowSpecific));
        } finally {
            if (softwareBitmap != null && !softwareBitmap.isRecycled()) softwareBitmap.recycle();
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled()) hardwareBitmap.recycle();
            if (buffer != null) buffer.close();
        }
    }

    private WindowContext inspectWindow(int requestedWindowId, boolean requireUberForeground) {
        List<WindowObservation> observations = new ArrayList<>();
        boolean uberFound = false;
        Rect targetBounds = new Rect();
        int foreignWindows = 0;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) return WindowContext.rejected("window_list_unavailable");
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    String packageName = null;
                    try {
                        root = window.getRoot();
                        if (root != null && root.getPackageName() != null) packageName = root.getPackageName().toString();
                    } finally {
                        if (root != null) root.recycle();
                    }
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    WindowObservation observation = new WindowObservation(window.getId(), packageName,
                            window.getType(), window.isActive(), window.isFocused(), bounds);
                    observations.add(observation);
                    if (uberPackage.equals(packageName) && window.getId() == requestedWindowId) {
                        uberFound = true;
                        targetBounds.set(bounds);
                    } else if (!uberPackage.equals(packageName)) {
                        foreignWindows++;
                    }
                }
            } finally {
                for (AccessibilityWindowInfo window : windows) if (window != null) window.recycle();
            }
        } catch (RuntimeException exception) {
            return WindowContext.rejected("window_inspection_" + exception.getClass().getSimpleName());
        }
        if (!uberFound || targetBounds.isEmpty()) return WindowContext.rejected("uber_window_not_visible");
        boolean activeRootUber = isActiveRootUber();
        if (requireUberForeground && !activeRootUber) return WindowContext.rejected("uber_not_foreground");
        if (requireUberForeground) {
            for (WindowObservation observation : observations) {
                if (uberPackage.equals(observation.packageName)) continue;
                if (Rect.intersects(targetBounds, observation.bounds)) {
                    return WindowContext.rejected("foreign_window_overlaps_full_screen_capture");
                }
            }
        }
        return new WindowContext(null, targetBounds, foreignWindows);
    }

    private boolean isActiveRootUber() {
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            return root != null && root.getPackageName() != null && uberPackage.contentEquals(root.getPackageName());
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static Bitmap cropToUberWindow(Bitmap screenshot, Rect targetBounds) {
        if (targetBounds == null || targetBounds.isEmpty()) return null;
        int left = Math.max(0, targetBounds.left);
        int top = Math.max(0, targetBounds.top);
        int right = Math.min(screenshot.getWidth(), targetBounds.right);
        int bottom = Math.min(screenshot.getHeight(), targetBounds.bottom);
        if (right <= left || bottom <= top) return null;
        return Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top);
    }

    private void finish(Callback callback, Capture capture) {
        inFlight.set(false);
        dispatch(callback, capture);
    }

    private void dispatch(Callback callback, Capture capture) {
        if (callback == null) { if (capture.bitmap != null) capture.bitmap.recycle(); return; }
        callbackExecutor.execute(() -> callback.onComplete(capture));
    }

    private static long elapsed(long startedAt) { return Math.max(0, SystemClock.elapsedRealtime() - startedAt); }

    private static String screenshotError(int code) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return "android_screenshot_rate_limited";
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return "no_accessibility_screenshot_capability";
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) return "android_screenshot_internal_error";
        }
        if (Build.VERSION.SDK_INT >= 34) {
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) return "invalid_window";
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return "secure_window";
        }
        return "android_screenshot_error_" + code;
    }

    public void cancelPending() { generation.incrementAndGet(); }

    /** Rechecks package ownership and foreground safety after the OCR task has completed. */
    public boolean stillValid(int windowId, boolean windowSpecific) {
        return inspectWindow(windowId, !windowSpecific).reason == null;
    }

    @Override public void close() {
        closed = true;
        cancelPending();
    }

    public interface Callback { void onComplete(Capture capture); }

    public static final class Capture {
        public final Bitmap bitmap;
        public final String failureReason;
        public final long captureMillis;
        public final int windowId;
        public final boolean windowSpecific;
        public final String trigger;
        public final int foreignWindowCount;

        private Capture(Bitmap bitmap, String failureReason, long captureMillis, int windowId,
                        boolean windowSpecific, String trigger, int foreignWindowCount) {
            this.bitmap = bitmap; this.failureReason = failureReason; this.captureMillis = captureMillis;
            this.windowId = windowId; this.windowSpecific = windowSpecific;
            this.trigger = trigger; this.foreignWindowCount = foreignWindowCount;
        }
        static Capture success(Bitmap bitmap, long millis, int id, boolean windowSpecific, String trigger, int foreignCount) {
            return new Capture(bitmap, null, millis, id, windowSpecific, trigger, foreignCount);
        }
        static Capture failure(String reason, long millis, int id, boolean windowSpecific) {
            return new Capture(null, reason, millis, id, windowSpecific, null, 0);
        }
        public boolean succeeded() { return bitmap != null && failureReason == null; }
    }

    private static final class WindowContext {
        final String reason;
        final Rect targetBounds;
        final int foreignWindowCount;
        WindowContext(String reason, Rect bounds, int foreignCount) {
            this.reason = reason; targetBounds = new Rect(bounds); foreignWindowCount = foreignCount;
        }
        static WindowContext rejected(String reason) { return new WindowContext(reason, new Rect(), 0); }
    }

    private static final class WindowObservation {
        final int id;
        final String packageName;
        final int type;
        final boolean active;
        final boolean focused;
        final Rect bounds;
        WindowObservation(int id, String packageName, int type, boolean active, boolean focused, Rect bounds) {
            this.id = id; this.packageName = packageName; this.type = type;
            this.active = active; this.focused = focused; this.bounds = new Rect(bounds);
        }
    }
}
