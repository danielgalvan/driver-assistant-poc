package com.driverassistant.calculator;

import com.driverassistant.config.ThresholdConfig;
import com.driverassistant.domain.ClassificationResult;
import com.driverassistant.domain.ClassificationStrategy;
import com.driverassistant.domain.Indicator;
import com.driverassistant.domain.IndicatorResult;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.Signal;

import java.util.EnumMap;
import java.util.Map;

public final class ClassificationService {
    public ClassificationResult classify(RideMetrics metrics, Double passengerRating,
                                         ThresholdConfig config) {
        ThresholdConfig safeConfig = config == null ? ThresholdConfig.defaults() : config;
        RideMetrics safeMetrics = metrics == null ? new RideMetrics(null, null, null, null) : metrics;
        Map<Indicator, IndicatorResult> results = new EnumMap<>(Indicator.class);

        add(results, Indicator.EARNINGS_PER_KM, safeMetrics.earningsPerKm(),
                safeConfig.perKmRedBelow(), safeConfig.perKmGreenFrom(), safeConfig.perKmEnabled());
        add(results, Indicator.EARNINGS_PER_HOUR, safeMetrics.earningsPerHour(),
                safeConfig.perHourRedBelow(), safeConfig.perHourGreenFrom(), safeConfig.perHourEnabled());
        add(results, Indicator.PASSENGER_RATING, passengerRating,
                safeConfig.ratingRedBelow(), safeConfig.ratingGreenFrom(), safeConfig.ratingEnabled());

        return overall(results, safeConfig.strategy());
    }

    private static void add(Map<Indicator, IndicatorResult> results, Indicator indicator,
                            Double value, double redBelow, double greenFrom, boolean enabled) {
        Signal signal = enabled ? signalFor(value, redBelow, greenFrom) : Signal.UNKNOWN;
        results.put(indicator, new IndicatorResult(indicator, valid(value) ? value : null, signal, enabled));
    }

    private static Signal signalFor(Double value, double redBelow, double greenFrom) {
        if (!valid(value)) return Signal.UNKNOWN;
        if (value < redBelow) return Signal.RED;
        if (value >= greenFrom) return Signal.GREEN;
        return Signal.YELLOW;
    }

    private static ClassificationResult overall(Map<Indicator, IndicatorResult> results,
                                                ClassificationStrategy strategy) {
        if (strategy == ClassificationStrategy.INDIVIDUAL_ONLY) {
            boolean missing = results.values().stream()
                    .anyMatch(item -> item.enabled() && item.signal() == Signal.UNKNOWN);
            return new ClassificationResult(results, Signal.UNKNOWN, missing, true);
        }

        int enabledCount = 0;
        int knownCount = 0;
        boolean missing = false;
        boolean red = false;
        boolean yellow = false;
        boolean financialGreen = false;
        Signal worst = Signal.GREEN;

        for (IndicatorResult item : results.values()) {
            if (!item.enabled()) continue;
            enabledCount++;
            Signal signal = item.signal();
            if (signal == Signal.UNKNOWN) {
                missing = true;
                continue;
            }
            knownCount++;
            if (signal == Signal.RED) {
                red = true;
                worst = Signal.RED;
            } else if (signal == Signal.YELLOW) {
                yellow = true;
                if (worst != Signal.RED) worst = Signal.YELLOW;
            } else if (item.indicator().isFinancial()) {
                financialGreen = true;
            }
        }

        if (enabledCount == 0 || knownCount == 0) {
            return new ClassificationResult(results, Signal.UNKNOWN, true, false);
        }

        Signal overall;
        if (strategy == ClassificationStrategy.FINANCIAL_GREEN) {
            if (red) overall = Signal.RED;
            else if (missing) overall = Signal.YELLOW;
            else if (financialGreen) overall = Signal.GREEN;
            else overall = Signal.YELLOW;
        } else {
            overall = worst;
        }
        return new ClassificationResult(results, overall, missing, false);
    }

    private static boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }
}
