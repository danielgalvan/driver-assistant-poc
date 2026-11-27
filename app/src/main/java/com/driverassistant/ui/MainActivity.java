package com.driverassistant.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AppCompatActivity;

import com.driverassistant.R;
import com.driverassistant.service.UberAccessibilityService;
import com.driverassistant.calculator.ClassificationService;
import com.driverassistant.calculator.ProfitabilityCalculator;
import com.driverassistant.config.ConfigRepository;
import com.driverassistant.domain.ClassificationResult;
import com.driverassistant.domain.Indicator;
import com.driverassistant.domain.IndicatorResult;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.Signal;
import com.driverassistant.domain.TripOffer;
import com.driverassistant.lifecycle.MonitoringActivationPolicy;
import com.driverassistant.util.DiagnosticLogStore;
import com.driverassistant.util.DiagnosticCaptureStore;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final ExecutorService CAPTURE_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConfigRepository config;
    private DiagnosticCaptureStore captureStore;
    private TextView accessibilityStatus;
    private TextView permissionStatus;
    private TextView monitoringStatus;
    private MaterialButton toggleMonitoringButton;
    private TextView uberStatus;
    private TextView uberSeenStatus;
    private TextView lastUberEventStatus;
    private TextView activeOfferStatus;
    private TextView overlayStatus;
    private TextView offerCount;
    private TextView incompleteOfferCount;
    private TextView lastOffer;
    private TextView recentEvents;
    private TextView captureStatus;
    private TextView captureModesStatus;
    private TextView captureModeWarning;
    private TextView captureDisclosure;
    private MaterialButton captureStartButton;
    private MaterialButton captureStopButton;
    private MaterialButton captureExportButton;
    private MaterialButton captureLimitButton;
    private boolean captureExportInProgress;
    private MaterialCardView simulationCard;
    private LinearLayout simulationContent;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshScreen();
            handler.postDelayed(this, 1_500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = new ConfigRepository(this);
        captureStore = DiagnosticCaptureStore.get(this);

        accessibilityStatus = findViewById(R.id.accessibility_status);
        permissionStatus = findViewById(R.id.permission_status);
        monitoringStatus = findViewById(R.id.monitoring_status);
        toggleMonitoringButton = findViewById(R.id.toggle_monitoring_button);
        uberStatus = findViewById(R.id.uber_status);
        uberSeenStatus = findViewById(R.id.uber_seen_status);
        lastUberEventStatus = findViewById(R.id.last_uber_event_status);
        activeOfferStatus = findViewById(R.id.active_offer_status);
        overlayStatus = findViewById(R.id.overlay_status);
        offerCount = findViewById(R.id.offer_count);
        incompleteOfferCount = findViewById(R.id.incomplete_offer_count);
        lastOffer = findViewById(R.id.last_offer);
        recentEvents = findViewById(R.id.recent_events);
        captureStatus = findViewById(R.id.capture_status);
        captureModesStatus = findViewById(R.id.capture_modes_status);
        captureModeWarning = findViewById(R.id.capture_mode_warning);
        captureDisclosure = findViewById(R.id.capture_disclosure);
        captureStartButton = findViewById(R.id.capture_start_button);
        captureStopButton = findViewById(R.id.capture_stop_button);
        captureExportButton = findViewById(R.id.capture_export_button);
        captureLimitButton = findViewById(R.id.capture_limit_button);
        simulationCard = findViewById(R.id.simulation_card);
        simulationContent = findViewById(R.id.simulation_content);

        findViewById(R.id.open_accessibility_button).setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        toggleMonitoringButton.setOnClickListener(view -> toggleMonitoring());
        findViewById(R.id.settings_button).setOnClickListener(view ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.simulate_button).setOnClickListener(view -> runSimulation());
        findViewById(R.id.test_overlay_button).setOnClickListener(view -> chooseOverlayScenario());
        findViewById(R.id.export_button).setOnClickListener(view -> exportLogs());
        findViewById(R.id.clear_diagnostics_button).setOnClickListener(view -> clearDiagnostics());
        captureStartButton.setOnClickListener(view -> startCapture());
        captureStopButton.setOnClickListener(view -> stopCapture());
        captureExportButton.setOnClickListener(view -> exportCapture());
        captureLimitButton.setOnClickListener(view -> chooseCaptureLimit());
        refreshCaptureStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void refreshScreen() {
        boolean enabledInSettings = isAccessibilityServiceEnabled();
        boolean serviceConnected = UberAccessibilityService.isConnected();
        boolean monitoring = config.monitoringEnabled();

        accessibilityStatus.setText(getString(R.string.status_accessibility,
                serviceConnected ? getString(R.string.yes) : getString(R.string.no)));
        permissionStatus.setText(getString(R.string.permission_status,
                enabledInSettings ? getString(R.string.enabled) : getString(R.string.disabled)));
        String monitoringLabel = monitoring
                ? serviceConnected ? getString(R.string.enabled) : getString(R.string.monitoring_not_operational)
                : getString(R.string.disabled);
        monitoringStatus.setText(getString(R.string.status_monitoring, monitoringLabel));
        monitoringStatus.setTextColor(getColor(monitoring
                ? serviceConnected ? R.color.signal_green : R.color.signal_red
                : R.color.brand_primary_dark));
        monitoringStatus.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        int monitoringButtonLabel = monitoring
                ? serviceConnected ? R.string.monitoring_stop_button : R.string.monitoring_stop_button_disconnected
                : R.string.monitoring_start_button;
        toggleMonitoringButton.setText(monitoringButtonLabel);
        toggleMonitoringButton.setContentDescription(getString(monitoring
                ? monitoringButtonLabel : R.string.monitoring_start_button));
        toggleMonitoringButton.setBackgroundTintList(ColorStateList.valueOf(getColor(
                monitoring ? R.color.signal_red : R.color.action_green)));
        toggleMonitoringButton.setTextColor(getColor(android.R.color.white));
        uberSeenStatus.setText(getString(R.string.uber_seen_before,
                DiagnosticLogStore.uberDetected(this) ? getString(R.string.yes) : getString(R.string.no)));
        uberStatus.setText(getString(R.string.uber_foreground,
                UberAccessibilityService.isUberForeground() ? getString(R.string.yes) : getString(R.string.no)));
        String lastUberEventValue = DiagnosticLogStore.lastUberEvent(this);
        lastUberEventStatus.setText(getString(R.string.last_uber_event,
                lastUberEventValue == null ? getString(R.string.none_yet) : lastUberEventValue));
        activeOfferStatus.setText(getString(R.string.active_offer_status,
                UberAccessibilityService.hasActiveOffer() ? getString(R.string.yes) : getString(R.string.no)));
        overlayStatus.setText(getString(R.string.status_overlay,
                UberAccessibilityService.isOverlayVisible() ? getString(R.string.enabled) : getString(R.string.disabled),
                config.overlayEnabled() ? getString(R.string.enabled) : getString(R.string.disabled)));
        offerCount.setText(getString(R.string.detected_offers, DiagnosticLogStore.offerCount(this)));
        incompleteOfferCount.setText(getString(R.string.incomplete_offers, DiagnosticLogStore.incompleteOfferCount(this)));
        String summary = DiagnosticLogStore.lastOffer(this);
        lastOffer.setText(getString(R.string.last_offer, summary == null ? getString(R.string.none_yet) : summary));

        List<String> events = DiagnosticLogStore.events(this);
        StringBuilder visibleEvents = new StringBuilder(getString(R.string.recent_events));
        int count = Math.min(5, events.size());
        for (int index = 0; index < count; index++) visibleEvents.append("\n").append(events.get(index));
        if (count == 0) visibleEvents.append("\n—");
        recentEvents.setText(visibleEvents.toString());
        refreshCaptureStatus();
    }

    private void refreshCaptureStatus() {
        DiagnosticCaptureStore.Status status = captureStore.status();
        String reason = status.stopReason() == null ? "—" : status.stopReason();
        String eventCount = getResources().getQuantityString(R.plurals.capture_event_count,
                (int) status.eventCount(), status.eventCount());
        captureStatus.setText(getString(status.active() ? R.string.capture_status_active : R.string.capture_status_inactive,
                eventCount, status.usedMegabytes(), status.limitMegabytes(),
                status.active() ? getString(R.string.capture_running) : reason));
        boolean monitoring = config.monitoringEnabled();
        captureModesStatus.setText(getString(R.string.capture_modes_status,
                status.active() ? getString(R.string.enabled) : getString(R.string.disabled),
                monitoring ? getString(R.string.enabled) : getString(R.string.disabled),
                config.overlayEnabled() ? getString(R.string.enabled) : getString(R.string.disabled)));
        captureModesStatus.setTextColor(getColor(monitoring ? R.color.signal_green : R.color.signal_red));
        captureModeWarning.setVisibility(status.active() && !monitoring ? View.VISIBLE : View.GONE);
        captureDisclosure.setText(getString(R.string.capture_disclosure, status.configuredLimitMb()));
        captureLimitButton.setText(getString(R.string.capture_limit, status.configuredLimitMb()));
        captureStartButton.setEnabled(!status.active());
        captureStopButton.setEnabled(status.active());
        captureExportButton.setEnabled(!status.active() && status.bytes() > 0 && !captureExportInProgress);
    }

    private void startCapture() {
        if (!ensureAccessibilityServiceReady()) return;
        if (!config.monitoringEnabled()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.capture_title)
                    .setMessage(R.string.capture_monitoring_off_dialog)
                    .setPositiveButton(R.string.capture_start_anyway, (dialog, which) -> startCaptureNow())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        startCaptureNow();
    }

    private void startCaptureNow() {
        if (captureStore.startCapture()) {
            captureStore.record("MODE_STATE", DiagnosticCaptureStore.object(
                    "reason", "capture_started",
                    "accessibility_service_connected", UberAccessibilityService.isConnected(),
                    "monitoring_enabled", config.monitoringEnabled(),
                    "overlay_configured", config.overlayEnabled()));
            Toast.makeText(this, R.string.capture_started, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.capture_start_failed, Toast.LENGTH_LONG).show();
        }
        refreshCaptureStatus();
    }

    private void stopCapture() {
        captureStore.stopCapture("manual_from_ui");
        Toast.makeText(this, R.string.capture_stopped, Toast.LENGTH_SHORT).show();
        refreshCaptureStatus();
    }

    private void chooseCaptureLimit() {
        String[] choices = {"10 MB", "25 MB", "50 MB", "100 MB"};
        int current = switch (captureStore.storageLimitMb()) {
            case 10 -> 0;
            case 25 -> 1;
            case 100 -> 3;
            default -> 2;
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_limit_title)
                .setSingleChoiceItems(choices, current, (dialog, which) -> {
                    int[] limits = {10, 25, 50, 100};
                    captureStore.setStorageLimitMb(limits[which]);
                    refreshCaptureStatus();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exportCapture() {
        if (captureStore.isActive()) {
            Toast.makeText(this, R.string.capture_export_stop_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (captureExportInProgress) return;
        captureExportInProgress = true;
        refreshCaptureStatus();
        CAPTURE_EXPORT_EXECUTOR.execute(() -> {
            Uri uri = null;
            try {
                String name = "driver-assistant-diagnostic-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                        .format(new Date()) + ".zip";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Driver Assistant");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Destino de download indisponível");
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IllegalStateException("Arquivo de exportação indisponível");
                    captureStore.exportZip(output);
                }
                ContentValues published = new ContentValues();
                published.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, published, null, null);
                runOnUiThread(() -> {
                    captureExportInProgress = false;
                    refreshCaptureStatus();
                    Toast.makeText(this, R.string.capture_export_saved, Toast.LENGTH_LONG).show();
                });
            } catch (Exception failure) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> {
                    captureExportInProgress = false;
                    refreshCaptureStatus();
                    Toast.makeText(this, R.string.capture_export_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        if (accessibilityEnabled != 1) return false;
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;

        ComponentName target = new ComponentName(this, UberAccessibilityService.class);
        String expected = target.flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String component = splitter.next();
            if (expected.equalsIgnoreCase(component)
                    || component.toLowerCase(Locale.ROOT).contains(UberAccessibilityService.class.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void toggleMonitoring() {
        boolean enabled = !config.monitoringEnabled();
        if (enabled && !ensureAccessibilityServiceReady()) return;
        config.setMonitoringEnabled(enabled);
        UberAccessibilityService.updateMonitoring(enabled);
        refreshScreen();
    }

    private boolean ensureAccessibilityServiceReady() {
        MonitoringActivationPolicy.BlockReason reason = MonitoringActivationPolicy.evaluate(
                isAccessibilityServiceEnabled(), UberAccessibilityService.isConnected());
        if (reason == MonitoringActivationPolicy.BlockReason.NONE) return true;

        int message = reason == MonitoringActivationPolicy.BlockReason.SERVICE_NOT_ENABLED
                ? R.string.accessibility_disabled_message
                : R.string.accessibility_service_not_connected_message;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.accessibility_required_title)
                .setMessage(message)
                .setPositiveButton(R.string.open_accessibility,
                        (dialog, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return false;
    }

    private void runSimulation() {
        var thresholds = config.loadThresholds();
        double amount = Math.max(thresholds.perKmGreenFrom() * 6.0,
                thresholds.perHourGreenFrom() * 0.5);
        TripOffer sample = new TripOffer(amount, "Exemplo simulado", 4.70,
                1.50, 8.0, 4.50, 22.0, null, null);
        RideMetrics metrics = new ProfitabilityCalculator().calculate(sample);
        ClassificationResult classification = new ClassificationService().classify(
                metrics, sample.passengerRating(), thresholds);

        simulationContent.removeAllViews();
        addOfferAmount(money(sample.offeredAmount()));
        addIndicatorRow(getString(R.string.offer_per_km), classification.indicators().get(Indicator.EARNINGS_PER_KM));
        addIndicatorRow(getString(R.string.offer_per_hour), classification.indicators().get(Indicator.EARNINGS_PER_HOUR));
        addIndicatorRow(getString(R.string.offer_rating), classification.indicators().get(Indicator.PASSENGER_RATING));
        addMetricRow(getString(R.string.offer_total_distance), distance(metrics.totalDistanceKm()));
        addMetricRow(getString(R.string.offer_total_time), duration(metrics.totalDurationMinutes()));
        if (classification.individualOnly()) {
            String label = getString(R.string.individual_only);
            if (classification.incomplete()) label += " · " + getString(R.string.incomplete);
            addOverallRow(label, getString(R.string.unknown), Signal.UNKNOWN);
        } else {
            String overall = classification.incomplete() ? getString(R.string.incomplete)
                    : signalName(classification.overallSignal());
            addOverallRow(getString(R.string.offer_general), overall,
                    classification.incomplete() ? Signal.UNKNOWN : classification.overallSignal());
        }
        simulationCard.setVisibility(View.VISIBLE);
        DiagnosticLogStore.record(this, "DA_CALCULATOR", "SIMULATION_RUN", "source=button");
    }

    private void addOfferAmount(String amount) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(roundedBackground(getColor(R.color.brand_secondary), getColor(R.color.brand_secondary), 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        simulationContent.addView(card, cardParams);

        TextView label = new TextView(this);
        label.setText(getString(R.string.offer_amount));
        label.setTextColor(getColor(R.color.brand_primary_dark));
        label.setTextSize(12);
        card.addView(label);

        TextView value = new TextView(this);
        value.setText(amount);
        value.setTextColor(getColor(R.color.brand_primary_dark));
        value.setTextSize(22);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(value);
    }

    private void addIndicatorRow(String label, IndicatorResult item) {
        boolean enabled = item != null && item.enabled();
        String value = !enabled ? getString(R.string.disabled)
                : item.value() == null ? getString(R.string.unknown)
                : item.indicator() == Indicator.PASSENGER_RATING ? decimal(item.value()) : money(item.value());
        Signal signal = enabled ? item.signal() : Signal.UNKNOWN;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundedBackground(getColor(R.color.surface_soft), getColor(R.color.surface_border), 12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(7);
        simulationContent.addView(row, rowParams);

        LinearLayout valueLine = new LinearLayout(this);
        valueLine.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(valueLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColor(R.color.ink_secondary));
        title.setTextSize(13);
        valueLine.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView amount = new TextView(this);
        amount.setText(value);
        amount.setTextColor(getColor(R.color.ink));
        amount.setTextSize(15);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueLine.addView(amount);

        TextView status = statusChip(enabled ? signalName(signal) : getString(R.string.disabled), signal);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(7);
        statusParams.gravity = Gravity.END;
        row.addView(status, statusParams);
    }

    private void addMetricRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(9), dp(2), dp(9));
        simulationContent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColor(R.color.ink_secondary));
        title.setTextSize(13);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView amount = new TextView(this);
        amount.setText(value);
        amount.setTextColor(getColor(R.color.ink));
        amount.setTextSize(13);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(amount);
    }

    private void addOverallRow(String title, String result, Signal signal) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setBackground(roundedBackground(signalSoftColor(signal), signalSoftColor(signal), 12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(7);
        simulationContent.addView(row, rowParams);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(getColor(R.color.ink));
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(statusChip(result, signal));
    }

    private TextView statusChip(String text, Signal signal) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(signalColor(signal));
        chip.setTextSize(12);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setBackground(roundedBackground(signalSoftColor(signal), signalSoftColor(signal), 24));
        return chip;
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        if (fillColor != strokeColor) background.setStroke(dp(1), strokeColor);
        return background;
    }

    private int signalColor(Signal signal) {
        return getColor(switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> R.color.signal_red;
            case YELLOW -> R.color.signal_yellow;
            case GREEN -> R.color.signal_green;
            case UNKNOWN -> R.color.signal_unknown;
        });
    }

    private int signalSoftColor(Signal signal) {
        return getColor(switch (signal == null ? Signal.UNKNOWN : signal) {
            case RED -> R.color.signal_red_soft;
            case YELLOW -> R.color.signal_yellow_soft;
            case GREEN -> R.color.signal_green_soft;
            case UNKNOWN -> R.color.signal_unknown_soft;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String signalName(Signal signal) {
        return switch (signal) {
            case RED -> "Vermelho";
            case YELLOW -> "Amarelo";
            case GREEN -> "Verde";
            case UNKNOWN -> getString(R.string.unknown);
        };
    }

    private void exportLogs() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Driver Assistant — diagnóstico");
        send.putExtra(Intent.EXTRA_TEXT, DiagnosticLogStore.exportText(this));
        startActivity(Intent.createChooser(send, getString(R.string.logs_shared)));
    }

    private void chooseOverlayScenario() {
        String[] labels = getResources().getStringArray(R.array.overlay_simulation_labels);
        String[] values = getResources().getStringArray(R.array.overlay_simulation_values);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_simulation_title)
                .setItems(labels, (dialog, which) -> {
                    if (!UberAccessibilityService.previewOverlayScenario(values[which])) {
                        Toast.makeText(this, R.string.simulation_requires_service, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearDiagnostics() {
        if (captureStore.isActive()) {
            Toast.makeText(this, R.string.capture_clear_active, Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_diagnostics)
                .setMessage(R.string.confirm_clear_diagnostics)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    captureStore.clearCapture(() -> {
                        DiagnosticLogStore.clear(this);
                        refreshScreen();
                        Toast.makeText(this, R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show();
                    });
                })
                .show();
    }

    private String money(Double value) {
        return value == null ? getString(R.string.unknown)
                : String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", value);
    }

    private String decimal(Double value) {
        return value == null ? getString(R.string.unknown)
                : String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value);
    }

    private String distance(Double value) {
        return value == null ? getString(R.string.unknown)
                : String.format(Locale.forLanguageTag("pt-BR"), "%.1f km", value);
    }

    private String duration(Double value) {
        return value == null ? getString(R.string.unknown)
                : String.format(Locale.forLanguageTag("pt-BR"), "%.0f min", value);
    }
}
