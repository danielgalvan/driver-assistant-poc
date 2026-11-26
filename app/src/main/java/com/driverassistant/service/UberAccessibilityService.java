package com.driverassistant.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
import com.driverassistant.util.DiagnosticCaptureStore;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reads accessible content only from Uber Driver; foreign events are reduced to metadata. */
public final class UberAccessibilityService extends AccessibilityService {
    public static final String UBER_PACKAGE = "com.ubercab.driver";
    private static final long FOREGROUND_POLL_MILLIS = 500;
    private static volatile boolean connected;
    private static volatile UberAccessibilityService activeService;
    private static long nextSimulationSession = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean snapshotDrainScheduled = new AtomicBoolean();
    private final AtomicReference<EventRequest> pendingSnapshot = new AtomicReference<>();
    private volatile long lastSnapshotRequestAt;
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
    private DiagnosticCaptureStore captureStore;
    private OverlayController overlay;
    private OfferLifecycleMachine lifecycle;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private volatile TripOffer activeOfferSnapshot;
    private volatile long simulationEpoch;
    private volatile boolean paused;
    private volatile boolean shuttingDown;
    private long simulationSessionId;
    private final ConcurrentHashMap<String, Integer> externalEventCounts = new ConcurrentHashMap<>();
    private volatile long lastExternalSummaryAt;
    private volatile long lastWindowStateAt;
    private volatile String lastExternalWindowFingerprint = "";
    private String lastCaptureSessionId;
    private String lastParserDiagnosticFingerprint;
    private long lastParserDiagnosticAt;
    private String lastLifecycleDiagnosticFingerprint;

    private static final class EventRequest {
        final int eventType;
        final long startedAt;
        final boolean foregroundPoll;
        final String sourcePackage;

        EventRequest(int eventType, long startedAt, boolean foregroundPoll, String sourcePackage) {
            this.eventType = eventType;
            this.startedAt = startedAt;
            this.foregroundPoll = foregroundPoll;
            this.sourcePackage = sourcePackage;
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
            if (service.overlay != null) service.overlay.hide("monitoring_disabled");
        }
    }

    public static void refreshOverlayConfiguration() {
        UberAccessibilityService service = activeService;
        if (service == null || service.overlay == null || service.config == null) return;
        service.overlay.hide("overlay_configuration_changed");
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
        captureStore = DiagnosticCaptureStore.get(this);
        overlay = new OverlayController(this);
        lifecycle = new OfferLifecycleMachine(config.offerDisappearanceToleranceMillis());
        analysisThread = new HandlerThread("driver-offer-analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        lastSnapshotRequestAt = SystemClock.elapsedRealtime();
        analysisHandler.post(() -> DiagnosticLogStore.record(this, "DA_ACCESSIBILITY",
                "SERVICE_CONNECTED", "target=uber_driver"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (paused || shuttingDown || event == null) return;
        ensureInitialized();
        long startedAt = SystemClock.elapsedRealtime();
        int eventType = event.getEventType();
        String eventPackage = event.getPackageName() == null ? "unknown" : event.getPackageName().toString();
        boolean uberEvent = UBER_PACKAGE.equals(eventPackage);
        if (captureStore.isActive()) {
            if (uberEvent) {
                captureStore.record("ACCESSIBILITY_EVENT", DiagnosticCaptureStore.object(
                        "event_type", eventType, "event_type_name", AccessibilityEvent.eventTypeToString(eventType),
                        "package", UBER_PACKAGE, "class", safeClass(event.getClassName()),
                        "window_id", event.getWindowId(), "content_change_types", event.getContentChangeTypes(),
                        "event_time_uptime_ms", event.getEventTime()));
            } else {
                recordExternalEvent(eventPackage, eventType, event.getWindowId());
            }
        }
        if (!uberEvent) {
            if (captureStore.isActive() && config != null && config.monitoringEnabled()) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastSnapshotRequestAt >= 500) {
                    requestSnapshot(eventType, startedAt, false, eventPackage);
                }
            }
            return;
        }
        if (!config.monitoringEnabled()) {
            if (captureStore.isActive()) requestSnapshot(eventType, startedAt, false, eventPackage);
            else analysisHandler.post(() -> {
                    DiagnosticLogStore.markUberEvent(this, Integer.toString(eventType));
                    DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "UBER_EVENT", "type=" + eventType);
                    disableLifecycleOnAnalysis("monitoring_disabled");
                });
            if (overlay != null) overlay.hide("monitoring_disabled");
            return;
        }

        // Accessibility callbacks arrive on the service's main thread. Keep this path short:
        // reading the window tree can involve hundreds of Binder calls on some driver screens.
        requestSnapshot(eventType, startedAt, false, eventPackage);
    }

