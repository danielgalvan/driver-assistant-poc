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
import com.driverassistant.diagnostics.BoundedEventQueue;
import com.driverassistant.diagnostics.ForegroundPollPolicy;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads accessible content only from Uber Driver; foreign events are reduced to metadata. */
public final class UberAccessibilityService extends AccessibilityService {
    public static final String UBER_PACKAGE = "com.ubercab.driver";
    private static final int MAX_PENDING_SNAPSHOTS = 8;
    private static final int MAX_EVENT_CONTEXT_NODES = 64;
    private static final int MAX_POLL_NODES = 220;
    private static final int MAX_POLL_WINDOWS = 3;
    private static volatile boolean connected;
    private static volatile UberAccessibilityService activeService;
    private static long nextSimulationSession = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean snapshotDrainScheduled = new AtomicBoolean();
    private final ForegroundPollPolicy foregroundPollPolicy = new ForegroundPollPolicy();
    private final BoundedEventQueue<EventRequest> pendingSnapshots = new BoundedEventQueue<>(MAX_PENDING_SNAPSHOTS);
    private final Map<Integer, String> lastSnapshotFingerprints = new HashMap<>();
    private final AtomicBoolean pollRequestedSoon = new AtomicBoolean();
    private final Runnable foregroundWatch = new Runnable() {
        @Override public void run() { runForegroundPoll(); }
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
    private long lastQueueOverflowDiagnosticAt;
    private int suppressedQueueOverflowDiagnostics;
    private volatile long lastPollStatusAt;
    private volatile long lastPollTimingAt;
    private volatile long lastPollStartedAt;
    private String lastCaptureSessionId;
    private String lastParserDiagnosticFingerprint;
    private long lastParserDiagnosticAt;
    private String lastLifecycleDiagnosticFingerprint;

    private static final class EventRequest {
        final int eventType;
        final long startedAt;
        final long captureStartedAt;
        final long captureFinishedAt;
        final long eventTimeUptimeMillis;
        final long eventAgeAtReceiptMillis;
        final boolean foregroundPoll;
        final boolean authoritativeWindowSnapshot;
        final boolean snapshotTruncated;
        final String snapshotOrigin;
        final String sourcePackage;
        final String eventClass;
        final String sourceCaptureError;
        final int windowId;
        final int windowType;
        final int windowLayer;
        final boolean windowActive;
        final boolean windowFocused;
        final int contentChangeTypes;
        final List<AccessibilityNode> snapshot;

        EventRequest(int eventType, long startedAt, long captureStartedAt, long captureFinishedAt,
                     long eventTimeUptimeMillis, long eventAgeAtReceiptMillis, boolean foregroundPoll,
                     boolean authoritativeWindowSnapshot, boolean snapshotTruncated,
                     String snapshotOrigin, String sourcePackage,
                     String eventClass, String sourceCaptureError, int windowId, int windowType,
                     int windowLayer, boolean windowActive, boolean windowFocused, int contentChangeTypes,
                     List<AccessibilityNode> snapshot) {
            this.eventType = eventType;
            this.startedAt = startedAt;
            this.captureStartedAt = captureStartedAt;
            this.captureFinishedAt = captureFinishedAt;
            this.eventTimeUptimeMillis = eventTimeUptimeMillis;
            this.eventAgeAtReceiptMillis = eventAgeAtReceiptMillis;
            this.foregroundPoll = foregroundPoll;
            this.authoritativeWindowSnapshot = authoritativeWindowSnapshot;
            this.snapshotTruncated = snapshotTruncated;
            this.snapshotOrigin = snapshotOrigin;
            this.sourcePackage = sourcePackage;
            this.eventClass = eventClass;
            this.sourceCaptureError = sourceCaptureError;
            this.windowId = windowId;
            this.windowType = windowType;
            this.windowLayer = windowLayer;
            this.windowActive = windowActive;
            this.windowFocused = windowFocused;
            this.contentChangeTypes = contentChangeTypes;
            this.snapshot = snapshot == null ? List.of() : List.copyOf(snapshot);
        }

        int priority() {
            int snapshotPriority = snapshotRelevance(snapshot);
            if (snapshotPriority >= 5) return snapshotPriority;
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return 4;
            if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && contentChangeTypes != 0) return 3;
            if (UBER_PACKAGE.equals(sourcePackage)) return 2;
            return 0;
        }

        boolean protectedSnapshot() { return snapshotRelevance(snapshot) >= 5; }

        boolean isWindowTransition() {
            return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        }

        boolean isTextOrContentChange() {
            return eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && contentChangeTypes != 0;
        }

        private static int snapshotRelevance(List<AccessibilityNode> nodes) {
            if (nodes == null) return 0;
            StringBuilder text = new StringBuilder();
            for (AccessibilityNode node : nodes) {
                if (node == null || !node.visibleToUser()) continue;
                if (node.resourceId() != null && (node.resourceId().endsWith("ub__driver_job_offers_pill_title")
                        || node.resourceId().endsWith("ub__driver_job_offers_pill_badge")
                        || node.resourceId().endsWith("ub_driver_offers_browse_offer_pill_view")
                        || node.resourceId().endsWith("ub_driver_offers_browse_pill_container"))) {
                    text.append(" __offer_structure__ ");
                }
                text.append(' ').append(node.accessibleText().toLowerCase(java.util.Locale.ROOT));
            }
            String value = text.toString();
            boolean acceptance = value.contains("aceitar") || value.contains("selecionar");
            boolean offerCue = acceptance
                    || value.contains("oferta") || value.contains("corrida")
                    || value.contains("uberx") || value.contains("comfort") || value.contains("priority");
            boolean money = value.contains("r$") || value.contains("brl");
            boolean route = (value.contains("km") || value.contains("metros"))
                    && (value.contains("min") || value.contains("hora"));
            if (acceptance || money && (offerCue || route)) return 6;
            return value.contains("__offer_structure__") ? 5 : 0;
        }
    }

