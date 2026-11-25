package com.driverassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import com.driverassistant.lifecycle.OfferLifecycleEvent;
import com.driverassistant.lifecycle.OfferLifecycleMachine;
import com.driverassistant.lifecycle.OfferLifecycleState;
import com.driverassistant.overlay.OverlayController;
import com.driverassistant.parser.AccessibilityNode;
import com.driverassistant.parser.OfferParseResult;
import com.driverassistant.parser.OfferParser;
import com.driverassistant.util.DiagnosticLogStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Accessibility service restricted by manifest configuration to the Uber Driver package. */
public final class UberAccessibilityService extends AccessibilityService {
    public static final String UBER_PACKAGE = "com.ubercab.driver";
    private static final long FOREGROUND_POLL_MILLIS = 500;
    private static volatile boolean connected;
    private static volatile UberAccessibilityService activeService;
    private static long nextSimulationSession = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean snapshotDrainScheduled = new AtomicBoolean();
    private final AtomicReference<EventRequest> pendingSnapshot = new AtomicReference<>();
    private final Runnable foregroundWatch = new Runnable() {
        @Override public void run() {
            checkOfferWindow();
            if (activeOfferSnapshot != null && config != null && config.monitoringEnabled()) {
                mainHandler.postDelayed(this, FOREGROUND_POLL_MILLIS);
            }
        }
    };
    private final OfferParser parser = new OfferParser();
    private final AccessibilityTreeReader treeReader = new AccessibilityTreeReader();
    private final ProfitabilityCalculator calculator = new ProfitabilityCalculator();
    private final ClassificationService classificationService = new ClassificationService();
    private ConfigRepository config;
    private OverlayController overlay;
    private OfferLifecycleMachine lifecycle;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private volatile TripOffer activeOfferSnapshot;
    private volatile long simulationEpoch;
    private volatile boolean paused;
    private volatile boolean shuttingDown;
    private long simulationSessionId;

    private static final class EventRequest {
        final int eventType;
        final long startedAt;

        EventRequest(int eventType, long startedAt) {
            this.eventType = eventType;
            this.startedAt = startedAt;
        }
    }

    public static boolean isConnected() { return connected; }

    public static boolean isOverlayVisible() {
        UberAccessibilityService service = activeService;
        return service != null && service.overlay != null && service.overlay.isVisible();
    }

    public static boolean hasActiveOffer() {
        UberAccessibilityService service = activeService;
        return service != null && service.activeOfferSnapshot != null;
    }

    public static boolean isUberForeground() {
        UberAccessibilityService service = activeService;
        return service != null && service.isUberWindowActive();
    }

    public static void updateMonitoring(boolean enabled) {
        UberAccessibilityService service = activeService;
        if (service == null) return;
        if (!enabled) {
            service.disableLifecycle("monitoring_disabled");
            if (service.overlay != null) service.overlay.hide();
        }
    }

    public static void refreshOverlayConfiguration() {
        UberAccessibilityService service = activeService;
        if (service == null || service.overlay == null || service.config == null) return;
        service.overlay.hide();
        if (service.config.overlayEnabled()) service.restoreRealOffer();
    }

    /** Simulations use the same Android overlay but never touch real offer lifecycle counters. */
    public static boolean previewOverlayScenario(String scenario) {
        UberAccessibilityService service = activeService;
        if (service == null || service.overlay == null || scenario == null) return false;
        service.ensureInitialized();
        if (!service.config.monitoringEnabled() || service.activeOfferSnapshot != null) return false;
        service.runOverlayScenario(scenario);
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        paused = false;
        shuttingDown = false;
        activeService = this;
        connected = true;
        config = new ConfigRepository(this);
        overlay = new OverlayController(this);
        lifecycle = new OfferLifecycleMachine(config.offerDisappearanceToleranceMillis());
        analysisThread = new HandlerThread("driver-offer-analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        analysisHandler.post(() -> DiagnosticLogStore.record(this, "DA_ACCESSIBILITY",
                "SERVICE_CONNECTED", "target=uber_driver"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (paused || shuttingDown || event == null || event.getPackageName() == null
                || !UBER_PACKAGE.contentEquals(event.getPackageName())) return;
        ensureInitialized();
        long startedAt = SystemClock.elapsedRealtime();
        int eventType = event.getEventType();
        if (!config.monitoringEnabled()) {
            analysisHandler.post(() -> {
                DiagnosticLogStore.markUberEvent(this, Integer.toString(eventType));
                DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "UBER_EVENT", "type=" + eventType);
                disableLifecycleOnAnalysis("monitoring_disabled");
            });
            if (overlay != null) overlay.hide();
            return;
        }

        // Accessibility callbacks arrive on the service's main thread. Keep this path short:
        // reading the window tree can involve hundreds of Binder calls on some driver screens.
        pendingSnapshot.set(new EventRequest(eventType, startedAt));
        scheduleSnapshotDrain();
    }

