package com.driverassistant.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Aggregates sanitized scalar diagnostic events without Android or JSON dependencies. */
public final class DiagnosticSessionSummary {
    private final Set<Long> recognizedOffers = new HashSet<>();
    private final Set<Long> presentedCards = new HashSet<>();
    private final Set<String> countedConflictCaptures = new HashSet<>();
    private final Map<Long, Long> offerDetectedAt = new HashMap<>();
    private final Map<Long, Boolean> incompleteByOffer = new HashMap<>();
    private final List<Long> displayLatencies = new ArrayList<>();
    private final List<Long> visibleDurations = new ArrayList<>();
    private final List<Long> ratingLatencies = new ArrayList<>();
    private final List<Long> parserTimes = new ArrayList<>();
    private final List<Long> ocrInferenceTimes = new ArrayList<>();
    private final List<Long> ocrTotalTimes = new ArrayList<>();
    private final List<Long> pollTimes = new ArrayList<>();
    private final List<Long> offerToOverlayTimes = new ArrayList<>();
    private final Map<String, Long> sourceCounts = new TreeMap<>();
    private final Map<String, Long> removalReasons = new TreeMap<>();
    private final Map<String, Long> parserRejections = new TreeMap<>();
    private final Map<String, Long> failuresByKind = new TreeMap<>();
    private final Map<String, Long> discardedByKind = new TreeMap<>();
    private final Map<String, Long> serviceEvents = new TreeMap<>();
    private final Map<String, String> sessionContext = new LinkedHashMap<>();
    private long durationMillis;
    private long candidateRegionsRejected;
    private long fieldDivergences;
    private long sourceIdentityRejections;
    private long writerQueueDroppedRecords;
    private String lastWriterDroppedKind;
    private long memorySamples;
    private long pssTotalKb;
    private long pssMaxKb;
    private long pssLastKb;
    private long javaHeapMaxBytes;
    private long javaHeapLastBytes;
    private long nativeHeapMaxBytes;
    private long nativeHeapLastBytes;
    private String stopReason;

    public void accept(String kind, long elapsedMillis, Map<String, String> details) {
        if (kind == null) return;
        Map<String, String> values = details == null ? Map.of() : details;
        durationMillis = Math.max(durationMillis, Math.max(0, elapsedMillis));
        switch (kind) {
            case "SESSION_CONTEXT" -> values.forEach((key, value) -> sessionContext.put(key, value));
            case "SESSION_STOPPED", "SESSION_AUTO_STOPPED" -> {
                stopReason = values.get("reason");
                writerQueueDroppedRecords = Math.max(writerQueueDroppedRecords,
                        number(values, "writer_queue_dropped_records", 0));
                lastWriterDroppedKind = values.get("last_writer_queue_dropped_kind");
            }
            case "OFFER_RECOGNIZED" -> acceptOffer(values, elapsedMillis);
            case "OFFER_CLASSIFICATION" -> acceptClassification(values);
            case "OFFER_FIELD_ENRICHED" -> acceptEnrichment(values, elapsedMillis);
            case "OFFER_FIELD_DIVERGENCE", "OFFER_CAPTURE_COMPARISON" -> {
                String captureId = values.get("capture_id");
                if (captureId == null || countedConflictCaptures.add(captureId)) {
                    fieldDivergences += number(values, "field_conflicts_count", 0);
                }
            }
            case "OFFER_SOURCE_DIVERGENCE" -> sourceIdentityRejections++;
            case "OVERLAY_SHOWN" -> acceptOverlayShown(values, elapsedMillis);
            case "OVERLAY_REMOVED", "OVERLAY_EXPIRED", "OVERLAY_REPLACED" -> acceptOverlayRemoved(kind, values);
            case "OCR_OFFER_DETECTION" -> acceptCandidateDetection(values);
            case "PARSER_RESULT" -> acceptParserResult(values);
            case "PROCESSING_EXCEPTION", "POLL_EXCEPTION", "OCR_RECOGNITION_FAILED",
                    "OCR_CAPTURE_FAILED", "OVERLAY_ERROR", "WINDOW_METADATA_ERROR" -> increment(failuresByKind, kind);
            case "OCR_RESULT_DISCARDED", "SNAPSHOT_QUEUE_RESULT", "EVENT_QUEUE_OVERFLOW",
                    "TREE_NOT_CAPTURED" -> acceptDiscard(kind, values);
            case "OCR_RECOGNITION" -> addTime(ocrInferenceTimes, number(values, "processing_ms", -1));
            case "OCR_PROCESSING_COMPLETE" -> addTime(ocrTotalTimes, number(values, "total_ms", -1));
            case "POLL_CYCLE" -> addTime(pollTimes, number(values, "cycle_ms", -1));
            case "OFFER_TO_OVERLAY_REQUEST" -> addTime(offerToOverlayTimes,
                    number(values, "event_to_overlay_request_ms", -1));
            case "RUNTIME_HEALTH" -> acceptRuntimeHealth(values);
            case "SERVICE_CONNECTED", "SERVICE_INTERRUPTED", "SERVICE_RESUMED_AFTER_INTERRUPT",
                    "SERVICE_DISCONNECTED" -> increment(serviceEvents, kind);
            default -> { }
        }
    }