    public static boolean isConnected() {
        UberAccessibilityService service = activeService;
        return connected && service != null && !service.paused && !service.shuttingDown;
    }

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
        if (service.captureStore != null && service.captureStore.isActive()) {
            service.captureStore.record("MODE_STATE", DiagnosticCaptureStore.object(
                    "reason", "monitoring_changed", "monitoring_enabled", enabled,
                    "overlay_configured", service.config != null && service.config.overlayEnabled()));
        }
        if (!enabled) {
            service.stopForegroundWatch();
            service.disableLifecycle("monitoring_disabled");
            if (service.overlay != null) service.overlay.hide("monitoring_disabled");
        } else {
            service.startForegroundWatch();
        }
    }

    public static void refreshOverlayConfiguration() {
        UberAccessibilityService service = activeService;
        if (service == null || service.overlay == null || service.config == null) return;
        if (service.captureStore != null && service.captureStore.isActive()) {
            service.captureStore.record("MODE_STATE", DiagnosticCaptureStore.object(
                    "reason", "overlay_configuration_changed",
                    "monitoring_enabled", service.config.monitoringEnabled(),
                    "overlay_configured", service.config.overlayEnabled()));
        }
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
        if (lifecycle == null) lifecycle = new OfferLifecycleMachine(config.offerDisappearanceToleranceMillis());
        startAnalysisThread();
        analysisHandler.post(() -> DiagnosticLogStore.record(this, "DA_ACCESSIBILITY",
                "SERVICE_CONNECTED", "target=uber_driver"));
        if (config.monitoringEnabled()) startForegroundWatch();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (paused || shuttingDown || event == null) return;
        ensureInitialized();
        long receivedAt = SystemClock.elapsedRealtime();
        int eventType = event.getEventType();
        int windowId = event.getWindowId();
        int contentChangeTypes = event.getContentChangeTypes();
        long eventTimeUptimeMillis = event.getEventTime();
        String eventPackage = event.getPackageName() == null ? "unknown" : event.getPackageName().toString();
        boolean uberEvent = UBER_PACKAGE.equals(eventPackage);
        if (!uberEvent) {
            if (captureStore.isActive()) recordExternalEvent(eventPackage, eventType, windowId);
            if (config != null && config.monitoringEnabled()) requestForegroundPollSoon();
            return;
        }

        boolean monitoringEnabled = config.monitoringEnabled();
        if (!monitoringEnabled && !captureStore.isActive()) {
            analysisHandler.post(() -> {
                DiagnosticLogStore.markUberEvent(this, Integer.toString(eventType));
                DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "UBER_EVENT", "type=" + eventType);
                disableLifecycleOnAnalysis("monitoring_disabled");
            });
            if (overlay != null) overlay.hide("monitoring_disabled");
            return;
        }

        long captureStartedAt = SystemClock.elapsedRealtime();
        List<AccessibilityNode> snapshot = new java.util.ArrayList<>();
        String sourceCaptureError = null;
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source != null) {
                CharSequence sourcePackage = source.getPackageName();
                if (sourcePackage != null && UBER_PACKAGE.contentEquals(sourcePackage)) {
                    snapshot.addAll(treeReader.readEventContext(source, MAX_EVENT_CONTEXT_NODES));
                } else {
                    sourceCaptureError = sourcePackage == null ? "source_package_unavailable" : "source_package_not_uber";
                }
            }
            appendEventText(snapshot, event);
        } catch (RuntimeException exception) {
            sourceCaptureError = exception.getClass().getSimpleName();
        } finally {
            if (source != null) source.recycle();
        }
        long captureFinishedAt = SystemClock.elapsedRealtime();
        long eventAgeAtReceipt = eventTimeUptimeMillis <= 0 ? -1
                : Math.max(0, SystemClock.uptimeMillis() - eventTimeUptimeMillis);
        EventRequest request = new EventRequest(eventType, receivedAt, captureStartedAt, captureFinishedAt,
                eventTimeUptimeMillis, eventAgeAtReceipt, false, false,
                snapshot.size() >= MAX_EVENT_CONTEXT_NODES, "event_subtree",
                UBER_PACKAGE, safeClass(event.getClassName()), sourceCaptureError,
                windowId, -1, -1, false, false, contentChangeTypes, snapshot);
        boolean hasSnapshot = !snapshot.isEmpty();
        if (captureStore.isActive()) recordEventCapture(request, hasSnapshot ? "pending_queue" : "no_snapshot");
        if (hasSnapshot) requestSnapshot(request);
        else if (captureStore.isActive()) captureStore.recordSnapshotSkipped(receivedAt,
                sourceCaptureError == null ? "event_source_unavailable_no_text" : "event_source_error_" + sourceCaptureError);
        if (monitoringEnabled) {
            foregroundPollPolicy.signalActivity();
            requestForegroundPollSoon();
        }
    }

    private void appendEventText(List<AccessibilityNode> snapshot, AccessibilityEvent event) {
        int order = snapshot.size();
        int added = 0;
        for (CharSequence text : event.getText()) {
            if (text != null && !text.toString().isBlank()) {
                snapshot.add(AccessibilityNode.textOnly(text.toString(), order++));
                added++;
            }
            if (added >= 12) break;
        }
        CharSequence description = event.getContentDescription();
        if (description != null && !description.toString().isBlank()) {
            snapshot.add(new AccessibilityNode(null, description.toString(), null, null,
                    -1, 0, order, true));
        }
    }

    private void requestSnapshot(EventRequest request) {
        String queueKey = Integer.toString(request.windowId);
        BoundedEventQueue.OfferResult<EventRequest> enqueue = pendingSnapshots.offer(
                request, request.priority(), queueKey, request.protectedSnapshot());
        EventRequest dropped = enqueue.dropped();
        if (dropped != null && enqueue.accepted() && captureStore != null && captureStore.isActive()) {
            boolean coalesced = "coalesced_same_window".equals(enqueue.reason());
            captureStore.recordSnapshotSkipped(dropped.startedAt, coalesced
                    ? "same_window_snapshot_coalesced" : "event_queue_snapshot_evicted_" + enqueue.reason());
            long now = SystemClock.elapsedRealtime();
            boolean trueOverflow = enqueue.reason().contains("evicted")
                    || enqueue.reason().contains("queue_contains") || enqueue.reason().contains("no_evictable");
            if (trueOverflow && (lastQueueOverflowDiagnosticAt == 0 || now - lastQueueOverflowDiagnosticAt >= 5_000)) {
                captureStore.record("EVENT_QUEUE_OVERFLOW", DiagnosticCaptureStore.object(
                        "incoming_event_type", request.eventType, "incoming_package", request.sourcePackage,
                        "incoming_window_id", request.windowId, "incoming_origin", request.snapshotOrigin,
                        "dropped_event_type", dropped.eventType,
                        "dropped_package", dropped.sourcePackage, "dropped_window_id", dropped.windowId,
                        "incoming_dropped", enqueue.incomingDropped(), "reason", enqueue.reason(),
                        "suppressed_overflows", suppressedQueueOverflowDiagnostics,
                        "queue_size", pendingSnapshots.size(), "capacity", MAX_PENDING_SNAPSHOTS));
                lastQueueOverflowDiagnosticAt = now;
                suppressedQueueOverflowDiagnostics = 0;
            } else if (trueOverflow) {
                suppressedQueueOverflowDiagnostics++;
            }
        }
        if (enqueue.accepted()) scheduleSnapshotDrain();
        else if (captureStore != null && captureStore.isActive()) {
            captureStore.recordSnapshotSkipped(request.startedAt, "event_snapshot_dropped_" + enqueue.reason());
            captureStore.record("SNAPSHOT_QUEUE_RESULT", DiagnosticCaptureStore.object(
                    "accepted", false, "reason", enqueue.reason(), "window_id", request.windowId,
                    "snapshot_origin", request.snapshotOrigin, "protected", request.protectedSnapshot(),
                    "queue_size", pendingSnapshots.size(), "capacity", MAX_PENDING_SNAPSHOTS));
        }
    }

    private void recordEventCapture(EventRequest request, String queueStatus) {
        if (captureStore == null || !captureStore.isActive()) return;
        captureStore.record("ACCESSIBILITY_EVENT", DiagnosticCaptureStore.object(
                "event_type", request.eventType, "event_type_name", AccessibilityEvent.eventTypeToString(request.eventType),
                "package", request.sourcePackage, "class", request.eventClass,
                "window_id", request.windowId, "content_change_types", request.contentChangeTypes,
                "event_time_uptime_ms", request.eventTimeUptimeMillis,
                "event_age_at_receipt_ms", request.eventAgeAtReceiptMillis,
                "receive_elapsed_ms", request.startedAt, "capture_started_elapsed_ms", request.captureStartedAt,
                "capture_finished_elapsed_ms", request.captureFinishedAt,
                "capture_ms", Math.max(0, request.captureFinishedAt - request.captureStartedAt),
                "snapshot_origin", request.snapshotOrigin, "node_count", request.snapshot.size(),
                "snapshot_truncated", request.snapshotTruncated,
                "source_capture_error", request.sourceCaptureError, "queue_status", queueStatus));
    }

    private void scheduleSnapshotDrain() {
        Handler handler = analysisHandler;
        if (handler == null || !snapshotDrainScheduled.compareAndSet(false, true)) return;
        handler.post(() -> {
            try {
                EventRequest request = pendingSnapshots.pollFirst();
                if (request != null) captureSnapshot(request);
            } finally {
                snapshotDrainScheduled.set(false);
                if (!pendingSnapshots.isEmpty()) scheduleSnapshotDrain();
            }
        });
    }

    /** Processes immutable event-time snapshots; the queue never retains Android accessibility objects. */
    private void captureSnapshot(EventRequest request) {
        captureSnapshot(request, true);
    }

    private OfferParseResult captureSnapshot(EventRequest request, boolean applyLifecycle) {
        if (paused || shuttingDown || !connected) return null;
        int eventType = request.eventType;
        long startedAt = request.startedAt;
        long workerStartedAt = SystemClock.elapsedRealtime();
        String source = request.snapshotOrigin;
        DiagnosticLogStore.markUberEvent(this, request.foregroundPoll ? source : Integer.toString(eventType));
        DiagnosticLogStore.record(this, "DA_ACCESSIBILITY",
                request.foregroundPoll ? "FOREGROUND_RECHECK" : "UBER_EVENT",
                request.foregroundPoll ? "source=" + source : "type=" + eventType);
        boolean captureActive = captureStore != null && captureStore.isActive();
        boolean monitoringEnabled = config.monitoringEnabled();
        if (!monitoringEnabled && !captureActive) {
            disableLifecycleOnAnalysis("monitoring_disabled");
            hideOverlay("monitoring_disabled");
            return null;
        }
        long queueMillis = Math.max(0, workerStartedAt - request.captureFinishedAt);
        long treeReadMillis = Math.max(0, request.captureFinishedAt - request.captureStartedAt);
        if (request.snapshot.isEmpty()) {
            if (captureActive) captureStore.recordSnapshotSkipped(request.startedAt,
                    request.sourceCaptureError == null ? "event_source_unavailable_no_text" : "event_source_error");
            return null;
        }

        String fingerprint = AccessibilityTreeReader.contentFingerprint(request.snapshot);
        String previousFingerprint = lastSnapshotFingerprints.put(request.windowId, fingerprint);
        while (lastSnapshotFingerprints.size() > 32) {
            Integer oldest = lastSnapshotFingerprints.keySet().iterator().next();
            lastSnapshotFingerprints.remove(oldest);
        }
        if (fingerprint.equals(previousFingerprint)) {
            if (captureActive) captureStore.recordSnapshotSkipped(request.startedAt, "duplicate_immutable_snapshot");
            return null;
        }

        JSONArray windows = new JSONArray();
        windows.put(DiagnosticCaptureStore.object("id", request.windowId, "type", request.windowType,
                "layer", request.windowLayer, "active", request.windowActive,
                "focused", request.windowFocused, "root_package", UBER_PACKAGE, "source", source));
        WindowRoot window = new WindowRoot(request.windowId, request.windowType, request.windowLayer,
                request.windowActive, request.windowFocused, null);
        try {
            long parseStartedAt = SystemClock.elapsedRealtime();
            OfferParseResult result = processSnapshot(request.snapshot, startedAt, request, UBER_PACKAGE,
                    windows, window, monitoringEnabled, queueMillis, treeReadMillis);
            long parseMillis = SystemClock.elapsedRealtime() - parseStartedAt;
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "SNAPSHOT_TIMING",
                    "source=" + source + "; capture_ms=" + treeReadMillis + "; queue_ms=" + queueMillis
                            + "; parse_ms=" + parseMillis + "; event_age_at_receipt_ms="
                            + request.eventAgeAtReceiptMillis + "; nodes=" + request.snapshot.size()
                            + "; window_id=" + request.windowId);
            if (!monitoringEnabled) {
                if (captureActive) captureStore.record("LIFECYCLE_NOT_RUN", DiagnosticCaptureStore.object(
                        "reason", "monitoring_disabled", "parser_accepted", result != null && result.accepted(),
                        "snapshot_origin", source, "node_count", request.snapshot.size()));
                return result;
            }
            if (applyLifecycle && result != null && result.accepted()) applyOfferResult(result, startedAt, source);
            else if (applyLifecycle && request.authoritativeWindowSnapshot && result != null) {
                processMissing(result.rejectionReason(), startedAt);
            }
            return result;
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName() + "; source=" + source);
            if (captureActive) captureStore.record("PROCESSING_EXCEPTION", DiagnosticCaptureStore.object(
                    "exception", exception.getClass().getSimpleName(), "source", source,
                    "window_id", request.windowId));
            return null;
        }
    }

    @Override
    public void onInterrupt() {
        paused = true;
        stopForegroundWatch();
        pendingSnapshots.clear();
        disableLifecycle("service_interrupted");
        hideOverlay("service_interrupted");
        recordAsync("SERVICE_INTERRUPTED", null);
        if (captureStore != null) captureStore.record("SERVICE_INTERRUPTED", DiagnosticCaptureStore.object());
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        stopForegroundWatch();
        pendingSnapshots.clear();
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
        startAnalysisThread();
    }

    private void startAnalysisThread() {
        if (analysisThread != null && analysisThread.isAlive() && analysisHandler != null) return;
        analysisThread = new HandlerThread("driver-offer-analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
    }

    private OfferParseResult processSnapshot(List<AccessibilityNode> snapshot, long startedAt,
                                             EventRequest request, String activePackage,
                                             JSONArray windowMetadata, WindowRoot window,
                                             boolean monitoringEnabled, long queueMillis,
                                             long treeReadMillis) {
        if (paused || shuttingDown || !connected) return null;
        boolean captureActive = captureStore != null && captureStore.isActive();
        if (!monitoringEnabled && !captureActive) {
            return null;
        }
        if (config.detailedDiagnosticsEnabled()) {
            DiagnosticLogStore.recordStructure(this, AccessibilityTreeReader.structureSummary(snapshot));
        }

        try {
            long parserStartedAt = SystemClock.elapsedRealtime();
            OfferParseResult result = parser.parseNodes(snapshot, captureActive);
            long parserDurationMillis = SystemClock.elapsedRealtime() - parserStartedAt;
            DiagnosticLogStore.recordParserResult(this, result);
            if (captureActive) recordParserDiagnostics(snapshot, result, request, activePackage, windowMetadata,
                    parserDurationMillis, monitoringEnabled, window,
                    queueMillis, treeReadMillis);
            return result;
        } catch (RuntimeException exception) {
            DiagnosticLogStore.record(this, "DA_ACCESSIBILITY", "PROCESSING_ERROR",
                    "exception=" + exception.getClass().getSimpleName());
            if (captureStore != null) captureStore.record("PROCESSING_EXCEPTION", DiagnosticCaptureStore.object(
                    "exception", exception.getClass().getSimpleName(), "stage", "parser", "window_id", window.id));
            if (captureStore != null && captureStore.isActive()) {
                String fingerprint = window.id + "|" + AccessibilityTreeReader.contentFingerprint(snapshot);
                if (captureStore.shouldCaptureTreeSnapshot(Integer.toString(window.id), fingerprint,
                        true, request.isWindowTransition())) {
                    captureStore.record("UBER_TREE_SNAPSHOT", DiagnosticCaptureStore.object(
                            "event_type", request.eventType, "event_package", request.sourcePackage,
                            "active_package", activePackage, "window_id", window.id,
                            "window_type", window.type, "window_layer", window.layer,
                            "window_active", window.active, "window_focused", window.focused,
                            "node_count", snapshot.size(),
                            "parser_exception", exception.getClass().getSimpleName(),
                            "tree", AccessibilityTreeReader.diagnosticTree(snapshot), "windows", windowMetadata));
                }
            }
            return null;
        }
    }

    private void applyOfferResult(OfferParseResult result, long startedAt, String snapshotOrigin) {
        if (result == null || !result.accepted()) {
            processMissing(result == null ? "parser_unavailable" : result.rejectionReason(), startedAt);
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
        long overlayRequestedAt = SystemClock.elapsedRealtime();
        overlay.show(offer, metrics, classification, transition.sessionId(), false);
        if (captureStore != null && captureStore.isActive()) {
            captureStore.record("OFFER_TO_OVERLAY_REQUEST", DiagnosticCaptureStore.object(
                    "snapshot_origin", snapshotOrigin, "event_received_elapsed_ms", startedAt,
                    "overlay_request_elapsed_ms", overlayRequestedAt,
                    "event_to_overlay_request_ms", Math.max(0, overlayRequestedAt - startedAt),
                    "overlay_enabled", config.overlayEnabled()));
        }
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
            }
        }
        DiagnosticLogStore.recordProcessingTime(this, SystemClock.elapsedRealtime() - startedAt);
    }

    /** Polls Uber-owned windows even before the first offer has been recognized. */
    private void runForegroundPoll() {
        if (!foregroundPollPolicy.begin()) return;
        long pollStartedAt = SystemClock.elapsedRealtime();
        lastPollStartedAt = pollStartedAt;
        boolean uberVisible = false;
        int uberWindowCount = 0;
        boolean snapshotChanged = false;
        boolean relevantSnapshotChanged = false;
        WindowSnapshot windows = null;
        try {
            if (paused || shuttingDown || !connected || config == null || !config.monitoringEnabled()) return;
            windows = findUberWindowRoots();
            uberVisible = !windows.roots.isEmpty();
            uberWindowCount = windows.roots.size();
            if (!uberVisible) {
                if (captureStore != null && captureStore.isActive()) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPollStatusAt >= 5_000) {
                        captureStore.record("ACTIVE_WINDOW_NOT_UBER", DiagnosticCaptureStore.object(
                                "reason", "no_accessible_uber_window", "source", "foreground_poll",
                                "poll_elapsed_ms", pollStartedAt));
                        lastPollStatusAt = now;
                    }
                }
                if (activeOfferSnapshot != null) processMissing("uber_window_unavailable", pollStartedAt);
                return;
            }

            windows.roots.sort((left, right) -> {
                int focusedOrder = Boolean.compare(right.focused, left.focused);
                if (focusedOrder != 0) return focusedOrder;
                int activeOrder = Boolean.compare(right.active, left.active);
                if (activeOrder != 0) return activeOrder;
                return Integer.compare(right.layer, left.layer);
            });
            int examined = Math.min(MAX_POLL_WINDOWS, windows.roots.size());
            OfferParseResult selectedOffer = null;
            int selectedScore = Integer.MIN_VALUE;
            String rejectionReason = "empty_uber_tree";
            boolean parsedWindow = false;
            boolean unchangedWindow = false;
            for (int index = 0; index < examined; index++) {
                if (paused || shuttingDown || !config.monitoringEnabled()) break;
                WindowRoot window = windows.roots.get(index);
                AccessibilityNodeInfo root = window.root;
                window.root = null;
                long captureStartedAt = SystemClock.elapsedRealtime();
                List<AccessibilityNode> snapshot;
                try {
                    snapshot = treeReader.read(root, MAX_POLL_NODES);
                } catch (RuntimeException exception) {
                    if (captureStore != null && captureStore.isActive()) {
                        captureStore.record("PROCESSING_EXCEPTION", DiagnosticCaptureStore.object(
                                "exception", exception.getClass().getSimpleName(), "stage", "poll_tree_read",
                                "window_id", window.id));
                    }
                    continue;
                }
                long captureFinishedAt = SystemClock.elapsedRealtime();
                if (snapshot.isEmpty()) {
                    if (captureStore != null && captureStore.isActive()) {
                        captureStore.recordSnapshotSkipped(pollStartedAt, "empty_polled_uber_window");
                    }
                    continue;
                }
                String fingerprint = AccessibilityTreeReader.contentFingerprint(snapshot);
                String prior = lastSnapshotFingerprints.get(window.id);
                if (fingerprint.equals(prior)) {
                    unchangedWindow = true;
                    if (captureStore != null && captureStore.isActive()) {
                        captureStore.recordSnapshotSkipped(pollStartedAt, "unchanged_polled_uber_window");
                    }
                    continue;
                }

                snapshotChanged = true;
                relevantSnapshotChanged |= EventRequest.snapshotRelevance(snapshot) >= 5;
                long eventUptime = SystemClock.uptimeMillis();
                EventRequest request = new EventRequest(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        pollStartedAt, captureStartedAt, captureFinishedAt, eventUptime, 0,
                        true, snapshot.size() < MAX_POLL_NODES, snapshot.size() >= MAX_POLL_NODES,
                        "uber_window_poll", UBER_PACKAGE, "", null,
                        window.id, window.type, window.layer, window.active, window.focused,
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, snapshot);
                OfferParseResult result = captureSnapshot(request, false);
                if (result == null) continue;
                parsedWindow = true;
                if (result.accepted()) {
                    int score = result.foundFields().size() * 10
                            + (window.focused ? 2 : window.active ? 1 : 0);
                    if (score > selectedScore) {
                        selectedOffer = result;
                        selectedScore = score;
                    }
                } else if (rejectionReason.equals("empty_uber_tree")) {
                    rejectionReason = result.rejectionReason();
                }
            }
            if (selectedOffer != null) {
                applyOfferResult(selectedOffer, pollStartedAt, "uber_window_poll");
            } else if (snapshotChanged && parsedWindow
                    && (activeOfferSnapshot == null || !unchangedWindow)) {
                processMissing(rejectionReason, pollStartedAt);
            }
        } catch (RuntimeException exception) {
            if (captureStore != null && captureStore.isActive()) {
                captureStore.record("POLL_EXCEPTION", DiagnosticCaptureStore.object(
                        "exception", exception.getClass().getSimpleName(),
                        "stage", "uber_window_poll"));
            }
        } finally {
            if (windows != null) windows.recycle();
            long delay = foregroundPollPolicy.finish(uberVisible, relevantSnapshotChanged);
            if (pollRequestedSoon.getAndSet(false)) delay = Math.min(delay, fastPollDelayMillis());
            Handler handler = analysisHandler;
            if (handler != null && foregroundPollPolicy.enabled() && !paused && !shuttingDown) {
                handler.removeCallbacks(foregroundWatch);
                handler.postDelayed(foregroundWatch, delay);
            }
            if (captureStore != null && captureStore.isActive()) {
                long now = SystemClock.elapsedRealtime();
                if (snapshotChanged || now - lastPollTimingAt >= 5_000) {
                    captureStore.record("POLL_CYCLE", DiagnosticCaptureStore.object(
                            "uber_visible", uberVisible, "snapshot_changed", snapshotChanged,
                            "relevant_snapshot_changed", relevantSnapshotChanged,
                            "queue_depth", pendingSnapshots.size(), "next_delay_ms", delay,
                            "cycle_ms", now - pollStartedAt,
                            "uber_window_count", uberWindowCount,
                            "roots_examined", Math.min(MAX_POLL_WINDOWS, uberWindowCount),
                            "roots_omitted", Math.max(0, uberWindowCount - MAX_POLL_WINDOWS),
                            "windows", windows == null ? new JSONArray() : windows.metadata,
                            "diagnostic_writer_queue_ms", captureStore.lastWriteQueueMillis(),
                            "diagnostic_writer_append_ms", captureStore.lastWriteDurationMillis()));
                    lastPollTimingAt = now;
                }
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

    private void disableLifecycle(String reason) {
        simulationEpoch++;
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
        if (config == null || !config.monitoringEnabled() || paused || shuttingDown) return;
        Handler handler = analysisHandler;
        if (handler == null) return;
        boolean wasEnabled = foregroundPollPolicy.enabled();
        if (!wasEnabled) foregroundPollPolicy.enable();
        foregroundPollPolicy.signalActivity();
        if (foregroundPollPolicy.inFlight()) {
            pollRequestedSoon.set(true);
            return;
        }
        handler.removeCallbacks(foregroundWatch);
        handler.postDelayed(foregroundWatch, wasEnabled ? fastPollDelayMillis() : 0);
    }

    private void requestForegroundPollSoon() {
        if (config == null || !config.monitoringEnabled() || paused || shuttingDown) return;
        Handler handler = analysisHandler;
        if (handler == null) return;
        if (!foregroundPollPolicy.enabled()) foregroundPollPolicy.enable();
        foregroundPollPolicy.signalActivity();
        if (foregroundPollPolicy.inFlight()) {
            pollRequestedSoon.set(true);
            return;
        }
        handler.removeCallbacks(foregroundWatch);
        handler.postDelayed(foregroundWatch, fastPollDelayMillis());
    }

    private long fastPollDelayMillis() {
        long lastPoll = lastPollStartedAt;
        if (lastPoll <= 0) return 75;
        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - lastPoll);
        return Math.max(75, ForegroundPollPolicy.FAST_INTERVAL_MILLIS - elapsed);
    }

    private void stopForegroundWatch() {
        foregroundPollPolicy.disable();
        pollRequestedSoon.set(false);
        Handler handler = analysisHandler;
        if (handler != null) handler.removeCallbacks(foregroundWatch);
        mainHandler.removeCallbacks(foregroundWatch);
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
                                         long processingMillis, boolean monitoringEnabled,
                                         WindowRoot window, long queueMillis, long treeReadMillis) {
        String fingerprint = window.id + "|" + AccessibilityTreeReader.contentFingerprint(snapshot);
        boolean candidate = AccessibilityTreeReader.containsOfferCandidate(snapshot);
        boolean structureSignal = AccessibilityTreeReader.containsOfferStructureSignal(snapshot);
        boolean relevantTextEvidence = AccessibilityTreeReader.containsRelevantDiagnosticText(snapshot);
        boolean importantChange = request.isWindowTransition()
                || "event_subtree".equals(request.snapshotOrigin)
                && request.isTextOrContentChange() && relevantTextEvidence;
        if (captureStore.shouldCaptureTreeSnapshot(Integer.toString(window.id), fingerprint,
                candidate, importantChange)) {
            captureStore.record("UBER_TREE_SNAPSHOT", DiagnosticCaptureStore.object(
                    "event_type", request.eventType,
                    "event_type_name", AccessibilityEvent.eventTypeToString(request.eventType),
                    "event_package", request.sourcePackage,
                    "active_package", activePackage,
                    "event_time_uptime_ms", request.eventTimeUptimeMillis,
                    "event_age_at_receipt_ms", request.eventAgeAtReceiptMillis,
                    "event_received_elapsed_ms", request.startedAt,
                    "capture_started_elapsed_ms", request.captureStartedAt,
                    "capture_finished_elapsed_ms", request.captureFinishedAt,
                    "snapshot_origin", request.snapshotOrigin,
                    "queue_delay_ms", queueMillis, "tree_read_ms", treeReadMillis,
                    "event_window_id", request.windowId,
                    "content_change_types", request.contentChangeTypes,
                    "window_id", window.id, "window_type", window.type, "window_layer", window.layer,
                    "window_active", window.active, "window_focused", window.focused,
                    "node_count", snapshot.size(), "offer_candidate_text", candidate,
                    "offer_structure_signal", structureSignal,
                    "protected_snapshot", request.protectedSnapshot(),
                    "snapshot_truncated", request.snapshotTruncated,
                    "relevant_text_evidence", relevantTextEvidence,
                    "snapshot_trigger", request.isWindowTransition() ? "window_transition"
                            : importantChange ? "relevant_content_change" : "changed_tree",
                    "tree", AccessibilityTreeReader.diagnosticTree(snapshot), "windows", windows));
        } else {
            captureStore.recordSnapshotSkipped(request.startedAt, "unchanged_or_rate_limited_snapshot");
        }
        if (shouldRecordParserResult(result, window.id)) {
            captureStore.record("PARSER_RESULT", DiagnosticCaptureStore.object(
                    "accepted", result.accepted(), "rejection_reason", result.rejectionReason(),
                    "found_fields", DiagnosticCaptureStore.array(result.foundFields()),
                    "unavailable_fields", DiagnosticCaptureStore.array(result.unavailableFields()),
                    "validation_trace", DiagnosticCaptureStore.array(result.diagnosticTrace()),
                    "monitoring_enabled", monitoringEnabled, "processing_ms", processingMillis,
                    "queue_ms", queueMillis, "tree_read_ms", treeReadMillis,
                    "snapshot_origin", request.snapshotOrigin,
                    "snapshot_truncated", request.snapshotTruncated,
                    "event_received_elapsed_ms", request.startedAt,
                    "capture_finished_elapsed_ms", request.captureFinishedAt,
                    "event_age_at_receipt_ms", request.eventAgeAtReceiptMillis,
                    "diagnostic_writer_queue_ms", captureStore.lastWriteQueueMillis(),
                    "diagnostic_writer_append_ms", captureStore.lastWriteDurationMillis(),
                    "poll_interval_fast_ms", ForegroundPollPolicy.FAST_INTERVAL_MILLIS,
                    "event_time_uptime_ms", request.eventTimeUptimeMillis,
                    "request_window_id", request.windowId, "window_id", window.id,
                    "window_type", window.type, "window_layer", window.layer,
                    "window_active", window.active, "window_focused", window.focused,
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

    private boolean shouldRecordParserResult(OfferParseResult result, int windowId) {
        if (captureStore == null) return false;
        String session = captureStore.currentSessionId();
        if (session != null && !session.equals(lastCaptureSessionId)) {
            lastCaptureSessionId = session;
            lastLifecycleDiagnosticFingerprint = null;
            lastParserDiagnosticFingerprint = null;
            lastParserDiagnosticAt = 0;
        }
        String fingerprint = windowId + "|" + result.accepted() + "|" + result.rejectionReason() + "|"
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

    private WindowSnapshot findUberWindowRoots() {
        JSONArray metadata = new JSONArray();
        List<WindowRoot> uberRoots = new java.util.ArrayList<>();
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
                    if (UBER_PACKAGE.equals(pkg) && root != null) {
                        uberRoots.add(new WindowRoot(window.getId(), window.getType(), window.getLayer(),
                                window.isActive(), window.isFocused(), AccessibilityNodeInfo.obtain(root)));
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
        return new WindowSnapshot(uberRoots, metadata);
    }

    private static String packageName(AccessibilityNodeInfo root) {
        return root == null || root.getPackageName() == null ? "unknown" : root.getPackageName().toString();
    }

    private static String safeClass(CharSequence value) {
        if (value == null) return "unknown";
        return value.toString().replaceAll("[^A-Za-z0-9_.$]", "");
    }

    private static final class WindowRoot {
        final int id;
        final int type;
        final int layer;
        final boolean active;
        final boolean focused;
        AccessibilityNodeInfo root;

        WindowRoot(int id, int type, int layer, boolean active, boolean focused, AccessibilityNodeInfo root) {
            this.id = id;
            this.type = type;
            this.layer = layer;
            this.active = active;
            this.focused = focused;
            this.root = root;
        }

        void recycle() { if (root != null) { root.recycle(); root = null; } }
    }

    private static final class WindowSnapshot {
        final List<WindowRoot> roots;
        final JSONArray metadata;
        WindowSnapshot(List<WindowRoot> roots, JSONArray metadata) { this.roots = roots; this.metadata = metadata; }
        void recycle() { for (WindowRoot root : roots) root.recycle(); roots.clear(); }
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