    private void scheduleSnapshotDrain() {
        Handler handler = analysisHandler;
        if (handler == null || !snapshotDrainScheduled.compareAndSet(false, true)) return;
        handler.post(() -> {
            try {
                EventRequest request = pendingSnapshot.getAndSet(null);
                if (request != null) captureSnapshot(request);
            } finally {
                snapshotDrainScheduled.set(false);
                if (pendingSnapshot.get() != null) scheduleSnapshotDrain();
            }
        });
    }

    /** Runs on the dedicated analysis thread; intermediate events are coalesced to the latest. */
    private void captureSnapshot(EventRequest request) {
        if (paused || shuttingDown || !connected) return;
        int eventType = request.eventType;
        long startedAt = request.startedAt;
        long workerStartedAt = SystemClock.elapsedRealtime();
        DiagnosticLogStore.markUberEvent(this, Integer.toString(eventType));
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "UBER_EVENT", "type=" + eventType);
        if (!config.monitoringEnabled()) {
            disableLifecycleOnAnalysis("monitoring_disabled");
            hideOverlay();
            return;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                processMissing("tree_unavailable", startedAt);
                return;
            }
            if (root.getPackageName() == null || !UBER_PACKAGE.contentEquals(root.getPackageName())) {
                root.recycle();
                root = null;
                processMissing("foreign_root", startedAt);
                return;
            }

