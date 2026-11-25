package com.driverassistant.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import com.driverassistant.util.DiagnosticLogStore;

import java.util.Locale;

/** Apresenta um cartão não clicável e não focalizável sobre o app monitorado. */
public final class OverlayController {
    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConfigRepository config;
    private View card;
    private Runnable dismissTask;

    public OverlayController(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.config = new ConfigRepository(service);
    }

    public void show(TripOffer offer, RideMetrics metrics, ClassificationResult classification) {
        if (!config.overlayEnabled()) {
            hide();
            return;
        }
        handler.post(() -> showOnMainThread(offer, metrics, classification, false));
    }

    public void hide() {
        handler.post(this::removeOnMainThread);
    }

    private void showOnMainThread(TripOffer offer, RideMetrics metrics,
                                  ClassificationResult classification, boolean simulated) {
        try {
            if (card == null) {
                card = LayoutInflater.from(service).inflate(R.layout.overlay_offer_card, null, false);
            }
            ThresholdConfig thresholds = config.loadThresholds();
            bind(card, offer, metrics, classification, thresholds, simulated);
            WindowManager.LayoutParams params = layoutParams();
            if (card.getParent() == null) windowManager.addView(card, params);
            else windowManager.updateViewLayout(card, params);

            if (dismissTask != null) handler.removeCallbacks(dismissTask);
            dismissTask = this::removeOnMainThread;
            handler.postDelayed(dismissTask, config.overlayDurationSeconds() * 1000L);
            DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_SHOWN", "source=uber");
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(service, "DA_OVERLAY", "OVERLAY_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            removeOnMainThread();
        }
    }

    private void bind(View root, TripOffer offer, RideMetrics metrics,
                      ClassificationResult classification, ThresholdConfig thresholds, boolean simulated) {
        setText(root, R.id.overlay_title, summary(metrics));
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
        String overallLabel = "ANALISAR";
        if (classification != null && classification.individualOnly()) {
            overallLabel = "INDIVIDUAL";
        } else if (classification != null && classification.incomplete()) {
            overallLabel = "INCOMPLETO";
        } else if (classification != null) {
            overallSignal = classification.overallSignal();
            overallLabel = recommendation(overallSignal);
        }
        TextView overall = root.findViewById(R.id.overlay_overall);
        if (!config.showOverall()) {
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

        boolean showCategory = config.showCategory() && offer != null && offer.category() != null;
        boolean showPickup = config.showPickupDistance() && offer != null && offer.pickupDistanceKm() != null;
        boolean showTrip = config.showTripDistance() && offer != null && offer.tripDistanceKm() != null;
        setOptional(root, R.id.overlay_category, service.getString(R.string.offer_category),
                offer == null ? null : offer.category(), showCategory);
        setOptional(root, R.id.overlay_pickup_distance, service.getString(R.string.offer_pickup_distance),
                distance(offer == null ? null : offer.pickupDistanceKm()), showPickup);
        setOptional(root, R.id.overlay_trip_distance, service.getString(R.string.offer_trip_distance),
                distance(offer == null ? null : offer.tripDistanceKm()), showTrip);
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
        params.width = Math.min(dp(360), Math.max(dp(240), availableWidth));
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
            case RED -> "REJEITAR";
            case YELLOW -> "ATENÇÃO";
            case GREEN -> "ACEITAR";
            case UNKNOWN -> "ANALISAR";
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
            if (!summary.isEmpty()) summary.append(" · ");
            summary.append(totalDistance);
        }
        return summary.isEmpty() ? "Oferta Uber" : summary.toString();
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

    private void removeOnMainThread() {
        if (dismissTask != null) handler.removeCallbacks(dismissTask);
        dismissTask = null;
        if (card != null && card.getParent() != null) {
            try {
                windowManager.removeView(card);
            } catch (RuntimeException ignored) {
                // O sistema pode remover a janela durante a desconexão do serviço.
            }
        }
    }
}
