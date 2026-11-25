package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** All fixtures are simulated text; none asserts a production Uber accessibility layout. */
public final class OfferParserTest {
    private final OfferParser parser = new OfferParser();

    @Test public void parsesSimulatedOfferFieldsFromOneLine() {
        TripOffer offer = parser.parse(List.of(
                "Simulação: UberX · Oferta R$ 22,00 · 1,2 km até o embarque · 5 min",
                "7,4 km de viagem · 18 min · Nota: 4,95")).orElseThrow();
        assertEquals(22.0, offer.offeredAmount(), 0.0001);
        assertEquals("UberX", offer.category());
        assertEquals(1.2, offer.pickupDistanceKm(), 0.0001);
        assertEquals(5.0, offer.pickupEtaMinutes(), 0.0001);
        assertEquals(7.4, offer.tripDistanceKm(), 0.0001);
        assertEquals(18.0, offer.tripDurationMinutes(), 0.0001);
        assertEquals(4.95, offer.passengerRating(), 0.0001);
    }

    @Test public void parsesSplitSimulatedAccessibilityNodesAndHierarchyMetadata() {
        List<AccessibilityNode> simulatedTree = List.of(
                new AccessibilityNode("Comfort", null, "offer/category", "TextView", 0, 1, 0),
                new AccessibilityNode("Oferta", null, "offer/title", "TextView", 0, 1, 1),
                new AccessibilityNode("R$", null, "offer/fare_currency", "TextView", 0, 1, 2),
                new AccessibilityNode("27,98", null, "offer/fare_value", "TextView", 0, 1, 3),
                new AccessibilityNode("Até o embarque", null, "offer/pickup_label", "TextView", 0, 1, 4),
                new AccessibilityNode("750", null, "offer/pickup_distance_value", "TextView", 0, 1, 5),
                new AccessibilityNode("m", null, "offer/pickup_distance_unit", "TextView", 0, 1, 6),
                new AccessibilityNode("5", null, "offer/pickup_time_value", "TextView", 0, 1, 7),
                new AccessibilityNode("min", null, "offer/pickup_time_unit", "TextView", 0, 1, 8),
                new AccessibilityNode("Distância da viagem", null, "offer/trip_label", "TextView", 0, 1, 9),
                new AccessibilityNode("0,750", null, "offer/trip_distance_value", "TextView", 0, 1, 10),
                new AccessibilityNode("km", null, "offer/trip_distance_unit", "TextView", 0, 1, 11),
                new AccessibilityNode("1 h 20 min", null, "offer/trip_time", "TextView", 0, 1, 12),
                new AccessibilityNode("4,90", null, "offer/rating", "TextView", 0, 1, 13));

        OfferParseResult parsed = parser.parseNodes(simulatedTree);
        assertTrue(parsed.accepted());
        assertEquals(27.98, parsed.offer().offeredAmount(), 0.0001);
        assertEquals("Comfort", parsed.offer().category());
        assertEquals(0.75, parsed.offer().pickupDistanceKm(), 0.0001);
        assertEquals(5.0, parsed.offer().pickupEtaMinutes(), 0.0001);
        assertEquals(0.75, parsed.offer().tripDistanceKm(), 0.0001);
        assertEquals(80.0, parsed.offer().tripDurationMinutes(), 0.0001);
        assertEquals(4.90, parsed.offer().passengerRating(), 0.0001); // Resource ID supplies rating context.
    }

    @Test public void choosesOfferFareInsteadOfAnEarlierUnrelatedMoneyValue() {
        TripOffer offer = parser.parse(List.of("UberX", "Estimativa anterior R$ 8,00", "", "info", "painel", "corrida",
                "Oferta selecionável",
                "Valor da corrida R$ 27,98", "5 km de viagem · 20 min")).orElseThrow();
        assertEquals(27.98, offer.offeredAmount(), 0.0001);
    }

    @Test public void doesNotTreatSurgeOrPromotionAmountsAsTheOfferFare() {
        TripOffer offer = parser.parse(List.of("UberX", "Oferta da viagem", "R$ 22,00",
                "Preço dinâmico R$ 60,00", "Promoção semanal R$ 100,00")).orElseThrow();
        assertEquals(22.0, offer.offeredAmount(), 0.0001);
    }

    @Test public void keepsUnavailableFieldsNullAndDoesNotInventTravelContext() {
        TripOffer offer = parser.parse(List.of("Oferta UberX", "R$ 18,00", "25 min", "12,9 km")).orElseThrow();
        assertNull(offer.pickupDistanceKm());
        assertNull(offer.pickupEtaMinutes());
        assertNull(offer.tripDistanceKm());
        assertNull(offer.tripDurationMinutes());
        assertNull(offer.passengerRating());
    }

    @Test public void parsesMeterDistanceAndCompoundHourDuration() {
        TripOffer offer = parser.parse(List.of("Oferta UberX", "R$ 18,00", "750 m até embarque",
                "1 h 30 min de viagem")).orElseThrow();
        assertEquals(0.75, offer.pickupDistanceKm(), 0.0001);
        assertEquals(90.0, offer.tripDurationMinutes(), 0.0001);
    }

    @Test public void ratingRequiresPassengerContextOrStar() {
        TripOffer noContext = parser.parse(List.of("Oferta UberX R$ 18,00", "4,95")).orElseThrow();
        assertNull(noContext.passengerRating());
        TripOffer withContext = parser.parse(List.of("Oferta UberX R$ 18,00", "Passageiro", "4,95 estrelas")).orElseThrow();
        assertEquals(4.95, withContext.passengerRating(), 0.0001);
        TripOffer withStar = parser.parse(List.of("Oferta UberX R$ 18,00", "★ 4,80")).orElseThrow();
        assertEquals(4.80, withStar.passengerRating(), 0.0001);
    }

    @Test public void detectsCategoryVariantsAndRejectsNonOfferScreens() {
        assertEquals("Uber Black", parser.parse(List.of("Nova oferta Uber Black", "R$ 55,00")).orElseThrow().category());
        assertFalse(parser.parse(List.of("UberX", "R$ 18,00", "Histórico de corridas")).isPresent());
        assertFalse(parser.parse(List.of("UberX", "R$ 18,00", "Saldo da carteira")).isPresent());
        assertFalse(parser.parse(List.of("UberX", "R$ 18,00", "Corrida em andamento")).isPresent());
        assertFalse(parser.parse(List.of("Mapa", "UberX", "R$ 18,00")).isPresent());
        assertFalse(parser.parse(List.of("R$ 18,00", "Saldo da carteira")).isPresent());
    }

    @Test public void reportsParserFieldsAndReasonWithoutRetainingRawText() {
        OfferParseResult rejected = parser.parseNodes(List.of(AccessibilityNode.textOnly("Carteira", 0)));
        assertFalse(rejected.accepted());
        assertEquals("screen_context:carteira", rejected.rejectionReason());
        assertFalse(rejected.unavailableFields().isEmpty());
        assertTrue(parser.parseNodes(List.of(AccessibilityNode.textOnly("Oferta UberX R$ 18,00", 0)))
                .foundFields().contains("offered_amount"));
    }
}
