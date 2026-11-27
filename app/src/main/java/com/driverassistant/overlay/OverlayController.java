package com.driverassistant.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import com.driverassistant.R;
import com.driverassistant.calculator.ClassificationService;
import com.driverassistant.config.ConfigRepository;
import com.driverassistant.config.ThresholdConfig;
import com.driverassistant.domain.ClassificationResult;
import com.driverassistant.domain.Indicator;
import com.driverassistant.domain.IndicatorResult;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.Signal;
import com.driverassistant.domain.TripOffer;
import com.driverassistant.lifecycle.OverlaySessionPolicy;
import com.driverassistant.util.DiagnosticLogStore;
import com.driverassistant.util.DiagnosticCaptureStore;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;

import java.util.Locale;

/** Apresenta um cartão não clicável e não focalizável sobre o app monitorado. */
public final class OverlayController {
    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConfigRepository config;
    private final OverlaySessionPolicy sessionPolicy = new OverlaySessionPolicy();
    private View card;
    private Runnable dismissTask;
    private volatile boolean visible;
    private long lastKeepAliveDiagnosticAt;

    public OverlayController(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.config = new ConfigRepository(service);
    }

    public void show(TripOffer offer, RideMetrics metrics, ClassificationResult classification,
                     long sessionId, boolean simulated) {
        recordCapture("OVERLAY_SHOW_REQUESTED", offer, sessionId, simulated,
                "overlay_enabled=" + (config.overlayEnabled() || simulated));
        if (!config.overlayEnabled() && !simulated) {
            recordCapture("OVERLAY_SHOW_SUPPRESSED", offer, sessionId, simulated, "reason=overlay_disabled");
            hide("overlay_disabled");
            return;
        }
        long requestedAt = android.os.SystemClock.uptimeMillis();
        handler.post(() -> showOnMainThread(offer, metrics, classification, sessionId, simulated, requestedAt));
    }

    public void hide() {
        hide("unspecified");
    }

    public void hide(String reason) {
        handler.post(() -> {
            boolean wasVisible = visible;
            sessionPolicy.clear();
            boolean removed = removeOnMainThread();
            if (wasVisible) {
                recordCapture("OVERLAY_REMOVE_REQUESTED", null, 0, false, "reason=" + safe(reason));
                recordCapture("OVERLAY_REMOVED", null, 0, false,
                        "reason=" + safe(reason) + "; removed=" + removed);
            }
        });
    }

    public boolean isVisible() { return visible; }

    /** Keeps a pending offer visible while fresh snapshots continue confirming the same session. */
    public void keepAlive(long sessionId) {
        handler.post(() -> keepAliveOnMainThread(sessionId));
    }

    private void keepAliveOnMainThread(long sessionId) {
        if (!visible || card == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        long durationMillis = config.overlayDurationSeconds() * 1000L;
        if (!sessionPolicy.refresh(sessionId, now, durationMillis)) return;
        scheduleDismiss(sessionId, null, false);
        if (now - lastKeepAliveDiagnosticAt >= 5_000) {
            lastKeepAliveDiagnosticAt = now;
            DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_KEEP_ALIVE",
                    "session=" + sessionId + "; reason=pending_offer_confirmed");
            recordCapture("OVERLAY_KEEP_ALIVE", null, sessionId, false,
                    "reason=pending_offer_confirmed");
        }
    }