    private void requestSnapshot(int eventType, long startedAt, boolean foregroundPoll, String sourcePackage) {
        lastSnapshotRequestAt = startedAt;
        EventRequest previous = pendingSnapshot.getAndSet(new EventRequest(eventType, startedAt, foregroundPoll, sourcePackage));
        if (previous != null && captureStore != null && captureStore.isActive()) {
            captureStore.recordSnapshotSkipped(previous.startedAt, "coalesced_by_newer_event");
        }
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
        String source = request.foregroundPoll ? "foreground_poll" : "accessibility_event";
        DiagnosticLogStore.markUberEvent(this, request.foregroundPoll ? source : Integer.toString(eventType));
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY",
                request.foregroundPoll ? "FOREGROUND_RECHECK" : "UBER_EVENT",
                request.foregroundPoll ? "source=" + source : "type=" + eventType);
        boolean captureActive = captureStore != null && captureStore.isActive();
        boolean monitoringEnabled = config.monitoringEnabled();
        if (!monitoringEnabled && !captureActive) {
            disableLifecycleOnAnalysis("monitoring_disabled");
            hideOverlay("monitoring_disabled");
            return;
        }
        AccessibilityNodeInfo root = null;
        WindowSnapshot windows = null;
        try {
            root = getRootInActiveWindow();
            boolean activeRootUnavailable = root == null;
            String activePackage = root == null || root.getPackageName() == null
                    ? "unknown" : root.getPackageName().toString();
            if (captureActive || root == null || !UBER_PACKAGE.equals(activePackage)) {
                windows = findUberWindowRoot();
            }
            JSONArray windowMetadata = windows == null ? new JSONArray() : windows.metadata;
            boolean uberActive = UBER_PACKAGE.equals(activePackage);
            if (captureActive && shouldRecordWindowState((uberActive ? "uber:" : "foreign:") + activePackage, windowMetadata)) {
                captureStore.record(uberActive ? "ACTIVE_WINDOW" : "ACTIVE_WINDOW_NOT_UBER", DiagnosticCaptureStore.object(
                        "active_package", activePackage, "event_package", request.sourcePackage,
                        "event_type", eventType, "source", source,
                        "uber_window_found", windows != null && windows.root != null,
                        "windows", windowMetadata));
            }
            if (root == null || !UBER_PACKAGE.equals(activePackage)) {
                if (root != null) root.recycle();
                root = null;
                if (windows == null) windows = new WindowSnapshot(null, new JSONArray());
                root = windows.root;
                windows.root = null;
                if (root == null) {
                    if (captureActive) captureStore.recordSnapshotSkipped(startedAt,
                            "active_window_not_uber_and_no_accessible_uber_window");
                    // A third-party accessibility overlay can become the active window while the Uber offer remains.
                    // Do not turn that foreign root into an offer disappearance.
                    if (activeRootUnavailable && monitoringEnabled
                            && UBER_PACKAGE.equals(request.sourcePackage)) {
                        processMissing("tree_unavailable", startedAt);
                    }
                    return;
                }
            }

            long treeReadStartedAt = SystemClock.elapsedRealtime();
            List<AccessibilityNode> snapshot = treeReader.read(root);
            long treeReadMillis = SystemClock.elapsedRealtime() - treeReadStartedAt;
            root = null; // The reader recycles every visited node, including the root.
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SNAPSHOT_TIMING",
                    "source=" + source + "; queue_ms=" + Math.max(0, workerStartedAt - startedAt)
                            + "; tree_ms=" + treeReadMillis + "; nodes=" + snapshot.size());
            if (snapshot.isEmpty()) {
                if (captureActive) captureStore.recordSnapshotSkipped(startedAt, "empty_uber_tree");
                if (monitoringEnabled) processMissing("empty_tree", startedAt);
                return;
            }
            processSnapshot(List.copyOf(snapshot), eventType, startedAt, request, activePackage, windowMetadata);
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            if (captureActive) captureStore.record("PROCESSING_EXCEPTION", DiagnosticCaptureStore.object(
                    "exception", exception.getClass().getSimpleName(), "source", source));
            if (monitoringEnabled) processMissing("tree_read_error", startedAt);
        } finally {
            if (root != null) root.recycle();
            if (windows != null) windows.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        paused = true;
        disableLifecycle("service_interrupted");
        hideOverlay("service_interrupted");
        recordAsync("SERVICE_INTERRUPTED", null);
        if (captureStore != null) captureStore.record("SERVICE_INTERRUPTED", DiagnosticCaptureStore.object());
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        mainHandler.removeCallbacks(foregroundWatch);
        disableLifecycle("service_disconnected");
        hideOverlay("service_disconnected");
        connected = false;
        if (activeService == this) activeService = null;
        recordAsync("SERVICE_DISCONNECTED", null);
        if (captureStore != null) captureStore.record("SERVICE_DISCONNECTED", DiagnosticCaptureStore.object());
        if (analysisThread != null) analysisThread.quitSafely();
        super.onDestroy();
    }

    private void ensureInitialized() {
        if (config == null) config = new ConfigRepository(this);
        if (captureStore == null) captureStore = DiagnosticCaptureStore.get(this);
        if (lifecycle == null) lifecycle = new OfferLifecycleMachine(config.offerDisappearanceToleranceMillis());
        if (overlay == null) overlay = new OverlayController(this);
        if (analysisHandler == null) {
            analysisThread = new HandlerThread("driver-offer-analysis");
            analysisThread.start();
            analysisHandler = new Handler(analysisThread.getLooper());
        }
    }

    private void processSnapshot(List<AccessibilityNode> snapshot, int eventType, long startedAt,
                                 EventRequest request, String activePackage, JSONArray windowMetadata) {
        if (paused || shuttingDown || !connected) return;
        boolean monitoringEnabled = config.monitoringEnabled();
        boolean captureActive = captureStore != null && captureStore.isActive();
        if (!monitoringEnabled && !captureActive) {
            disableLifecycleOnAnalysis("monitoring_disabled");
            DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
            return;
        }
        if (config.detailedDiagnosticsEnabled()) {
            DiagnosticLogStore.recordStructure(this, AccessibilityTreeReader.structureSummary(snapshot));
        }

        try {
            OfferParseResult result = parser.parseNodes(snapshot, captureActive);
            DiagnosticLogStore.recordParserResult(this, result);
            if (captureActive) recordParserDiagnostics(snapshot, result, request, activePackage, windowMetadata,
                    SystemClock.elapsedRealtime() - startedAt, monitoringEnabled);
            if (!monitoringEnabled) {
                captureStore.record("LIFECYCLE_NOT_RUN", DiagnosticCaptureStore.object(
                        "reason", "monitoring_disabled", "parser_accepted", result.accepted()));
                return;
            }
            if (!result.accepted()) {
                processMissing(result.rejectionReason(), startedAt);
                return;
            }

            OfferLifecycleEvent transition = lifecycle.observe(Optional.of(result.offer()), SystemClock.uptimeMillis());
            recordLifecycle(transition, result.offer());
            if (!transition.transitioned()) {
                DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
                return;
            }
            logTransition(transition);
            if (transition.state() == OfferLifecycleState.OFFER_DISAPPEARED) {
                activeOfferSnapshot = null;
                hideOverlay("offer_disappeared");
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
            if (captureStore != null) captureStore.record("PROCESSING_EXCEPTION", DiagnosticCaptureStore.object(
                    "exception", exception.getClass().getSimpleName(), "stage", "parser_or_lifecycle"));
            if (captureStore != null && captureStore.isActive()) {
                String fingerprint = AccessibilityTreeReader.diagnosticFingerprint(snapshot);
                if (captureStore.shouldCaptureTreeSnapshot(fingerprint, true)) {
                    captureStore.record("UBER_TREE_SNAPSHOT", DiagnosticCaptureStore.object(
                            "event_type", eventType, "event_package", request.sourcePackage,
                            "active_package", activePackage, "node_count", snapshot.size(),
                            "parser_exception", exception.getClass().getSimpleName(),
                            "tree", AccessibilityTreeReader.diagnosticTree(snapshot), "windows", windowMetadata));
                }
            }
            if (monitoringEnabled) processMissing("processing_error", startedAt);
            return;
        }
        DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
    }

    private void processMissing(String reason, long startedAt) {
        OfferLifecycleEvent event = lifecycle.observe(Optional.empty(), SystemClock.uptimeMillis());
        recordLifecycle(event, null);
        DiagnosticLogStore.record(this, "DA_OFFER", "OFFER_CANDIDATE_MISSING", "reason=" + safeReason(reason));
        if (event.transitioned()) {
            logTransition(event);
            if (event.state() == OfferLifecycleState.OFFER_DISAPPEARED) {
                activeOfferSnapshot = null;
                hideOverlay("offer_disappeared");
                mainHandler.removeCallbacks(foregroundWatch);
            }
        }
        DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
    }

    /** Polls the real Uber window when accessibility events stop arriving during an active offer. */
    private void checkOfferWindow() {
        if (activeOfferSnapshot == null || config == null || !config.monitoringEnabled() || analysisHandler == null) return;
        boolean uberForeground = hasAccessibleUberWindow();
        if (uberForeground) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSnapshotRequestAt >= FOREGROUND_POLL_MILLIS) {
                requestSnapshot(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, now, true, UBER_PACKAGE);
            }
        } else {
            if (captureStore != null && captureStore.isActive()) {
                captureStore.record("ACTIVE_WINDOW_NOT_UBER", DiagnosticCaptureStore.object(
                        "reason", "no_accessible_uber_window", "source", "foreground_poll"));
            }
        }
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

    private boolean hasAccessibleUberWindow() {
        if (!connected) return false;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && UBER_PACKAGE.contentEquals(root.getPackageName())) return true;
        } catch (RuntimeException ignored) {
            // Fall through to the interactive window list.
        } finally {
            if (root != null) root.recycle();
        }
        WindowSnapshot windows = findUberWindowRoot();
        boolean found = windows.root != null;
        windows.recycle();
        return found;
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

    private void recordLifecycle(OfferLifecycleEvent event, TripOffer offer) {
        if (captureStore == null || !captureStore.isActive() || event == null) return;
        String session = captureStore.currentSessionId();
        if (session != null && !session.equals(lastCaptureSessionId)) {
            lastCaptureSessionId = session;
            lastLifecycleDiagnosticFingerprint = null;
            lastParserDiagnosticFingerprint = null;
            lastParserDiagnosticAt = 0;
        }
        JSONObject details = DiagnosticCaptureStore.object(
                "state", String.valueOf(event.state()), "transitioned", event.transitioned(),
                "session_id", event.sessionId(), "reason", event.reason(), "offer_present", offer != null,
                "amount", offer == null ? null : offer.offeredAmount(),
                "category", offer == null ? null : DiagnosticTextSanitizer.sanitizeCategory(offer.category()),
                "pickup_km", offer == null ? null : offer.pickupDistanceKm(),
                "pickup_minutes", offer == null ? null : offer.pickupEtaMinutes(),
                "trip_km", offer == null ? null : offer.tripDistanceKm(),
                "trip_minutes", offer == null ? null : offer.tripDurationMinutes(),
                "rating", offer == null ? null : offer.passengerRating());
        String fingerprint = details.toString();
        if (event.transitioned() || !fingerprint.equals(lastLifecycleDiagnosticFingerprint)) {
            captureStore.record("LIFECYCLE_RESULT", details);
            lastLifecycleDiagnosticFingerprint = fingerprint;
        }
    }

    private void recordParserDiagnostics(List<AccessibilityNode> snapshot, OfferParseResult result,
                                         EventRequest request, String activePackage, JSONArray windows,
                                         long processingMillis, boolean monitoringEnabled) {
        String fingerprint = AccessibilityTreeReader.diagnosticFingerprint(snapshot);
        boolean candidate = AccessibilityTreeReader.containsOfferCandidate(snapshot);
        if (captureStore.shouldCaptureTreeSnapshot(fingerprint, candidate)) {
            captureStore.record("UBER_TREE_SNAPSHOT", DiagnosticCaptureStore.object(
                    "event_type", request.eventType,
                    "event_type_name", AccessibilityEvent.eventTypeToString(request.eventType),
                    "event_package", request.sourcePackage,
                    "active_package", activePackage,
                    "event_time_uptime_ms", request.startedAt,
                    "node_count", snapshot.size(), "offer_candidate_text", candidate,
                    "tree", AccessibilityTreeReader.diagnosticTree(snapshot), "windows", windows));
        } else {
            captureStore.recordSnapshotSkipped(request.startedAt, "unchanged_or_rate_limited_snapshot");
        }
        if (shouldRecordParserResult(result)) {
            captureStore.record("PARSER_RESULT", DiagnosticCaptureStore.object(
                    "accepted", result.accepted(), "rejection_reason", result.rejectionReason(),
                    "found_fields", DiagnosticCaptureStore.array(result.foundFields()),
                    "unavailable_fields", DiagnosticCaptureStore.array(result.unavailableFields()),
                    "validation_trace", DiagnosticCaptureStore.array(result.diagnosticTrace()),
                    "monitoring_enabled", monitoringEnabled, "processing_ms", processingMillis,
                    "offer_amount", result.offer() == null ? null : result.offer().offeredAmount(),
                    "category", result.offer() == null ? null
                            : DiagnosticTextSanitizer.sanitizeCategory(result.offer().category()),
                    "rating", result.offer() == null ? null : result.offer().passengerRating(),
                    "pickup_km", result.offer() == null ? null : result.offer().pickupDistanceKm(),
                    "pickup_minutes", result.offer() == null ? null : result.offer().pickupEtaMinutes(),
                    "trip_km", result.offer() == null ? null : result.offer().tripDistanceKm(),
                    "trip_minutes", result.offer() == null ? null : result.offer().tripDurationMinutes()));
        }
    }

    private boolean shouldRecordParserResult(OfferParseResult result) {
        if (captureStore == null) return false;
        String session = captureStore.currentSessionId();
        if (session != null && !session.equals(lastCaptureSessionId)) {
            lastCaptureSessionId = session;
            lastLifecycleDiagnosticFingerprint = null;
            lastParserDiagnosticFingerprint = null;
            lastParserDiagnosticAt = 0;
        }
        String fingerprint = result.accepted() + "|" + result.rejectionReason() + "|"
                + result.foundFields() + "|" + result.unavailableFields() + "|" + result.diagnosticTrace();
        long now = SystemClock.elapsedRealtime();
        boolean shouldRecord = !fingerprint.equals(lastParserDiagnosticFingerprint)
                || now - lastParserDiagnosticAt >= 10_000;
        if (shouldRecord) {
            lastParserDiagnosticFingerprint = fingerprint;
            lastParserDiagnosticAt = now;
        }
        return shouldRecord;
    }

    private void recordExternalEvent(String packageName, int eventType, int windowId) {
        String key = packageName + "|" + AccessibilityEvent.eventTypeToString(eventType);
        externalEventCounts.merge(key, 1, Integer::sum);
        if (captureStore == null || !captureStore.isActive()) return;
        long now = SystemClock.elapsedRealtime();
        boolean summaryDue = now - lastExternalSummaryAt >= 5_000;
        if (summaryDue) {
            String activePackage = activeWindowPackage();
            captureStore.record("NON_UBER_EVENT_SUMMARY", DiagnosticCaptureStore.object(
                    "package", packageName, "event_type", eventType,
                    "event_type_name", AccessibilityEvent.eventTypeToString(eventType),
                    "window_id", windowId, "active_window_package", activePackage,
                    "coalesced_counts", new JSONObject(externalEventCounts)));
            captureStore.record("TREE_NOT_CAPTURED", DiagnosticCaptureStore.object(
                    "reason", "non_uber_event_content_not_read", "event_package", packageName,
                    "event_type", eventType, "coalesced_counts", new JSONObject(externalEventCounts)));
            externalEventCounts.clear();
            lastExternalSummaryAt = now;
        }
    }

    private String activeWindowPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            return root == null || root.getPackageName() == null ? "unknown" : root.getPackageName().toString();
        } catch (RuntimeException ignored) {
            return "unavailable";
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean shouldRecordWindowState(String activePackage, JSONArray windows) {
        String fingerprint = String.valueOf(activePackage) + "|" + String.valueOf(windows);
        long now = SystemClock.elapsedRealtime();
        if (fingerprint.equals(lastExternalWindowFingerprint) && now - lastWindowStateAt < 15_000) return false;
        lastExternalWindowFingerprint = fingerprint;
        lastWindowStateAt = now;
        return true;
    }

    private WindowSnapshot findUberWindowRoot() {
        JSONArray metadata = new JSONArray();
        AccessibilityNodeInfo uberRoot = null;
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    String pkg = root == null || root.getPackageName() == null
                            ? "unknown" : root.getPackageName().toString();
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    metadata.put(DiagnosticCaptureStore.object("id", window.getId(), "type", window.getType(),
                            "layer", window.getLayer(), "active", window.isActive(), "focused", window.isFocused(),
                            "root_package", pkg, "bounds", bounds.flattenToString()));
                    if (uberRoot == null && UBER_PACKAGE.equals(pkg) && root != null) {
                        uberRoot = AccessibilityNodeInfo.obtain(root);
                    }
                } finally { if (root != null) root.recycle(); }
            }
        } catch (RuntimeException exception) {
            if (captureStore != null && captureStore.isActive()) captureStore.record("WINDOW_METADATA_ERROR",
                    DiagnosticCaptureStore.object("exception", exception.getClass().getSimpleName()));
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                if (window != null) window.recycle();
            }
        }
        return new WindowSnapshot(uberRoot, metadata);
    }

    private static String safeClass(CharSequence value) {
        if (value == null) return "unknown";
        return value.toString().replaceAll("[^A-Za-z0-9_.$]", "");
    }

    private static final class WindowSnapshot {
        AccessibilityNodeInfo root;
        final JSONArray metadata;
        WindowSnapshot(AccessibilityNodeInfo root, JSONArray metadata) { this.root = root; this.metadata = metadata; }
        void recycle() { if (root != null) { root.recycle(); root = null; } }
    }

    private void hideOverlay(String reason) { if (overlay != null) overlay.hide(reason); }

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
                if (simulationEpoch == epoch && simulationSessionId == session) overlay.hide("simulation_disappeared");
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
