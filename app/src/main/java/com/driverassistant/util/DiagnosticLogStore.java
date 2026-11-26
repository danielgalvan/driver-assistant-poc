package com.driverassistant.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.driverassistant.diagnostics.BoundedEventBuffer;
import com.driverassistant.diagnostics.EventRateLimiter;
import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.TripOffer;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;
import com.driverassistant.parser.OfferParseResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Redacted local diagnostics with an in-memory ring buffer and batched disk writes. */
public final class DiagnosticLogStore {
    private static final String PREFS = "driver_assistant_diagnostics";
    private static final String EVENTS = "events";
    private static final String OFFER_COUNT = "offer_count";
    private static final String INCOMPLETE_COUNT = "incomplete_offer_count";
    private static final String LAST_OFFER = "last_offer";
    private static final String UBER_DETECTED = "uber_detected";
    private static final String LAST_UBER_EVENT = "last_uber_event";
    private static final int MAX_EVENTS = 200;
    private static final long PERSIST_DELAY_MILLIS = 500;
    private static final Object LOCK = new Object();
    private static final BoundedEventBuffer BUFFER = new BoundedEventBuffer(MAX_EVENTS);
    private static final EventRateLimiter RATE_LIMITER = new EventRateLimiter(1_000, 160);
    private static final HandlerThread WORKER_THREAD = startWorker();
    private static final Handler WORKER = new Handler(WORKER_THREAD.getLooper());
    private static boolean initialized;
    private static SharedPreferences preferences;
    private static int offerCount;
    private static int incompleteOfferCount;
    private static String lastOffer;
    private static String lastUberEvent;
    private static String lastEvent;
    private static boolean uberPreviouslyDetected;
    private static Runnable persistTask;

    private DiagnosticLogStore() { }

    public static void record(Context context, String tag, String code, String detail) {
        initialize(context);
        String safeTag = knownTag(tag) ? tag : "DA_ACCESSIBILITY";
        String safeDetail = detail == null ? "" : detail.trim();
        String rateKey = safeTag + "|" + code + ("OFFER_PROCESSING_TIME".equals(code) ? "" : "|" + safeDetail);
        long now = System.currentTimeMillis();
        if (!RATE_LIMITER.allow(rateKey, now)) return;
        String category = "DA_PARSER".equals(safeTag) || "DA_OFFER".equals(safeTag)
                || "DA_CALCULATOR".equals(safeTag) ? "NEGOCIO" : "TÉCNICO";
        String line = timestamp(now) + " | " + category + " | " + code
                + (safeDetail.isEmpty() ? "" : " | " + safeDetail);
        Log.i(safeTag, code + (safeDetail.isEmpty() ? "" : " | " + safeDetail));
        synchronized (LOCK) {
            BUFFER.add(line);
            lastEvent = line;
            schedulePersistLocked();
        }
    }

    public static void recordOffer(Context context, TripOffer offer, RideMetrics metrics, boolean incomplete) {
        initialize(context);
        int count;
        synchronized (LOCK) {
            offerCount++;
            if (incomplete) incompleteOfferCount++;
            count = offerCount;
            lastOffer = offerSummary(offer, metrics);
            schedulePersistLocked();
        }
        record(context, "DA_OFFER", "OFFER_UNIQUE", "count=" + count + "; incomplete=" + incomplete);
    }

    public static void updateLastOffer(Context context, TripOffer offer, RideMetrics metrics) {
        initialize(context);
        synchronized (LOCK) {
            lastOffer = offerSummary(offer, metrics);
            schedulePersistLocked();
        }
        record(context, "DA_OFFER", "OFFER_FIELDS_UPDATED", "summary_refreshed=true");
    }

    public static void recordParserResult(Context context, OfferParseResult result) {
        if (result == null) return;
        if (result.accepted()) {
            record(context, "DA_PARSER", "FIELDS_PARSED", "found=" + String.join(",", result.foundFields())
                    + "; unavailable=" + String.join(",", result.unavailableFields()));
        } else {
            record(context, "DA_PARSER", "CANDIDATE_REJECTED", "reason=" + result.rejectionReason()
                    + "; unavailable=" + String.join(",", result.unavailableFields()));
        }
    }

    public static void recordProcessingTime(Context context, long durationMillis) {
        record(context, "DA_ACCESSIBILITY", "OFFER_PROCESSING_TIME", "ms=" + Math.max(0, durationMillis));
    }

    public static void recordStructure(Context context, List<String> structure) {
        if (structure == null || structure.isEmpty()) return;
        String detail = String.join(";", structure.subList(0, Math.min(60, structure.size())));
        if (detail.length() > 2_500) detail = detail.substring(0, 2_500);
        record(context, "DA_ACCESSIBILITY", "TREE_STRUCTURE", detail);
    }

    public static int offerCount(Context context) { initialize(context); synchronized (LOCK) { return offerCount; } }
    public static int incompleteOfferCount(Context context) { initialize(context); synchronized (LOCK) { return incompleteOfferCount; } }
    public static String lastOffer(Context context) { initialize(context); synchronized (LOCK) { return lastOffer; } }
    public static String lastEvent(Context context) { initialize(context); synchronized (LOCK) { return lastEvent; } }
    public static String lastUberEvent(Context context) { initialize(context); synchronized (LOCK) { return lastUberEvent; } }

    /** Historical flag: reports whether the Uber package has ever generated an event since clearing diagnostics. */
    public static boolean uberDetected(Context context) {
        initialize(context);
        synchronized (LOCK) { return uberPreviouslyDetected; }
    }

    public static void setUberDetected(Context context, boolean detected) {
        if (!detected) return;
        markUberEvent(context, "unknown");
    }

