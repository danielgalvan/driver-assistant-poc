package com.driverassistant.diagnostics;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DiagnosticSessionSummaryTest {
    @Test public void aggregatesOfferCardLatencyFieldEnrichmentAndProcessingStats() {
        DiagnosticSessionSummary summary = new DiagnosticSessionSummary();
        summary.accept("SESSION_CONTEXT", 0, Map.of("capture_strategy", "HYBRID"));
        summary.accept("SERVICE_CONNECTED", 10, Map.of());
        summary.accept("SERVICE_INTERRUPTED", 11, Map.of());
        summary.accept("SERVICE_RESUMED_AFTER_INTERRUPT", 12, Map.of());
        summary.accept("OFFER_RECOGNIZED", 1_000,
                Map.of("offer_session_id", "12", "source", "accessibility", "initially_incomplete", "true"));
        summary.accept("PARSER_RESULT", 1_050,
                Map.of("accepted", "true", "processing_ms", "8"));
        summary.accept("PARSER_RESULT", 1_100,
                Map.of("accepted", "false", "rejection_reason", "map_context", "processing_ms", "12"));
        summary.accept("OFFER_CLASSIFICATION", 1_200,
                Map.of("offer_session_id", "12", "incomplete", "false"));
        summary.accept("OFFER_FIELD_ENRICHED", 1_600,
                Map.of("offer_session_id", "12", "field", "RATING"));
        summary.accept("OFFER_TO_OVERLAY_REQUEST", 1_250,
                Map.of("event_to_overlay_request_ms", "25"));
        summary.accept("OVERLAY_SHOWN", 1_300,
                Map.of("offer_session_id", "12", "simulated", "false"));
        summary.accept("OVERLAY_REMOVED", 9_300,
                Map.of("reason", "offer_disappeared", "visible_duration_ms", "8000"));
        summary.accept("POLL_CYCLE", 1_400, Map.of("cycle_ms", "30"));
        summary.accept("POLL_CYCLE", 1_500, Map.of("cycle_ms", "40"));
        summary.accept("SESSION_STOPPED", 10_000,
                Map.of("reason", "manual", "writer_queue_dropped_records", "2",
                        "last_writer_queue_dropped_kind", "POLL_CYCLE"));

        Map<?, ?> root = (Map<?, ?>) summary.toMap();
        Map<?, ?> offers = (Map<?, ?>) root.get("offers");
        Map<?, ?> cards = (Map<?, ?>) root.get("cards");
        Map<?, ?> fields = (Map<?, ?>) root.get("fields");
        Map<?, ?> processing = (Map<?, ?>) root.get("processing");
        Map<?, ?> serviceEvents = (Map<?, ?>) root.get("service_lifecycle");
        Map<?, ?> pollStats = (Map<?, ?>) processing.get("poll_cycle_ms");

        assertEquals(1, offers.get("unique_recognized"));
        assertEquals(1L, offers.get("complete"));
        assertEquals(1, cards.get("presented"));
        assertEquals(300L, cards.get("average_initial_display_ms"));
        assertEquals(1, fields.get("rating_enriched_count"));
        assertEquals(600L, fields.get("average_time_to_rating_ms"));
        assertEquals(40L, pollStats.get("p95"));
        assertEquals(2L, processing.get("writer_queue_dropped_records"));
        assertEquals("manual", root.get("stop_reason"));
        assertEquals(1L, serviceEvents.get("SERVICE_INTERRUPTED"));
        assertEquals(1L, serviceEvents.get("SERVICE_RESUMED_AFTER_INTERRUPT"));
    }

    @Test public void candidateAndParserRejectionsAreSeparatedFromAcceptedOffers() {
        DiagnosticSessionSummary summary = new DiagnosticSessionSummary();
        summary.accept("OCR_OFFER_DETECTION", 500,
                Map.of("candidate_found", "false", "candidate_region_count", "2", "rejection_reason", "no_offer_card"));
        summary.accept("PARSER_RESULT", 600,
                Map.of("accepted", "false", "rejection_reason", "map_context", "processing_ms", "4"));
        summary.accept("OFFER_SOURCE_DIVERGENCE", 700, Map.of());

        Map<?, ?> offers = (Map<?, ?>) ((Map<?, ?>) summary.toMap()).get("offers");
        Map<?, ?> divergences = (Map<?, ?>) ((Map<?, ?>) summary.toMap()).get("divergences");
        assertEquals(0, offers.get("unique_recognized"));
        assertEquals(2L, offers.get("possible_candidate_regions_rejected"));
        assertTrue(((Map<?, ?>) offers.get("parser_rejections_by_reason")).containsKey("map_context"));
        assertEquals(1L, divergences.get("incompatible_source_pairs"));
    }

    @Test public void doesNotCountTheSameSourceFieldConflictTwice() {
        DiagnosticSessionSummary summary = new DiagnosticSessionSummary();
        summary.accept("OFFER_CAPTURE_COMPARISON", 100,
                Map.of("capture_id", "43", "field_conflicts_count", "2"));
        summary.accept("OFFER_FIELD_DIVERGENCE", 101,
                Map.of("capture_id", "43", "field_conflicts_count", "2"));

        Map<?, ?> divergences = (Map<?, ?>) ((Map<?, ?>) summary.toMap()).get("divergences");
        assertEquals(2L, divergences.get("field_conflicts"));
    }
}