    private void acceptOffer(Map<String, String> values, long elapsedMillis) {
        long id = offerId(values);
        if (id < 0) return;
        if (recognizedOffers.add(id)) {
            offerDetectedAt.put(id, Math.max(0, elapsedMillis));
            increment(sourceCounts, values.getOrDefault("source", "unknown"));
        }
        if (Boolean.parseBoolean(values.getOrDefault("initially_incomplete", "false"))) {
            incompleteByOffer.put(id, true);
        } else {
            incompleteByOffer.putIfAbsent(id, false);
        }
    }

    private void acceptClassification(Map<String, String> values) {
        long id = offerId(values);
        if (id < 0) return;
        incompleteByOffer.put(id, Boolean.parseBoolean(values.getOrDefault("incomplete", "true")));
    }

    private void acceptEnrichment(Map<String, String> values, long elapsedMillis) {
        if (!"RATING".equals(values.get("field"))) return;
        long id = offerId(values);
        Long detectedAt = offerDetectedAt.get(id);
        if (detectedAt != null) ratingLatencies.add(Math.max(0, elapsedMillis - detectedAt));
    }

    private void acceptOverlayShown(Map<String, String> values, long elapsedMillis) {
        if (Boolean.parseBoolean(values.getOrDefault("simulated", "false"))) return;
        long id = offerId(values);
        if (id < 0 || !presentedCards.add(id)) return;
        Long detectedAt = offerDetectedAt.get(id);
        if (detectedAt != null) displayLatencies.add(Math.max(0, elapsedMillis - detectedAt));
    }

    private void acceptOverlayRemoved(String kind, Map<String, String> values) {
        String reason = values.getOrDefault("reason", kind.toLowerCase());
        increment(removalReasons, reason);
        long duration = number(values, "visible_duration_ms", -1);
        if (duration >= 0) visibleDurations.add(duration);
    }

    private void acceptCandidateDetection(Map<String, String> values) {
        long candidates = number(values, "candidate_region_count", 0);
        if (candidates > 0 && !Boolean.parseBoolean(values.getOrDefault("candidate_found", "false"))) {
            candidateRegionsRejected += candidates;
        }
        if ("true".equals(values.get("candidate_found"))) return;
        String reason = values.get("rejection_reason");
        if (reason != null && !reason.isBlank()) increment(parserRejections, reason);
    }

    private void acceptParserResult(Map<String, String> values) {
        addTime(parserTimes, number(values, "processing_ms", -1));
        if (!Boolean.parseBoolean(values.getOrDefault("accepted", "false"))) {
            increment(parserRejections, values.getOrDefault("rejection_reason", "unknown"));
        }
    }

    private void acceptDiscard(String kind, Map<String, String> values) {
        if ("SNAPSHOT_QUEUE_RESULT".equals(kind) && Boolean.parseBoolean(values.getOrDefault("accepted", "true"))) return;
        increment(discardedByKind, kind);
    }

