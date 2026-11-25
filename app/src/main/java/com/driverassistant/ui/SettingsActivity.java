package com.driverassistant.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.driverassistant.R;
import com.driverassistant.config.ConfigRepository;
import com.driverassistant.config.ThresholdConfig;
import com.driverassistant.domain.ClassificationStrategy;
import com.driverassistant.parser.LocalizedNumberParser;
import com.driverassistant.util.DiagnosticLogStore;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

public final class SettingsActivity extends AppCompatActivity {
    private static final String[] POSITIONS = {"TOP_RIGHT", "TOP_LEFT", "BOTTOM_RIGHT", "BOTTOM_LEFT"};
    private ConfigRepository config;
    private TextInputEditText perKmRed;
    private TextInputEditText perKmGreen;
    private TextInputEditText perHourRed;
    private TextInputEditText perHourGreen;
    private TextInputEditText ratingRed;
    private TextInputEditText ratingGreen;
    private TextInputEditText overlayDuration;
    private MaterialCheckBox perKmEnabled;
    private MaterialCheckBox perHourEnabled;
    private MaterialCheckBox ratingEnabled;
    private MaterialCheckBox overlayEnabled;
    private MaterialCheckBox showCategory;
    private MaterialCheckBox showPerKm;
    private MaterialCheckBox showPerHour;
    private MaterialCheckBox showRating;
    private MaterialCheckBox showPickupDistance;
    private MaterialCheckBox showTripDistance;
    private MaterialCheckBox showTotalDistance;
    private MaterialCheckBox showTotalTime;
    private MaterialCheckBox showOverall;
    private Spinner strategySpinner;
    private Spinner positionSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        config = new ConfigRepository(this);
        bindViews();
        loadValues();
        findViewById(R.id.save_settings).setOnClickListener(view -> save());
    }

    private void bindViews() {
        perKmRed = findViewById(R.id.per_km_red);
        perKmGreen = findViewById(R.id.per_km_green);
        perHourRed = findViewById(R.id.per_hour_red);
        perHourGreen = findViewById(R.id.per_hour_green);
        ratingRed = findViewById(R.id.rating_red);
        ratingGreen = findViewById(R.id.rating_green);
        overlayDuration = findViewById(R.id.overlay_duration);
        perKmEnabled = findViewById(R.id.enable_per_km);
        perHourEnabled = findViewById(R.id.enable_per_hour);
        ratingEnabled = findViewById(R.id.enable_rating);
        overlayEnabled = findViewById(R.id.overlay_enabled);
        showCategory = findViewById(R.id.show_category);
        showPerKm = findViewById(R.id.show_per_km);
        showPerHour = findViewById(R.id.show_per_hour);
        showRating = findViewById(R.id.show_rating);
        showPickupDistance = findViewById(R.id.show_pickup_distance);
        showTripDistance = findViewById(R.id.show_trip_distance);
        showTotalDistance = findViewById(R.id.show_total_distance);
        showTotalTime = findViewById(R.id.show_total_time);
        showOverall = findViewById(R.id.show_overall);
        strategySpinner = findViewById(R.id.classification_strategy);
        positionSpinner = findViewById(R.id.overlay_position);

        ArrayAdapter<String> strategyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.classification_strategy_labels));
        strategyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        strategySpinner.setAdapter(strategyAdapter);
        ArrayAdapter<String> positionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.overlay_position_labels));
        positionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        positionSpinner.setAdapter(positionAdapter);
    }

    private void loadValues() {
        ThresholdConfig thresholds = config.loadThresholds();
        perKmRed.setText(decimal(thresholds.perKmRedBelow()));
        perKmGreen.setText(decimal(thresholds.perKmGreenFrom()));
        perHourRed.setText(decimal(thresholds.perHourRedBelow()));
        perHourGreen.setText(decimal(thresholds.perHourGreenFrom()));
        ratingRed.setText(decimal(thresholds.ratingRedBelow()));
        ratingGreen.setText(decimal(thresholds.ratingGreenFrom()));
        perKmEnabled.setChecked(thresholds.perKmEnabled());
        perHourEnabled.setChecked(thresholds.perHourEnabled());
        ratingEnabled.setChecked(thresholds.ratingEnabled());
        strategySpinner.setSelection(thresholds.strategy().ordinal());

        overlayEnabled.setChecked(config.overlayEnabled());
        overlayDuration.setText(Integer.toString(config.overlayDurationSeconds()));
        positionSpinner.setSelection(positionIndex(config.overlayPosition()));
        showCategory.setChecked(config.showCategory());
        showPerKm.setChecked(config.showPerKm());
        showPerHour.setChecked(config.showPerHour());
        showRating.setChecked(config.showRating());
        showPickupDistance.setChecked(config.showPickupDistance());
        showTripDistance.setChecked(config.showTripDistance());
        showTotalDistance.setChecked(config.showTotalDistance());
        showTotalTime.setChecked(config.showTotalTime());
        showOverall.setChecked(config.showOverall());
    }

    private void save() {
        Double kmRed = number(perKmRed);
        Double kmGreen = number(perKmGreen);
        Double hourRed = number(perHourRed);
        Double hourGreen = number(perHourGreen);
        Double ratingMin = number(ratingRed);
        Double ratingMax = number(ratingGreen);
        Integer seconds = integer(overlayDuration);
        if (kmRed == null || kmGreen == null || hourRed == null || hourGreen == null
                || ratingMin == null || ratingMax == null || seconds == null) {
            showInvalid();
            return;
        }

        ClassificationStrategy[] strategies = ClassificationStrategy.values();
        ClassificationStrategy selectedStrategy = strategies[Math.max(0,
                Math.min(strategySpinner.getSelectedItemPosition(), strategies.length - 1))];
        ThresholdConfig thresholds = new ThresholdConfig(kmRed, kmGreen, hourRed, hourGreen,
                ratingMin, ratingMax, perKmEnabled.isChecked(), perHourEnabled.isChecked(),
                ratingEnabled.isChecked(), selectedStrategy);
        if (ratingMin > 5 || ratingMax > 5 || seconds < 1 || seconds > 120 || !thresholds.isValid()) {
            showInvalid();
            return;
        }
        if (!config.saveThresholds(thresholds)) {
            showInvalid();
            return;
        }

        String position = POSITIONS[Math.max(0, Math.min(positionSpinner.getSelectedItemPosition(), POSITIONS.length - 1))];
        boolean overlaySaved = config.saveOverlaySettings(overlayEnabled.isChecked(), seconds, position,
                showCategory.isChecked(), showRating.isChecked(), showPickupDistance.isChecked(),
                showTripDistance.isChecked(), showTotalDistance.isChecked(), showTotalTime.isChecked(),
                showPerKm.isChecked(), showPerHour.isChecked(), showOverall.isChecked());
        if (!overlaySaved) {
            showInvalid();
            return;
        }

        DiagnosticLogStore.record(this, "DA_CONFIG", "CONFIG_SAVED", "thresholds_and_overlay=true");
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showInvalid() {
        Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show();
    }

    private static Double number(TextInputEditText input) {
        return LocalizedNumberParser.parse(input.getText() == null ? null : input.getText().toString());
    }

    private static Integer integer(TextInputEditText input) {
        try {
            return Integer.parseInt(input.getText() == null ? "" : input.getText().toString().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String decimal(double value) {
        return java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("pt-BR"))
                .format(value);
    }

    private static int positionIndex(String position) {
        for (int index = 0; index < POSITIONS.length; index++) {
            if (POSITIONS[index].equals(position)) return index;
        }
        return 0;
    }
}