    private void showOnMainThread(TripOffer offer, RideMetrics metrics,
                                  ClassificationResult classification, long sessionId, boolean simulated,
                                  long requestedAt) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            long durationMillis = config.overlayDurationSeconds() * 1000L;
            String fingerprint = displayFingerprint(offer, metrics, classification);
            OverlaySessionPolicy.Decision decision = sessionPolicy.show(sessionId, fingerprint, now, durationMillis);
            if (decision == OverlaySessionPolicy.Decision.EXPIRED) {
                boolean removed = removeOnMainThread();
                DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_EXPIRED", "session=" + sessionId);
                recordCapture("OVERLAY_EXPIRED", offer, sessionId, simulated,
                        "reason=session_expired; removed=" + removed);
                return;
            }
            if (decision == OverlaySessionPolicy.Decision.DUPLICATE) {
                recordCapture("OVERLAY_SHOW_SUPPRESSED", offer, sessionId, simulated, "reason=duplicate");
                return;
            }
            if (card == null) {
                ContextThemeWrapper themedContext = new ContextThemeWrapper(service, R.style.Theme_DriverAssistant);
                card = LayoutInflater.from(themedContext).inflate(R.layout.overlay_offer_card, null, false);
                card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            ThresholdConfig thresholds = config.loadThresholds();
            bind(card, offer, metrics, classification, thresholds, simulated);
            WindowManager.LayoutParams params = layoutParams();
            if (card.getParent() == null) windowManager.addView(card, params);
            else windowManager.updateViewLayout(card, params);
            visible = true;

            if (decision == OverlaySessionPolicy.Decision.NEW_SESSION) {
                scheduleDismiss(sessionId, offer, simulated);
            } else if (sessionPolicy.refresh(sessionId, now, durationMillis)) {
                scheduleDismiss(sessionId, offer, simulated);
            }
            DiagnosticLogStore.record(service, "DA_OVERLAY",
                    decision == OverlaySessionPolicy.Decision.NEW_SESSION ? "OVERLAY_SHOWN" : "OVERLAY_UPDATED",
                    "source=" + (simulated ? "simulation" : "uber") + "; session=" + sessionId
                            + "; main_queue_ms=" + Math.max(0, android.os.SystemClock.uptimeMillis() - requestedAt));
            recordCapture(decision == OverlaySessionPolicy.Decision.NEW_SESSION
                    ? "OVERLAY_SHOWN" : "OVERLAY_UPDATED", offer, sessionId, simulated,
                    "decision=" + decision + "; visible=" + visible + "; main_queue_ms="
                            + Math.max(0, android.os.SystemClock.uptimeMillis() - requestedAt));
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            recordCapture("OVERLAY_ERROR", offer, sessionId, simulated,
                    "exception=" + exception.getClass().getSimpleName());
            sessionPolicy.clear();
            removeOnMainThread();
        }
    }

    private void bind(View root, TripOffer offer, RideMetrics metrics,
                      ClassificationResult classification, ThresholdConfig thresholds, boolean simulated) {
        TextView sourceBadge = root.findViewById(R.id.overlay_app_badge);
        sourceBadge.setText(simulated ? "SIM" : "U");
        sourceBadge.setTextSize(simulated ? 8 : 11);
        sourceBadge.setBackgroundTintList(ColorStateList.valueOf(service.getColor(
                simulated ? R.color.brand_primary : R.color.ink)));
        sourceBadge.setPadding(simulated ? dp(3) : 0, 0, simulated ? dp(3) : 0, 0);
        sourceBadge.setContentDescription(simulated ? "Simulação, não é uma oferta real" : "Uber Driver");
        setText(root, R.id.overlay_title, (simulated ? "SIMULAÇÃO · " : "") + summary(metrics));
        setIndicatorMetric(root, R.id.overlay_per_km_item, R.id.overlay_per_km,
                R.id.overlay_per_km_bar, service.getString(R.string.offer_per_km),
                indicator(classification, Indicator.EARNINGS_PER_KM),
                config.showPerKm() && thresholds.perKmEnabled());
        setIndicatorMetric(root, R.id.overlay_per_hour_item, R.id.overlay_per_hour,
                R.id.overlay_per_hour_bar, service.getString(R.string.offer_per_hour),
                indicator(classification, Indicator.EARNINGS_PER_HOUR),
                config.showPerHour() && thresholds.perHourEnabled());
        setIndicatorMetric(root, R.id.overlay_rating_item, R.id.overlay_rating,
                R.id.overlay_rating_bar, service.getString(R.string.offer_rating),
                indicator(classification, Indicator.PASSENGER_RATING),
                config.showRating() && thresholds.ratingEnabled());

        Signal overallSignal = Signal.UNKNOWN;
        String overallLabel = "INCOMPLETO";
        if (classification != null && classification.individualOnly()) {
            overallLabel = "INCOMPLETO";
        } else if (classification != null && classification.incomplete()) {
            overallLabel = "INCOMPLETO";
        } else if (classification != null) {
            overallSignal = classification.overallSignal();
            overallLabel = recommendation(overallSignal);
        }
        TextView overall = root.findViewById(R.id.overlay_overall);
        if (!config.showOverall() || (classification != null && classification.individualOnly())) {
            overall.setVisibility(View.GONE);
        } else {
            overall.setVisibility(View.VISIBLE);
            overall.setText(overallLabel);
            overall.setTextColor(badgeTextColor(overallSignal));
            overall.setBackgroundTintList(ColorStateList.valueOf(badgeColor(overallSignal)));
        }

        MaterialCardView offerCard = root.findViewById(R.id.offer_card);
        int borderColor = config.showOverall()
                ? color(overallSignal) : service.getColor(R.color.surface_border);
        offerCard.setStrokeColor(borderColor);
        offerCard.setStrokeWidth(dp(2));

        boolean showCategory = config.showCategory();
        boolean showPickup = config.showPickupDistance();
        boolean showTrip = config.showTripDistance();
        setOptional(root, R.id.overlay_amount, service.getString(R.string.offer_amount),
                money(offer == null ? null : offer.offeredAmount()), config.showAmount());
        setOptional(root, R.id.overlay_category, service.getString(R.string.offer_category),
                offer == null ? null : offer.category(), showCategory);
        setOptional(root, R.id.overlay_pickup_distance, service.getString(R.string.offer_pickup_distance),
                travelMetric(offer == null ? null : offer.pickupDistanceKm(), offer == null ? null : offer.pickupEtaMinutes()), showPickup);
        setOptional(root, R.id.overlay_trip_distance, service.getString(R.string.offer_trip_distance),
                travelMetric(offer == null ? null : offer.tripDistanceKm(), offer == null ? null : offer.tripDurationMinutes()), showTrip);
        root.findViewById(R.id.overlay_details).setVisibility(
                showCategory || showPickup || showTrip ? View.VISIBLE : View.GONE);
    }

    private void setIndicatorMetric(View root, int itemId, int valueId, int barId,
                                   String label, IndicatorResult result, boolean visible) {
        View item = root.findViewById(itemId);
        if (!visible) {
            item.setVisibility(View.GONE);
            return;
        }
        item.setVisibility(View.VISIBLE);
        boolean available = result != null && result.enabled() && result.value() != null;
        String value = available ? indicatorValue(result) : "—";
        Signal signal = result == null || !result.enabled() ? Signal.UNKNOWN : result.signal();
        TextView valueView = root.findViewById(valueId);
        valueView.setText(value);
        valueView.setAutoSizeTextTypeUniformWithConfiguration(13, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        View bar = root.findViewById(barId);
        bar.setBackgroundTintList(ColorStateList.valueOf(color(signal)));
        item.setContentDescription(label + ": " + value + ", " + signalName(signal));
    }

    private void setOptional(View root, int id, String label, String value, boolean visible) {
        TextView view = root.findViewById(id);
        if (!visible) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(label + ": " + (value == null ? service.getString(R.string.unknown) : value));
    }

    private static IndicatorResult indicator(ClassificationResult result, Indicator indicator) {
        return result == null ? null : result.indicators().get(indicator);
    }

    private WindowManager.LayoutParams layoutParams() {
        String position = config.overlayPosition();
        boolean left = "TOP_LEFT".equals(position) || "BOTTOM_LEFT".equals(position);
        boolean bottom = "BOTTOM_LEFT".equals(position) || "BOTTOM_RIGHT".equals(position);
        int gravity = (bottom ? Gravity.BOTTOM : Gravity.TOP) | (left ? Gravity.LEFT : Gravity.RIGHT);
        int margin = dp(14);
        int y = dp(bottom ? 96 : 76);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        int availableWidth = service.getResources().getDisplayMetrics().widthPixels - 2 * margin;
        params.width = Math.max(dp(1), Math.min(dp(360), availableWidth));
        params.gravity = gravity;
        params.x = margin;
        params.y = y;
        return params;
    }

    private static String distance(Double value) {
        return valid(value) ? String.format(Locale.forLanguageTag("pt-BR"), "%.1f km", value) : null;
    }

    private static String duration(Double value) {
        return valid(value) ? String.format(Locale.forLanguageTag("pt-BR"), "%.0f min", value) : null;
    }

    private static String money(Double value) {
        return valid(value) ? String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", value) : "—";
    }

    private static String indicatorValue(IndicatorResult result) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f", result.value());
    }

    private static boolean valid(Double value) { return value != null && Double.isFinite(value); }

    private String travelMetric(Double distanceKm, Double minutes) {
        StringBuilder value = new StringBuilder();
        if (valid(distanceKm)) value.append(distance(distanceKm));
        if (valid(minutes)) {
            if (value.length() > 0) value.append(" · ");
            value.append(duration(minutes));
        }
        return value.length() == 0 ? null : value.toString();
    }

    private String signalName(Signal signal) {
        if (signal == null || signal == Signal.UNKNOWN) return "ANALISAR";
        return switch (signal) {
            case RED -> "RUIM";
            case YELLOW -> "ATENÇÃO";
            case GREEN -> "BOM";
            case UNKNOWN -> "ANALISAR";
        };
    }

    private String recommendation(Signal signal) {
        return switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> "POUCO RENTÁVEL";
            case YELLOW -> "AVALIAR";
            case GREEN -> "INTERESSANTE";
            case UNKNOWN -> "INCOMPLETO";
        };
    }

    private String summary(RideMetrics metrics) {
        StringBuilder summary = new StringBuilder();
        String time = config.showTotalTime() && metrics != null
                ? duration(metrics.totalDurationMinutes()) : null;
        String totalDistance = config.showTotalDistance() && metrics != null
                ? distance(metrics.totalDistanceKm()) : null;
        if (time != null) summary.append(time);
        if (totalDistance != null) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(totalDistance);
        }
        return summary.length() == 0 ? "Oferta Uber" : summary.toString();
    }

    private int color(Signal signal) {
        int resource = switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> R.color.signal_red;
            case YELLOW -> R.color.signal_yellow;
            case GREEN -> R.color.signal_green;
            case UNKNOWN -> R.color.signal_unknown;
        };
        return service.getColor(resource);
    }

    private int softColor(Signal signal) {
        int resource = switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> R.color.signal_red_soft;
            case YELLOW -> R.color.signal_yellow_soft;
            case GREEN -> R.color.signal_green_soft;
            case UNKNOWN -> R.color.signal_unknown_soft;
        };
        return service.getColor(resource);
    }

    private int badgeColor(Signal signal) {
        return switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> color(Signal.RED);
            case YELLOW -> softColor(Signal.YELLOW);
            case GREEN -> color(Signal.GREEN);
            case UNKNOWN -> softColor(Signal.UNKNOWN);
        };
    }

    private int badgeTextColor(Signal signal) {
        return switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED, GREEN -> service.getColor(android.R.color.white);
            case YELLOW, UNKNOWN -> color(signal);
        };
    }

    private static void setText(View root, int id, String value) {
        TextView view = root.findViewById(id);
        view.setText(value);
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private boolean removeOnMainThread() {
        if (dismissTask != null) handler.removeCallbacks(dismissTask);
        dismissTask = null;
        boolean removed = true;
        if (card != null && card.getParent() != null) {
            try {
                windowManager.removeView(card);
            } catch (RuntimeException ignored) {
                removed = false;
            }
        }
        visible = false;
        return removed;
    }

    private void scheduleDismiss(long sessionId, TripOffer offer, boolean simulated) {
        if (dismissTask != null) handler.removeCallbacks(dismissTask);
        dismissTask = () -> {
            if (sessionPolicy.expire(sessionId, android.os.SystemClock.uptimeMillis())) {
                boolean removed = removeOnMainThread();
                DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_EXPIRED", "session=" + sessionId);
                recordCapture("OVERLAY_EXPIRED", offer, sessionId, simulated,
                        "reason=display_duration_without_fresh_offer; removed=" + removed);
            }
        };
        handler.postDelayed(dismissTask, sessionPolicy.remainingMillis(
                android.os.SystemClock.uptimeMillis()));
    }

    private void recordCapture(String kind, TripOffer offer, long sessionId, boolean simulated, String reason) {
        DiagnosticCaptureStore store = DiagnosticCaptureStore.get(service);
        if (!store.isActive()) return;
        store.record(kind, DiagnosticCaptureStore.object(
                "session_id", sessionId, "simulated", simulated, "reason", reason,
                "amount", offer == null ? null : offer.offeredAmount(),
                "category", offer == null ? null : DiagnosticTextSanitizer.sanitizeCategory(offer.category()),
                "rating", offer == null ? null : offer.passengerRating(),
                "pickup_km", offer == null ? null : offer.pickupDistanceKm(),
                "pickup_minutes", offer == null ? null : offer.pickupEtaMinutes(),
                "trip_km", offer == null ? null : offer.tripDistanceKm(),
                "trip_minutes", offer == null ? null : offer.tripDurationMinutes()));
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_:-]", "_");
    }

    private static String displayFingerprint(TripOffer offer, RideMetrics metrics,
                                             ClassificationResult classification) {
        if (offer == null) return "empty";
        return String.valueOf(offer.offeredAmount()) + '|' + offer.category() + '|'
                + offer.passengerRating() + '|' + offer.pickupDistanceKm() + '|'
                + offer.pickupEtaMinutes() + '|' + offer.tripDistanceKm() + '|'
                + offer.tripDurationMinutes() + '|' + (metrics == null ? "" : metrics.totalDistanceKm()) + '|'
                + (metrics == null ? "" : metrics.totalDurationMinutes()) + '|'
                + (classification == null ? "" : classification.overallSignal()) + '|'
                + (classification != null && classification.incomplete());
    }
}