    private void acceptRuntimeHealth(Map<String, String> values) {
        long pss = number(values, "pss_kb", -1);
        if (pss >= 0) {
            memorySamples++;
            pssTotalKb += pss;
            pssMaxKb = Math.max(pssMaxKb, pss);
            pssLastKb = pss;
        }
        javaHeapLastBytes = number(values, "java_heap_used_bytes", javaHeapLastBytes);
        javaHeapMaxBytes = Math.max(javaHeapMaxBytes, javaHeapLastBytes);
        nativeHeapLastBytes = number(values, "native_heap_allocated_bytes", nativeHeapLastBytes);
        nativeHeapMaxBytes = Math.max(nativeHeapMaxBytes, nativeHeapLastBytes);
    }

    public Map<String, Object> toMap() {
        long complete = incompleteByOffer.values().stream().filter(value -> !value).count();
        long incomplete = incompleteByOffer.values().stream().filter(Boolean::booleanValue).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("session_duration_ms", durationMillis);
        result.put("stop_reason", stopReason);
        result.put("session_context", new LinkedHashMap<>(sessionContext));
        result.put("offers", Map.of(
                "unique_recognized", recognizedOffers.size(),
                "complete", complete,
                "incomplete", incomplete,
                "possible_candidate_regions_rejected", candidateRegionsRejected,
                "parser_rejections_by_reason", new TreeMap<>(parserRejections)));
        result.put("cards", Map.of(
                "presented", presentedCards.size(),
                "average_initial_display_ms", average(displayLatencies),
                "visible_duration_ms", stats(visibleDurations),
                "removal_reasons", new TreeMap<>(removalReasons)));
        result.put("fields", Map.of(
                "rating_enriched_count", ratingLatencies.size(),
                "average_time_to_rating_ms", average(ratingLatencies)));
        result.put("divergences", Map.of(
                "field_conflicts", fieldDivergences,
                "incompatible_source_pairs", sourceIdentityRejections));
        result.put("recognition_sources", new TreeMap<>(sourceCounts));
        result.put("processing", Map.of(
                "failures_by_kind", new TreeMap<>(failuresByKind),
                "discarded_captures_by_kind", new TreeMap<>(discardedByKind),
                "writer_queue_dropped_records", writerQueueDroppedRecords,
                "last_writer_queue_dropped_kind", lastWriterDroppedKind == null ? "none" : lastWriterDroppedKind,
                "parser_ms", stats(parserTimes),
                "ocr_inference_ms", stats(ocrInferenceTimes),
                "ocr_total_ms", stats(ocrTotalTimes),
                "poll_cycle_ms", stats(pollTimes),
                "offer_to_overlay_request_ms", stats(offerToOverlayTimes)));
        result.put("runtime", Map.of(
                "pss_kb", Map.of("samples", memorySamples, "average", memorySamples == 0 ? 0 : pssTotalKb / memorySamples,
                        "maximum", pssMaxKb, "last", pssLastKb),
                "java_heap_used_bytes", Map.of("maximum", javaHeapMaxBytes, "last", javaHeapLastBytes),
                "native_heap_allocated_bytes", Map.of("maximum", nativeHeapMaxBytes, "last", nativeHeapLastBytes)));
        result.put("service_lifecycle", new TreeMap<>(serviceEvents));
        return result;
    }

    private static long offerId(Map<String, String> values) {
        return number(values, "offer_session_id", number(values, "session_id", -1));
    }

    private static long number(Map<String, String> values, String key, long fallback) {
        try { return Long.parseLong(values.get(key)); }
        catch (NumberFormatException | NullPointerException ignored) { return fallback; }
    }

    private static void increment(Map<String, Long> values, String key) {
        values.merge(key == null || key.isBlank() ? "unknown" : key, 1L, Long::sum);
    }

    private static long average(List<Long> values) {
        return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static Map<String, Object> stats(List<Long> values) {
        if (values.isEmpty()) return Map.of("count", 0, "average", 0, "p95", 0, "maximum", 0);
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int p95Index = (int) Math.ceil(sorted.length * 0.95) - 1;
        long total = 0;
        for (long value : sorted) total += value;
        return Map.of("count", sorted.length, "average", total / sorted.length,
                "p95", sorted[Math.max(0, p95Index)], "maximum", sorted[sorted.length - 1]);
    }

    private static void addTime(List<Long> values, long value) {
        if (value >= 0) values.add(value);
    }
}
