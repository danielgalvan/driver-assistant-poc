package com.driverassistant.config;

import com.driverassistant.domain.ClassificationStrategy;

import java.util.ArrayList;
import java.util.List;

/** Limites configuráveis. Os valores padrão são parâmetros iniciais da POC. */
public final class ThresholdConfig {
    private final double perKmRedBelow;
    private final double perKmGreenFrom;
    private final double perHourRedBelow;
    private final double perHourGreenFrom;
    private final double ratingRedBelow;
    private final double ratingGreenFrom;
    private final boolean perKmEnabled;
    private final boolean perHourEnabled;
    private final boolean ratingEnabled;
    private final ClassificationStrategy strategy;

    public ThresholdConfig(double perKmRedBelow, double perKmGreenFrom,
                           double perHourRedBelow, double perHourGreenFrom,
                           double ratingRedBelow, double ratingGreenFrom,
                           boolean perKmEnabled, boolean perHourEnabled,
                           boolean ratingEnabled, ClassificationStrategy strategy) {
        this.perKmRedBelow = perKmRedBelow;
        this.perKmGreenFrom = perKmGreenFrom;
        this.perHourRedBelow = perHourRedBelow;
        this.perHourGreenFrom = perHourGreenFrom;
        this.ratingRedBelow = ratingRedBelow;
        this.ratingGreenFrom = ratingGreenFrom;
        this.perKmEnabled = perKmEnabled;
        this.perHourEnabled = perHourEnabled;
        this.ratingEnabled = ratingEnabled;
        this.strategy = strategy == null ? ClassificationStrategy.WORST_ENABLED : strategy;
    }

    public static ThresholdConfig defaults() {
        return new ThresholdConfig(1.40, 1.80, 30.00, 40.00, 4.30, 4.90,
                true, true, true, ClassificationStrategy.WORST_ENABLED);
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        validatePair("ganho por km", perKmRedBelow, perKmGreenFrom, errors);
        validatePair("ganho por hora", perHourRedBelow, perHourGreenFrom, errors);
        validatePair("nota do passageiro", ratingRedBelow, ratingGreenFrom, errors);
        if (ratingRedBelow > 5 || ratingGreenFrom > 5) {
            errors.add("nota do passageiro: os limites devem estar entre 0 e 5.");
        }
        return errors;
    }

    private static void validatePair(String name, double red, double green, List<String> errors) {
        if (!Double.isFinite(red) || !Double.isFinite(green) || red < 0 || green < 0) {
            errors.add(name + ": informe limites numéricos não negativos.");
        } else if (green < red) {
            errors.add(name + ": o limite verde deve ser maior ou igual ao limite vermelho.");
        }
    }

    public boolean isValid() { return validationErrors().isEmpty(); }
    public double perKmRedBelow() { return perKmRedBelow; }
    public double perKmGreenFrom() { return perKmGreenFrom; }
    public double perHourRedBelow() { return perHourRedBelow; }
    public double perHourGreenFrom() { return perHourGreenFrom; }
    public double ratingRedBelow() { return ratingRedBelow; }
    public double ratingGreenFrom() { return ratingGreenFrom; }
    public boolean perKmEnabled() { return perKmEnabled; }
    public boolean perHourEnabled() { return perHourEnabled; }
    public boolean ratingEnabled() { return ratingEnabled; }
    public ClassificationStrategy strategy() { return strategy; }
}