    public static void markUberEvent(Context context, String eventType) {
        initialize(context);
        synchronized (LOCK) {
            uberPreviouslyDetected = true;
            String safeType = eventType == null ? "unknown" : eventType.replaceAll("[^0-9A-Za-z_-]", "");
            lastUberEvent = timestamp(System.currentTimeMillis()) + " · tipo=" + safeType;
            schedulePersistLocked();
        }
    }

    public static List<String> events(Context context) {
        initialize(context);
        synchronized (LOCK) { return BUFFER.snapshot(); }
    }

    public static void clear(Context context) {
        initialize(context);
        synchronized (LOCK) {
            BUFFER.loadNewestFirst(List.of());
            offerCount = 0;
            incompleteOfferCount = 0;
            lastOffer = null;
            lastUberEvent = null;
            lastEvent = null;
            uberPreviouslyDetected = false;
            if (persistTask != null) WORKER.removeCallbacks(persistTask);
            persistTask = () -> {
                synchronized (LOCK) {
                    preferences.edit().clear().apply();
                    persistTask = null;
                }
            };
            WORKER.post(persistTask);
        }
    }

    public static String exportText(Context context) {
        initialize(context);
        StringBuilder output = new StringBuilder("Driver Assistant — diagnóstico local\n")
                .append("Ofertas únicas: ").append(offerCount(context)).append('\n')
                .append("Ofertas incompletas: ").append(incompleteOfferCount(context)).append('\n')
                .append("Uber visto anteriormente: ").append(uberDetected(context) ? "sim" : "não").append('\n')
                .append("Último evento Uber: ").append(lastUberEvent(context) == null ? "nenhum" : lastUberEvent(context)).append('\n')
                .append("Última oferta: ").append(lastOffer(context) == null ? "nenhuma" : lastOffer(context)).append('\n')
                .append("\nEventos recentes (sem texto bruto, endereços ou nomes):\n");
        for (String event : events(context)) output.append(event).append('\n');
        return output.toString();
    }

    private static String offerSummary(TripOffer offer, RideMetrics metrics) {
        if (offer == null) return "Oferta incompleta";
        List<String> fields = new ArrayList<>();
        fields.add("valor=" + money(offer.offeredAmount()));
        String category = DiagnosticTextSanitizer.sanitizeCategory(offer.category());
        if (category != null) fields.add("categoria=" + category);
        fields.add("nota=" + decimal(offer.passengerRating()));
        fields.add("embarque=" + distance(offer.pickupDistanceKm()) + "/" + duration(offer.pickupEtaMinutes()));
        fields.add("viagem=" + distance(offer.tripDistanceKm()) + "/" + duration(offer.tripDurationMinutes()));
        fields.add("total=" + distance(metrics == null ? null : metrics.totalDistanceKm())
                + "/" + duration(metrics == null ? null : metrics.totalDurationMinutes()));
        return String.join("; ", fields);
    }

    private static String money(Double value) {
        return value == null || !Double.isFinite(value) ? "indisponível"
                : String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", value);
    }
    private static String decimal(Double value) {
        return value == null || !Double.isFinite(value) ? "indisponível"
                : String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value);
    }
    private static String distance(Double value) {
        return value == null || !Double.isFinite(value) ? "indisponível"
                : String.format(Locale.forLanguageTag("pt-BR"), "%.2f km", value);
    }
    private static String duration(Double value) {
        return value == null || !Double.isFinite(value) ? "indisponível"
                : String.format(Locale.forLanguageTag("pt-BR"), "%.0f min", value);
    }

    private static void initialize(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (initialized) return;
            preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String stored = preferences.getString(EVENTS, "");
            List<String> values = new ArrayList<>();
            if (stored != null && !stored.isBlank()) {
                for (String line : stored.split("\\n")) if (!line.isBlank()) values.add(line);
            }
            BUFFER.loadNewestFirst(values);
            offerCount = preferences.getInt(OFFER_COUNT, 0);
            incompleteOfferCount = preferences.getInt(INCOMPLETE_COUNT, 0);
            lastOffer = preferences.getString(LAST_OFFER, null);
            lastUberEvent = preferences.getString(LAST_UBER_EVENT, null);
            lastEvent = BUFFER.snapshot().isEmpty() ? null : BUFFER.snapshot().get(0);
            uberPreviouslyDetected = preferences.getBoolean(UBER_DETECTED, false);
            initialized = true;
        }
    }

    private static void schedulePersistLocked() {
        if (persistTask != null) return;
        persistTask = () -> {
            synchronized (LOCK) {
                preferences.edit()
                        .putString(EVENTS, String.join("\n", BUFFER.snapshot()))
                        .putInt(OFFER_COUNT, offerCount)
                        .putInt(INCOMPLETE_COUNT, incompleteOfferCount)
                        .putString(LAST_OFFER, lastOffer)
                        .putString(LAST_UBER_EVENT, lastUberEvent)
                        .putBoolean(UBER_DETECTED, uberPreviouslyDetected)
                        .apply();
                persistTask = null;
            }
        };
        WORKER.postDelayed(persistTask, PERSIST_DELAY_MILLIS);
    }

    private static HandlerThread startWorker() {
        HandlerThread thread = new HandlerThread("driver-diagnostics");
        thread.start();
        return thread;
    }

    private static String timestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(millis));
    }

    private static boolean knownTag(String tag) {
        return "DA_ACCESSIBILITY".equals(tag) || "DA_PARSER".equals(tag) || "DA_OFFER".equals(tag)
                || "DA_CALCULATOR".equals(tag) || "DA_OVERLAY".equals(tag) || "DA_CONFIG".equals(tag);
    }
}
