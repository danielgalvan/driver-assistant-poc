package com.driverassistant.calculator;

import com.driverassistant.config.ThresholdConfig;
import com.driverassistant.domain.ClassificationResult;
import com.driverassistant.domain.ClassificationStrategy;
import com.driverassistant.domain.Indicator;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.Signal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ClassificationServiceTest {
    private final ClassificationService service = new ClassificationService();

    @Test public void appliesRedYellowGreenBoundaries() {
        ThresholdConfig config = ThresholdConfig.defaults();
        assertEquals(Signal.RED, classify(1.39, 40.0, 4.9, config).indicators().get(Indicator.EARNINGS_PER_KM).signal());
        assertEquals(Signal.YELLOW, classify(1.40, 40.0, 4.9, config).indicators().get(Indicator.EARNINGS_PER_KM).signal());
        assertEquals(Signal.YELLOW, classify(1.79, 40.0, 4.9, config).indicators().get(Indicator.EARNINGS_PER_KM).signal());
        assertEquals(Signal.GREEN, classify(1.80, 40.0, 4.9, config).indicators().get(Indicator.EARNINGS_PER_KM).signal());
    }

    @Test public void defaultStrategyUsesWorstEnabledIndicator() {
        ClassificationResult result = classify(2.0, 20.0, 5.0, ThresholdConfig.defaults());
        assertEquals(ClassificationStrategy.WORST_ENABLED, ThresholdConfig.defaults().strategy());
        assertEquals(Signal.RED, result.overallSignal());
    }

    @Test public void financialGreenAllowsKnownYellowIndicators() {
        ThresholdConfig config = config(ClassificationStrategy.FINANCIAL_GREEN,
                true, true, true);
        ClassificationResult result = classify(2.0, 35.0, 4.6, config);
        assertEquals(Signal.GREEN, result.overallSignal());
        assertFalse(result.incomplete());
    }

    @Test public void financialGreenDoesNotIgnoreUnknownEnabledIndicators() {
        ThresholdConfig config = config(ClassificationStrategy.FINANCIAL_GREEN,
                true, true, true);
        ClassificationResult result = service.classify(new RideMetrics(6.0, 30.0, 2.0, 40.0), null, config);
        assertEquals(Signal.YELLOW, result.overallSignal());
        assertTrue(result.incomplete());
    }

    @Test public void disabledIndicatorsDoNotAffectOverallClassification() {
        ThresholdConfig config = config(ClassificationStrategy.WORST_ENABLED,
                true, false, false);
        ClassificationResult result = classify(2.0, 5.0, 0.0, config);
        assertEquals(Signal.GREEN, result.overallSignal());
        assertFalse(result.indicators().get(Indicator.EARNINGS_PER_HOUR).enabled());
        assertEquals(Signal.UNKNOWN, result.indicators().get(Indicator.EARNINGS_PER_HOUR).signal());
        assertFalse(result.incomplete());
    }

    @Test public void missingEnabledValuesAreUnknownAndMarkClassificationIncomplete() {
        ClassificationResult result = service.classify(new RideMetrics(null, null, null, null), null,
                ThresholdConfig.defaults());
        assertEquals(Signal.UNKNOWN, result.overallSignal());
        assertTrue(result.incomplete());
    }

    @Test public void individualOnlyStrategyDoesNotCreateOverallSignal() {
        ClassificationResult result = classify(2.0, 40.0, 4.9,
                config(ClassificationStrategy.INDIVIDUAL_ONLY, true, true, true));
        assertTrue(result.individualOnly());
        assertEquals(Signal.UNKNOWN, result.overallSignal());
        assertFalse(result.incomplete());
    }

    @Test public void individualOnlyStrategyMarksUnavailableIndicatorsIncomplete() {
        ClassificationResult result = service.classify(new RideMetrics(6.0, null, 2.0, null), null,
                config(ClassificationStrategy.INDIVIDUAL_ONLY, true, true, true));
        assertTrue(result.individualOnly());
        assertTrue(result.incomplete());
    }

    @Test public void invalidThresholdPairsAreRejected() {
        assertFalse(new ThresholdConfig(-1, 2, 20, 30, 3, 5,
                true, true, true, ClassificationStrategy.WORST_ENABLED).isValid());
        assertFalse(new ThresholdConfig(2, 1, 20, 30, 3, 5,
                true, true, true, ClassificationStrategy.WORST_ENABLED).isValid());
        assertFalse(new ThresholdConfig(1, 2, 20, 30, 3, 5.1,
                true, true, true, ClassificationStrategy.WORST_ENABLED).isValid());
    }

    private ClassificationResult classify(double perKm, double perHour, double rating, ThresholdConfig config) {
        return service.classify(new RideMetrics(10.0, 60.0, perKm, perHour), rating, config);
    }

    private static ThresholdConfig config(ClassificationStrategy strategy,
                                          boolean perKm, boolean perHour, boolean rating) {
        return new ThresholdConfig(1.4, 1.8, 30, 40, 4.3, 4.9,
                perKm, perHour, rating, strategy);
    }
}
