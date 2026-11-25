package com.driverassistant.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.driverassistant.domain.RideMetrics;
import com.driverassistant.domain.TripOffer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Logs estruturados e redigidos, mantidos localmente para diagnóstico da POC. */
public final class DiagnosticLogStore {
    private static final String PREFS = "driver_assistant_diagnostics";
    private static final String EVENTS = "events";
    private static final String OFFER_COUNT = "offer_count";
    private static final String LAST_OFFER = "last_offer";
    private static final String UBER_DETECTED = "uber_detected";
    private static final int MAX_EVENTS = 30;

    private DiagnosticLogStore() { }

    public static void record(Context context, String tag, String code, String detail) {
        String safeTag = knownTag(tag) ? tag : "DA_ACCESSIBILITY";
        String line = timestamp() + " | " + code + (detail == null || detail.trim().isEmpty() ? "" : " | " + detail);
        Log.i(safeTag, code + (detail == null || detail.trim().isEmpty() ? "" : " | " + detail));
        List<String> events = events(context);
        events.add(0, line);
        if (events.size() > MAX_EVENTS) events = new ArrayList<>(events.subList(0, MAX_EVENTS));
        prefs(context).edit().putString(EVENTS, String.join("\n", events)).apply();
    }

    public static void recordOffer(Context context, TripOffer offer, RideMetrics metrics) {
        SharedPreferences preferences = prefs(context);
        int count = preferences.getInt(OFFER_COUNT, 0) + 1;
        String summary = "Valor " + money(offer == null ? null : offer.offeredAmount())
                + ", ganho/km " + money(metrics == null ? null : metrics.earningsPerKm())
                + ", ganho/h " + money(metrics == null ? null : metrics.earningsPerHour());
        preferences.edit().putInt(OFFER_COUNT, count).putString(LAST_OFFER, summary).apply();
        record(context, "DA_OFFER", "OFFER_PARSED", "count=" + count + "; fields=" + availableFieldCount(offer));
    }

    public static int offerCount(Context context) { return prefs(context).getInt(OFFER_COUNT, 0); }
    public static String lastOffer(Context context) { return prefs(context).getString(LAST_OFFER, null); }
    public static void setUberDetected(Context context, boolean detected) {
        prefs(context).edit().putBoolean(UBER_DETECTED, detected).apply();
    }
    public static boolean uberDetected(Context context) {
        return prefs(context).getBoolean(UBER_DETECTED, false);
    }

    public static List<String> events(Context context) {
        String stored = prefs(context).getString(EVENTS, "");
        List<String> result = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) if (!line.trim().isEmpty()) result.add(line);
        return result;
    }

    public static String exportText(Context context) {
        StringBuilder output = new StringBuilder("Driver Assistant — diagnóstico local\n")
                .append("Ofertas reais detectadas: ").append(offerCount(context)).append('\n')
                .append("Última oferta: ").append(lastOffer(context) == null ? "nenhuma" : lastOffer(context)).append('\n')
                .append("\nEventos recentes (sem textos de tela, endereços ou nomes):\n");
        for (String event : events(context)) output.append(event).append('\n');
        return output.toString();
    }

    private static int availableFieldCount(TripOffer offer) {
        if (offer == null) return 0;
        int count = 0;
        if (offer.offeredAmount() != null) count++;
        if (offer.category() != null) count++;
        if (offer.passengerRating() != null) count++;
        if (offer.pickupDistanceKm() != null) count++;
        if (offer.pickupEtaMinutes() != null) count++;
        if (offer.tripDistanceKm() != null) count++;
        if (offer.tripDurationMinutes() != null) count++;
        if (offer.pickupLabel() != null) count++;
        if (offer.destinationLabel() != null) count++;
        return count;
    }

    private static String money(Double amount) {
        return amount == null || !Double.isFinite(amount) ? "indisponível"
                : String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", amount);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean knownTag(String tag) {
        return "DA_ACCESSIBILITY".equals(tag) || "DA_PARSER".equals(tag) || "DA_OFFER".equals(tag)
                || "DA_CALCULATOR".equals(tag) || "DA_OVERLAY".equals(tag) || "DA_CONFIG".equals(tag);
    }
}