            long treeReadStartedAt = SystemClock.elapsedRealtime();
            List<AccessibilityNode> snapshot = treeReader.read(root);
            long treeReadMillis = SystemClock.elapsedRealtime() - treeReadStartedAt;
            root = null; // The reader recycles every visited node, including the root.
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SNAPSHOT_TIMING",
                    "queue_ms=" + Math.max(0, workerStartedAt - startedAt)
                            + "; tree_ms=" + treeReadMillis + "; nodes=" + snapshot.size());
            if (snapshot.isEmpty()) {
                processMissing("empty_tree", startedAt);
                return;
            }
            processSnapshot(List.copyOf(snapshot), eventType, startedAt);
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            processMissing("tree_read_error", startedAt);
        } finally {
            if (root != null) root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        paused = true;
        disableLifecycle("service_interrupted");
        hideOverlay();
        recordAsync("SERVICE_INTERRUPTED", null);
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        mainHandler.removeCallbacks(foregroundWatch);
        disableLifecycle("service_disconnected");
        hideOverlay();
        connected = false;
        if (activeService == this) activeService = null;
        recordAsync("SERVICE_DISCONNECTED", null);
        if (analysisThread != null) analysisThread.quitSafely();
        super.onDestroy();
    }

    private void ensureInitialized() {
        if (config == null) config = new ConfigRepository(this);
        if (lifecycle == null) lifecycle = new OfferLifecycleMachine(config.offerDisappearanceToleranceMillis());
        if (overlay == null) overlay = new OverlayController(this);
        if (analysisHandler == null) {
            analysisThread = new HandlerThread("driver-offer-analysis");
            analysisThread.start();
            analysisHandler = new Handler(analysisThread.getLooper());
        }
    }

    private void processSnapshot(List<AccessibilityNode> snapshot, int eventType, long startedAt) {
        if (paused || shuttingDown || !connected) return;
        if (!config.monitoringEnabled()) {
            disableLifecycleOnAnalysis("monitoring_disabled");
            DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
            return;
        }
        if (config.detailedDiagnosticsEnabled()) {
            DiagnosticLogStore.recordStructure(this, AccessibilityTreeReader.structureSummary(snapshot));
        }

        try {
            OfferParseResult result = parser.parseNodes(snapshot);
            DiagnosticLogStore.recordParserResult(this, result);
            if (!result.accepted()) {
                processMissing(result.rejectionReason(), startedAt);
                return;
            }

            OfferLifecycleEvent transition = lifecycle.observe(Optional.of(result.offer()), SystemClock.uptimeMillis());
            if (!transition.transitioned()) {
                DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
                return;
            }
            logTransition(transition);
            if (transition.state() == OfferLifecycleState.OFFER_DISAPPEARED) {
                activeOfferSnapshot = null;
                hideOverlay();
                mainHandler.removeCallbacks(foregroundWatch);
                DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
                return;
            }

            TripOffer offer = transition.offer();
            RideMetrics metrics = calculator.calculate(offer);
            ClassificationResult classification = classificationService.classify(
                    metrics, offer.passengerRating(), config.loadThresholds());
            activeOfferSnapshot = offer;
            if (transition.state() == OfferLifecycleState.OFFER_DETECTED) {
                DiagnosticLogStore.recordOffer(this, offer, metrics, classification.incomplete());
                startForegroundWatch();
            } else {
                DiagnosticLogStore.updateLastOffer(this, offer, metrics);
            }
            DiagnosticLogStore.record(this, "DA_CALCULATOR", "CLASSIFIED",
                    "overall=" + classification.overallSignal() + "; incomplete=" + classification.incomplete());
            if (overlay == null) overlay = new OverlayController(this);
            simulationEpoch++;
            overlay.show(offer, metrics, classification, transition.sessionId(), false);
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            processMissing("processing_error", startedAt);
            return;
        }
        DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
    }

    private void processMissing(String reason, long startedAt) {
        OfferLifecycleEvent event = lifecycle.observe(Optional.empty(), SystemClock.uptimeMillis());
        DiagnosticLogStore.record(this, "DA_OFFER", "OFFER_CANDIDATE_MISSING", "reason=" + safeReason(reason));
        if (event.transitioned()) {
            logTransition(event);
            if (event.state() == OfferLifecycleState.OFFER_DISAPPEARED) {
                activeOfferSnapshot = null;
                hideOverlay();
                mainHandler.removeCallbacks(foregroundWatch);
            }
        }
        DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
    }

    /** Called by the main-thread poll; all lifecycle mutation remains serial on analysisHandler. */
    private void checkOfferWindow() {
        if (activeOfferSnapshot == null || config == null || !config.monitoringEnabled() || analysisHandler == null) return;
        boolean uberForeground = isUberWindowActive();
        analysisHandler.post(() -> {
            if (lifecycle == null || lifecycle.activeOffer() == null || !config.monitoringEnabled()) return;
            if (uberForeground) {
                lifecycle.observe(Optional.of(lifecycle.activeOffer()), SystemClock.uptimeMillis());
            } else {
                processMissing("uber_left_foreground", SystemClock.elapsedRealtime());
            }
        });
    }

    private boolean isUberWindowActive() {
        if (!connected) return false;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            return root != null && root.getPackageName() != null
                    && UBER_PACKAGE.contentEquals(root.getPackageName());
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private void disableLifecycle(String reason) {
        simulationEpoch++;
        mainHandler.removeCallbacks(foregroundWatch);
        Handler handler = analysisHandler;
        if (handler != null) handler.post(() -> disableLifecycleOnAnalysis(reason));
        else activeOfferSnapshot = null;
    }

    private void disableLifecycleOnAnalysis(String reason) {
        if (lifecycle == null) {
            activeOfferSnapshot = null;
            return;
        }
        OfferLifecycleEvent event = lifecycle.disable();
        activeOfferSnapshot = null;
        if (event.transitioned()) {
            DiagnosticLogStore.record(this, "DA_OFFER", "OFFER_LIFECYCLE_RESET", "reason=" + reason);
            logTransition(event);
        }
    }

    private void startForegroundWatch() {
        mainHandler.removeCallbacks(foregroundWatch);
        mainHandler.postDelayed(foregroundWatch, FOREGROUND_POLL_MILLIS);
    }

    private void logTransition(OfferLifecycleEvent event) {
        if (event == null || !event.transitioned()) return;
        DiagnosticLogStore.record(this, "DA_OFFER", "LIFECYCLE_TRANSITION",
                "state=" + event.state() + "; session=" + event.sessionId() + "; reason=" + event.reason());
    }

    private void hideOverlay() { if (overlay != null) overlay.hide(); }

    private void recordAsync(String code, String detail) {
        Handler handler = analysisHandler;
        if (handler == null) return;
        handler.post(() -> DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", code, detail));
    }

    private void runOverlayScenario(String scenario) {
        ensureInitialized();
        String normalized = scenario.trim().toLowerCase(java.util.Locale.ROOT);
        var thresholds = config.loadThresholds();
        double distance = 6.0;
        double minutes = 30.0;
        double greenAmount = Math.max(thresholds.perKmGreenFrom() * distance,
                thresholds.perHourGreenFrom() * minutes / 60.0);
        Double rating = (thresholds.ratingRedBelow() + thresholds.ratingGreenFrom()) / 2.0;
        double amount = greenAmount;
        if ("red".equals(normalized)) {
            if (thresholds.ratingEnabled()) rating = Math.max(0, thresholds.ratingRedBelow() - 0.2);
            else if (thresholds.perKmEnabled()) amount = Math.max(0, thresholds.perKmRedBelow() * distance - 0.01);
            else if (thresholds.perHourEnabled()) amount = Math.max(0, thresholds.perHourRedBelow() * minutes / 60.0 - 0.01);
        }
        if ("green".equals(normalized)) rating = thresholds.ratingGreenFrom();
        if ("yellow".equals(normalized)) {
            if (thresholds.ratingEnabled() && thresholds.ratingRedBelow() < thresholds.ratingGreenFrom()) {
                rating = (thresholds.ratingRedBelow() + thresholds.ratingGreenFrom()) / 2.0;
            } else if (thresholds.perHourEnabled() && thresholds.perHourRedBelow() < thresholds.perHourGreenFrom()) {
                amount = (thresholds.perHourRedBelow() + thresholds.perHourGreenFrom()) / 2.0 * minutes / 60.0;
            } else if (thresholds.perKmEnabled() && thresholds.perKmRedBelow() < thresholds.perKmGreenFrom()) {
                amount = (thresholds.perKmRedBelow() + thresholds.perKmGreenFrom()) / 2.0 * distance;
            }
        }

        TripOffer offer;
        if ("incomplete".equals(normalized)) {
            offer = new TripOffer(amount, "UberX (simulado)", null, 1.5, 8.0, null, null, null, null);
        } else if ("no_rating".equals(normalized)) {
            offer = simulatedOffer(amount, null);
        } else {
            offer = simulatedOffer(amount, rating);
        }
        simulationSessionId = nextSimulationSession--;
        long epoch = ++simulationEpoch;
        showSimulation(offer, simulationSessionId);

        if ("updated".equals(normalized)) {
            long session = simulationSessionId;
            TripOffer updated = simulatedOffer(amount + 2.0, rating);
            mainHandler.postDelayed(() -> {
                if (simulationEpoch == epoch && config.monitoringEnabled()) showSimulation(updated, session);
            }, 1_000);
        } else if ("duplicate".equals(normalized)) {
            showSimulation(offer, simulationSessionId);
        } else if ("disappearing".equals(normalized)) {
            long session = simulationSessionId;
            mainHandler.postDelayed(() -> {
                if (simulationEpoch == epoch && simulationSessionId == session) overlay.hide();
            }, Math.max(1_000, config.offerDisappearanceToleranceMillis()));
        }
        DiagnosticLogStore.record(this, "DA_CALCULATOR", "SIMULATION_OVERLAY", "scenario=" + normalized);
    }

    private TripOffer simulatedOffer(Double amount, Double rating) {
        return new TripOffer(amount, "UberX (simulado)", rating, 1.5, 8.0, 4.5, 22.0, null, null);
    }

    private void showSimulation(TripOffer offer, long session) {
        RideMetrics metrics = calculator.calculate(offer);
        ClassificationResult classification = classificationService.classify(metrics, offer.passengerRating(), config.loadThresholds());
        overlay.show(offer, metrics, classification, session, true);
    }

    private void restoreRealOffer() {
        Handler handler = analysisHandler;
        if (handler == null) return;
        handler.post(() -> {
            TripOffer offer = lifecycle == null ? null : lifecycle.activeOffer();
            if (offer == null || !config.monitoringEnabled()) return;
            RideMetrics metrics = calculator.calculate(offer);
            ClassificationResult classification = classificationService.classify(
                    metrics, offer.passengerRating(), config.loadThresholds());
            overlay.show(offer, metrics, classification, lifecycle.sessionId(), false);
        });
    }

    private static String safeReason(String reason) {
        if (reason == null) return "unknown";
        return reason.replaceAll("[^a-zA-Z0-9_:-]", "_");
    }
}
