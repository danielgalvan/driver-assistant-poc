package com.driverassistant.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.driverassistant.domain.ClassificationStrategy;
import com.driverassistant.capture.OfferCaptureStrategy;

/** Persistência local das preferências, isolada da interface e do serviço Android. */
public final class ConfigRepository {
    private static final String PREFS = "driver_assistant_config";
    private static final String PER_KM_RED = "per_km_red";
    private static final String PER_KM_GREEN = "per_km_green";
    private static final String PER_HOUR_RED = "per_hour_red";
    private static final String PER_HOUR_GREEN = "per_hour_green";
    private static final String RATING_RED = "rating_red";
    private static final String RATING_GREEN = "rating_green";
    private static final String PER_KM_ENABLED = "per_km_enabled";
    private static final String PER_HOUR_ENABLED = "per_hour_enabled";
    private static final String RATING_ENABLED = "rating_enabled";
    private static final String STRATEGY = "strategy";

    private final SharedPreferences preferences;

    public ConfigRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public ThresholdConfig loadThresholds() {
        ThresholdConfig defaults = ThresholdConfig.defaults();
        String strategyName = preferences.getString(STRATEGY, defaults.strategy().name());
        ClassificationStrategy strategy;
        try {
            strategy = ClassificationStrategy.valueOf(strategyName);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            strategy = defaults.strategy();
        }
        return new ThresholdConfig(
                number(PER_KM_RED, defaults.perKmRedBelow()),
                number(PER_KM_GREEN, defaults.perKmGreenFrom()),
                number(PER_HOUR_RED, defaults.perHourRedBelow()),
                number(PER_HOUR_GREEN, defaults.perHourGreenFrom()),
                number(RATING_RED, defaults.ratingRedBelow()),
                number(RATING_GREEN, defaults.ratingGreenFrom()),
                preferences.getBoolean(PER_KM_ENABLED, defaults.perKmEnabled()),
                preferences.getBoolean(PER_HOUR_ENABLED, defaults.perHourEnabled()),
                preferences.getBoolean(RATING_ENABLED, defaults.ratingEnabled()),
                strategy);
    }

    public boolean saveThresholds(ThresholdConfig config) {
        if (config == null || !config.isValid()) return false;
        preferences.edit()
                .putString(PER_KM_RED, Double.toString(config.perKmRedBelow()))
                .putString(PER_KM_GREEN, Double.toString(config.perKmGreenFrom()))
                .putString(PER_HOUR_RED, Double.toString(config.perHourRedBelow()))
                .putString(PER_HOUR_GREEN, Double.toString(config.perHourGreenFrom()))
                .putString(RATING_RED, Double.toString(config.ratingRedBelow()))
                .putString(RATING_GREEN, Double.toString(config.ratingGreenFrom()))
                .putBoolean(PER_KM_ENABLED, config.perKmEnabled())
                .putBoolean(PER_HOUR_ENABLED, config.perHourEnabled())
                .putBoolean(RATING_ENABLED, config.ratingEnabled())
                .putString(STRATEGY, config.strategy().name())
                .apply();
        return true;
    }

    public boolean monitoringEnabled() { return preferences.getBoolean("monitoring_enabled", false); }
    public void setMonitoringEnabled(boolean enabled) {
        preferences.edit().putBoolean("monitoring_enabled", enabled).apply();
    }

    public boolean overlayEnabled() { return preferences.getBoolean("overlay_enabled", true); }
    public int overlayDurationSeconds() { return preferences.getInt("overlay_duration_seconds", 12); }
    public String overlayPosition() { return preferences.getString("overlay_position", "TOP_RIGHT"); }
    public boolean showAmount() { return preferences.getBoolean("show_amount", true); }
    public boolean showCategory() { return preferences.getBoolean("show_category", false); }
    public boolean showRating() { return preferences.getBoolean("show_rating", true); }
    public boolean showPerKm() { return preferences.getBoolean("show_per_km", true); }
    public boolean showPerHour() { return preferences.getBoolean("show_per_hour", true); }
    public boolean showOverall() { return preferences.getBoolean("show_overall", true); }
    public boolean showPickupDistance() { return preferences.getBoolean("show_pickup_distance", false); }
    public boolean showTripDistance() { return preferences.getBoolean("show_trip_distance", false); }
    public boolean showTotalDistance() { return preferences.getBoolean("show_total_distance", true); }
    public boolean showTotalTime() { return preferences.getBoolean("show_total_time", true); }
    public long offerDisappearanceToleranceMillis() {
        return preferences.getLong("offer_disappearance_tolerance_ms", 2_200L);
    }
    public boolean detailedDiagnosticsEnabled() { return preferences.getBoolean("detailed_diagnostics", false); }
    public void setDetailedDiagnosticsEnabled(boolean enabled) {
        preferences.edit().putBoolean("detailed_diagnostics", enabled).apply();
    }

    public OfferCaptureStrategy offerCaptureStrategy() {
        return OfferCaptureStrategy.fromStoredValue(preferences.getString(
                "offer_capture_strategy", OfferCaptureStrategy.ACCESSIBILITY_ONLY.name()));
    }

    public void setOfferCaptureStrategy(OfferCaptureStrategy strategy) {
        OfferCaptureStrategy safe = strategy == null ? OfferCaptureStrategy.ACCESSIBILITY_ONLY : strategy;
        preferences.edit().putString("offer_capture_strategy", safe.name()).apply();
    }

    public boolean saveOverlaySettings(boolean enabled, int durationSeconds, String position,
                                       boolean amount, boolean category, boolean rating, boolean pickupDistance,
                                       boolean tripDistance, boolean totalDistance, boolean totalTime,
                                       boolean perKm, boolean perHour, boolean overall,
                                       long disappearanceToleranceMillis) {
        if (durationSeconds < 1 || durationSeconds > 120 || !isKnownPosition(position)
                || disappearanceToleranceMillis < 250 || disappearanceToleranceMillis > 10_000) return false;
        preferences.edit()
                .putBoolean("overlay_enabled", enabled)
                .putInt("overlay_duration_seconds", durationSeconds)
                .putString("overlay_position", position)
                .putBoolean("show_amount", amount)
                .putBoolean("show_category", category)
                .putBoolean("show_rating", rating)
                .putBoolean("show_per_km", perKm)
                .putBoolean("show_per_hour", perHour)
                .putBoolean("show_overall", overall)
                .putBoolean("show_pickup_distance", pickupDistance)
                .putBoolean("show_trip_distance", tripDistance)
                .putBoolean("show_total_distance", totalDistance)
                .putBoolean("show_total_time", totalTime)
                .putLong("offer_disappearance_tolerance_ms", disappearanceToleranceMillis)
                .apply();
        return true;
    }

    private double number(String key, double fallback) {
        try {
            String stored = preferences.getString(key, Double.toString(fallback));
            double parsed = Double.parseDouble(stored);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException | ClassCastException exception) {
            return fallback;
        }
    }

    private static boolean isKnownPosition(String position) {
        return "TOP_RIGHT".equals(position) || "TOP_LEFT".equals(position)
                || "BOTTOM_RIGHT".equals(position) || "BOTTOM_LEFT".equals(position);
    }
}
