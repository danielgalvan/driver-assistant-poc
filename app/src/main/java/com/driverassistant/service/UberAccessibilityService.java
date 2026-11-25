package com.driverassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.driverassistant.accessibility.AccessibilityTreeReader;
import com.driverassistant.calculator.ClassificationService;
import com.driverassistant.calculator.ProfitabilityCalculator;
import com.driverassistant.config.ConfigRepository;
import com.driverassistant.domain.ClassificationResult;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.TripOffer;
import com.driverassistant.overlay.OverlayController;
import com.driverassistant.parser.OfferParser;
import com.driverassistant.util.DiagnosticLogStore;
import com.driverassistant.util.EventDeduplicator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Accessibility service limitado ao package do Uber Driver. */
public final class UberAccessibilityService extends AccessibilityService {
    public static final String UBER_PACKAGE = "com.ubercab.driver";
    private static volatile boolean connected;
    private static volatile UberAccessibilityService activeService;

    private final EventDeduplicator eventDeduplicator = new EventDeduplicator(1_200);
    private final OfferParser parser = new OfferParser();
    private final AccessibilityTreeReader treeReader = new AccessibilityTreeReader();
    private final ProfitabilityCalculator calculator = new ProfitabilityCalculator();
    private final ClassificationService classificationService = new ClassificationService();
    private ConfigRepository config;
    private OverlayController overlay;
    private String activeOfferFingerprint;

    public static boolean isConnected() { return connected; }

    public static void updateMonitoring(boolean enabled) {
        UberAccessibilityService service = activeService;
        if (!enabled && service != null && service.overlay != null) service.overlay.hide();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        connected = true;
        config = new ConfigRepository(this);
        overlay = new OverlayController(this);
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SERVICE_CONNECTED", "target=uber_driver");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !UBER_PACKAGE.contentEquals(event.getPackageName())) return;

        if (config == null) config = new ConfigRepository(this);
        if (!config.monitoringEnabled()) {
            hideOverlay();
            return;
        }

        DiagnosticLogStore.setUberDetected(this, true);
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "UBER_EVENT",
                "type=" + event.getEventType());

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                onNoOffer("tree_unavailable");
                return;
            }
            if (root.getPackageName() == null || !UBER_PACKAGE.contentEquals(root.getPackageName())) {
                onNoOffer("foreign_root");
                return;
            }

            AccessibilityNodeInfo treeRoot = root;
            root = null;
            List<String> text = treeReader.read(treeRoot);
            if (text.isEmpty()) {
                onNoOffer("empty_tree");
                return;
            }

            String fingerprint = fingerprint(String.join("\n", text));
            if (!eventDeduplicator.shouldProcess(fingerprint, SystemClock.uptimeMillis())) return;

            Optional<TripOffer> parsed = parser.parse(text);
            if (parsed.isEmpty()) {
                DiagnosticLogStore.record(this, "DA_PARSER", "NO_OFFER_CANDIDATE", "nodes=" + text.size());
                onNoOffer("no_candidate");
                return;
            }

            TripOffer offer = parsed.get();
            RideMetrics metrics = calculator.calculate(offer);
            ClassificationResult classification = classificationService.classify(
                    metrics, offer.passengerRating(), config.loadThresholds());

            if (!fingerprint.equals(activeOfferFingerprint)) {
                activeOfferFingerprint = fingerprint;
                DiagnosticLogStore.recordOffer(this, offer, metrics);
            }
            DiagnosticLogStore.record(this, "DA_CALCULATOR", "CLASSIFIED",
                    "overall=" + classification.overallSignal() + "; incomplete=" + classification.incomplete());
            if (overlay == null) overlay = new OverlayController(this);
            overlay.show(offer, metrics, classification);
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            onNoOffer("processing_error");
        } finally {
            if (root != null) root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        hideOverlay();
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SERVICE_INTERRUPTED", null);
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        connected = false;
        if (activeService == this) activeService = null;
        DiagnosticLogStore.setUberDetected(this, false);
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SERVICE_DISCONNECTED", null);
        super.onDestroy();
    }

    private void onNoOffer(String reason) {
        activeOfferFingerprint = null;
        hideOverlay();
        DiagnosticLogStore.record(this, "DA_OFFER", "OFFER_NOT_AVAILABLE", "reason=" + reason);
    }

    private void hideOverlay() {
        if (overlay != null) overlay.hide();
    }

    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 é obrigatório em Android; o fallback ainda não registra nem expõe o texto.
            return Integer.toHexString(text.hashCode());
        }
    }
}
