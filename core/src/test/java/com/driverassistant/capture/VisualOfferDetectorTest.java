package com.driverassistant.capture;

import org.junit.Test;
import com.driverassistant.parser.OfferParser;

import java.util.List;

import static org.junit.Assert.*;

public final class VisualOfferDetectorTest {
    private final VisualOfferDetector detector = new VisualOfferDetector();

    @Test public void groupsOfferEvidenceByRelativeGeometry() {
        VisualOfferDetector.Detection result = detector.detect(List.of(
                line("UberX", 30, 100, 190, 140, 0),
                line("R$ 29,09", 30, 155, 260, 200, 1),
                line("★ 5,00 (19)", 30, 215, 340, 260, 2),
                line("7 min (0,8 km)", 30, 275, 360, 320, 3),
                line("33 minutos (10,8 km)", 30, 335, 470, 380, 4),
                line("Aceitar", 30, 400, 180, 445, 5)), 1080, 2400);

        assertTrue(result.hasCandidate());
        assertEquals(6, result.candidate().lines().size());
        assertTrue(result.candidate().evidence().offerLike());
        assertTrue(result.candidate().bounds().bottom() >= 445);
        assertEquals(6, result.candidate().toParserNodes().size());
    }

    @Test public void mapBoostAndOfflineTextAloneAreNotOfferEvidence() {
        VisualOfferDetector.Detection result = detector.detect(List.of(
                line("+R$ 10", 50, 100, 220, 140, 0),
                line("+R$ 3,50", 270, 160, 460, 200, 1),
                line("Ficar online", 50, 2200, 360, 2240, 2),
                line("Oportunidades", 40, 2280, 350, 2320, 3)), 1080, 2400);

        assertFalse(result.hasCandidate());
        assertEquals("ocr_no_offer_evidence", result.rejectionReason());
    }

    @Test public void bonusesAndMissionAmountDoNotCreateCandidateWithoutRideSignals() {
        VisualOfferDetector.Detection result = detector.detect(List.of(
                line("Ganhe R$ 250,00", 60, 100, 470, 145, 0),
                line("Bônus por completar viagens", 60, 160, 610, 205, 1),
                line("+R$ 8,50 incluído", 60, 220, 420, 265, 2)), 1080, 2400);

        assertFalse(result.hasCandidate());
        assertEquals("ocr_money_without_offer_context", result.rejectionReason());
    }

    @Test public void incompleteOfferSignalsMayBeCollectedButNeedFareAndAcceptanceEvidence() {
        VisualOfferDetector.Detection result = detector.detect(List.of(
                line("Comfort", 30, 100, 240, 145, 0),
                line("7 min (0,8 km)", 30, 160, 360, 205, 1),
                line("33 min (10,8 km)", 30, 220, 400, 265, 2)), 1080, 2400);

        assertFalse(result.hasCandidate());
        assertEquals("ocr_no_offer_evidence", result.rejectionReason());
    }

    @Test public void visualCandidateUsesTheExistingStrictOfferParser() {
        VisualOfferDetector.Detection visual = detector.detect(List.of(
                line("UberX", 30, 100, 190, 140, 0),
                line("R$ 29,09", 30, 155, 260, 200, 1),
                line("★ 5,00 (19)", 30, 215, 340, 260, 2),
                line("7 min (0,8 km)", 30, 275, 360, 320, 3),
                line("33 minutos (10,8 km)", 30, 335, 470, 380, 4),
                line("Aceitar", 30, 400, 180, 445, 5)), 1080, 2400);

        var result = new OfferParser().parseNodes(visual.candidate().toParserNodes(), true);

        assertTrue(result.accepted());
        assertEquals(Double.valueOf(29.09), result.offer().offeredAmount());
        assertEquals(Double.valueOf(5.0), result.offer().passengerRating());
        assertEquals(Double.valueOf(0.8), result.offer().pickupDistanceKm());
        assertEquals(Double.valueOf(10.8), result.offer().tripDistanceKm());
        assertEquals(Double.valueOf(7.0), result.offer().pickupEtaMinutes());
        assertEquals(Double.valueOf(33.0), result.offer().tripDurationMinutes());
    }

    private static VisualTextLine line(String text, int left, int top, int right, int bottom, int order) {
        return new VisualTextLine(text, left, top, right, bottom, order);
    }
}
