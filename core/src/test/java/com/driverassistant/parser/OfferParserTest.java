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

    @Test public void parsesSimulatedPriorityOfferWithoutTreatingAddOnsAsFare() {
        List<AccessibilityNode> simulatedTree = List.of(
                new AccessibilityNode("Priority", null, "offer/category", "TextView", -1, 0, 0),
                new AccessibilityNode("Exclusivo", null, "offer/subtitle", "TextView", 0, 1, 1),
                new AccessibilityNode("R$ 29,09", null, "offer/fare", "TextView", 0, 1, 2),
                new AccessibilityNode("Est. de R$ 2,51/km", null, "offer/estimated_rate", "TextView", 0, 1, 3),
                new AccessibilityNode("4,93 (1051)", null, "offer/passenger_rating", "TextView", 0, 1, 4),
                new AccessibilityNode("+R$ 8,50 incluído", null, "offer/surge_bonus", "TextView", 0, 1, 5),
                new AccessibilityNode("+R$ 3,22 incluída para retirada", null, "offer/pickup_bonus", "TextView", 0, 1, 6),
                new AccessibilityNode("7 min (0,8 km)", null, "offer/pickup_details", "TextView", 0, 1, 7),
                new AccessibilityNode("Local de embarque", null, "offer/pickup_address", "TextView", 0, 1, 8),
                new AccessibilityNode("33 minutos (10,8 km)", null, "offer/trip_details", "TextView", 0, 1, 9),
                new AccessibilityNode("Destino", null, "offer/trip_destination", "TextView", 0, 1, 10),
                new AccessibilityNode("Viagem longa (mais de 30 min)", null, "offer/long_trip_badge", "TextView", 0, 1, 11),
                new AccessibilityNode("Aceitar", null, "offer/accept", "Button", 0, 1, 12));

        OfferParseResult parsed = parser.parseNodes(simulatedTree);
        assertTrue(parsed.accepted());
        assertEquals(29.09, parsed.offer().offeredAmount(), 0.0001);
        assertEquals("Priority", parsed.offer().category());
        assertEquals(4.93, parsed.offer().passengerRating(), 0.0001);
        assertEquals(0.8, parsed.offer().pickupDistanceKm(), 0.0001);
        assertEquals(7.0, parsed.offer().pickupEtaMinutes(), 0.0001);
        assertEquals(10.8, parsed.offer().tripDistanceKm(), 0.0001);
        assertEquals(33.0, parsed.offer().tripDurationMinutes(), 0.0001);
    }

    @Test public void rejectsSimulatedOfflineOpportunityMapAmountsAsOffers() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("UberX", 0),
                AccessibilityNode.textOnly("Ficar online", 1),
                AccessibilityNode.textOnly("Oportunidades", 2),
                AccessibilityNode.textOnly("+R$ 10,00", 3),
                AccessibilityNode.textOnly("+R$ 3,50", 4),
                AccessibilityNode.textOnly("+R$ 12,50", 5)));
        assertFalse(parsed.accepted());
        assertEquals("screen_context:offline_opportunity_map", parsed.rejectionReason());
    }

    @Test public void rejectsOfflineMissionRangeWhenHelpTextMentionsAcceptingRequests() {
        List<AccessibilityNode> offlineHome = List.of(
                new AccessibilityNode("Home", null, "home/root", "FrameLayout", -1, 0, 0),
                new AccessibilityNode("Oportunidades", null, "home/opportunities", "TextView", 0, 1, 1),
                new AccessibilityNode("UberX", null, "map/category", "TextView", 0, 1, 2),
                new AccessibilityNode("Missões disponíveis entre R$ 38 e R$ 46", null,
                        "home/missions_summary", "TextView", 0, 1, 3),
                new AccessibilityNode("1-3 min", null, "map/marker_eta", "TextView", 0, 1, 4),
                new AccessibilityNode("1-2 min", null, "map/marker_eta", "TextView", 0, 1, 5),
                new AccessibilityNode("Ficar online", null, "home/go_online_button", "Button", 0, 1, 6),
                new AccessibilityNode("Veja melhores horários para aceitar solicitações hoje.", null,
                        "home/help_copy", "TextView", 0, 1, 7));

        OfferParseResult parsed = parser.parseNodes(offlineHome, true);

        assertFalse(parsed.accepted());
        assertEquals("screen_context:offline_opportunity_map", parsed.rejectionReason());
        assertTrue(parsed.diagnosticTrace().stream()
                .anyMatch(value -> value.contains("source_tree=true")));
    }

    @Test public void doesNotPromoteAnUnlabeledMapAmountAndTravelCueToAnOffer() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("UberX", 0),
                AccessibilityNode.textOnly("Oportunidades", 1),
                AccessibilityNode.textOnly("+R$ 14,75", 2),
                AccessibilityNode.textOnly("5 min", 3),
                AccessibilityNode.textOnly("12 km de viagem", 4)));
        assertFalse(parsed.accepted());
        assertEquals("missing_accept_action_or_labeled_fare", parsed.rejectionReason());
    }

    @Test public void hiddenStaleAcceptAndOfferLabelsDoNotTurnMapSurgeIntoAnOffer() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                new AccessibilityNode("Tela Uber", null, "root", "FrameLayout", -1, 0, 0),
                new AccessibilityNode("Ficar online", null, "home/go_online", "Button", 0, 1, 1),
                new AccessibilityNode("Oportunidades", null, "home/opportunities", "TextView", 0, 1, 2),
                new AccessibilityNode("UberX", null, "map/category", "TextView", 0, 1, 3),
                new AccessibilityNode("+R$ 12,75", null, "map/promotion", "TextView", 0, 1, 4),
                new AccessibilityNode("Aceitar", null, "request/accept", "Button", 0, 1, 5, false),
                new AccessibilityNode("Oferta Uber", null, "request/title", "TextView", 0, 1, 6, false)));

        assertFalse(parsed.accepted());
        assertEquals("screen_context:offline_opportunity_map", parsed.rejectionReason());
    }

    @Test public void fareAndVisibleActionWithoutIndependentOfferDataAreRejected() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                new AccessibilityNode("Oferta Uber", null, "request/title", "TextView", 0, 1, 0),
                new AccessibilityNode("R$ 14,75", null, "map/promotion", "TextView", 0, 1, 1),
                new AccessibilityNode("Aceitar", null, "request/accept", "Button", 0, 1, 2),
                new AccessibilityNode("UberX", null, "request/category", "TextView", 0, 1, 3, false)));

        assertFalse(parsed.accepted());
        assertEquals("missing_accept_action_or_labeled_fare", parsed.rejectionReason());
    }

    @Test public void parsesSimulatedOfferCardOnMapWithoutAccessibleAcceptButton() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("Mapa", 0),
                AccessibilityNode.textOnly("UberX Exclusivo", 1),
                AccessibilityNode.textOnly("R$ 20,03", 2),
                AccessibilityNode.textOnly("★ 5,00 (19)", 3),
                AccessibilityNode.textOnly("5 min (0,6 km) até o embarque", 4),
                AccessibilityNode.textOnly("45 minutos (20,3 km) de viagem", 5),
                AccessibilityNode.textOnly("+R$ 7,75 incluído", 6)));

        assertTrue(parsed.accepted());
        assertEquals(20.03, parsed.offer().offeredAmount(), 0.0001);
        assertEquals("UberX", parsed.offer().category());
        assertEquals(5.0, parsed.offer().passengerRating(), 0.0001);
        assertEquals(0.6, parsed.offer().pickupDistanceKm(), 0.0001);
        assertEquals(5.0, parsed.offer().pickupEtaMinutes(), 0.0001);
        assertEquals(20.3, parsed.offer().tripDistanceKm(), 0.0001);
        assertEquals(45.0, parsed.offer().tripDurationMinutes(), 0.0001);
    }

    @Test public void structuredMapOfferCardStillWorksWhenPassengerRatingIsUnavailable() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("Mapa", 0),
                AccessibilityNode.textOnly("Comfort", 1),
                AccessibilityNode.textOnly("R$ 20,03", 2),
                AccessibilityNode.textOnly("7 min (0,8 km) até o embarque", 3),
                AccessibilityNode.textOnly("33 min (10,8 km) de viagem", 4)));

        assertTrue(parsed.accepted());
        assertEquals(20.03, parsed.offer().offeredAmount(), 0.0001);
        assertEquals("Comfort", parsed.offer().category());
        assertNull(parsed.offer().passengerRating());
        assertEquals(0.8, parsed.offer().pickupDistanceKm(), 0.0001);
        assertEquals(7.0, parsed.offer().pickupEtaMinutes(), 0.0001);
        assertEquals(10.8, parsed.offer().tripDistanceKm(), 0.0001);
        assertEquals(33.0, parsed.offer().tripDurationMinutes(), 0.0001);
    }

    @Test public void mapPromotionsAreIgnoredWhenTheyLackRideCardEvidence() {
        OfferParseResult parsed = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("Mapa", 0),
                AccessibilityNode.textOnly("UberX", 1),
                AccessibilityNode.textOnly("+R$ 12,75", 2),
                AccessibilityNode.textOnly("1 min", 3),
                AccessibilityNode.textOnly("0,6 km", 4),
                AccessibilityNode.textOnly("5 min", 5),
                AccessibilityNode.textOnly("6,2 km", 6)));

        assertFalse(parsed.accepted());
        assertEquals("screen_context:map", parsed.rejectionReason());
    }

    @Test public void parsesSimulatedUberXRequestInsteadOfMapBonusAndEstimate() {
        TripOffer offer = parser.parse(List.of(
                "UberX Exclusivo", "R$ 36,03", "Est. de R$ 1,72/km", "★ 5,00 (19)",
                "+R$ 7,75 incluído", "5 min (0,6 km)", "Local de embarque",
                "45 minutos (20,3 km)", "Destino", "Viagem longa (mais de 30 min)", "Aceitar")).orElseThrow();
        assertEquals(36.03, offer.offeredAmount(), 0.0001);
        assertEquals("UberX", offer.category());
        assertEquals(5.0, offer.passengerRating(), 0.0001);
        assertEquals(0.6, offer.pickupDistanceKm(), 0.0001);
        assertEquals(5.0, offer.pickupEtaMinutes(), 0.0001);
        assertEquals(20.3, offer.tripDistanceKm(), 0.0001);
        assertEquals(45.0, offer.tripDurationMinutes(), 0.0001);
    }

    @Test public void selectsTheRideCardSubtreeAndIgnoresOfflineMapPromotions() {
        List<AccessibilityNode> simulatedTree = List.of(
                new AccessibilityNode("Tela Uber", null, "root", "FrameLayout", -1, 0, 0),
                new AccessibilityNode("Ficar online", null, "home/go_online", "Button", 0, 1, 1),
                new AccessibilityNode("Oportunidades", null, "home/opportunities", "TextView", 0, 1, 2),
                new AccessibilityNode("+R$ 12,50", null, "map/promotion", "TextView", 0, 1, 3),
                new AccessibilityNode("Card da solicitação", null, "request/card", "LinearLayout", 0, 1, 4),
                new AccessibilityNode("UberX", null, "request/category", "TextView", 4, 2, 5),
                new AccessibilityNode("R$ 36,03", null, "request/fare", "TextView", 4, 2, 6),
                new AccessibilityNode("Est. de R$ 1,72/km", null, "request/estimate", "TextView", 4, 2, 7),
                new AccessibilityNode("5,00 (19)", null, "request/passenger_rating", "TextView", 4, 2, 8),
                new AccessibilityNode("+R$ 7,75 incluído", null, "request/bonus", "TextView", 4, 2, 9),
                new AccessibilityNode("5 min (0,6 km)", null, "request/pickup", "TextView", 4, 2, 10),
                new AccessibilityNode("Local de embarque", null, "request/pickup_address", "TextView", 4, 2, 11),
                new AccessibilityNode("45 minutos (20,3 km)", null, "request/trip", "TextView", 4, 2, 12),
                new AccessibilityNode("Destino", null, "request/destination", "TextView", 4, 2, 13),
                new AccessibilityNode("Viagem longa (mais de 30 min)", null, "request/long_trip", "TextView", 4, 2, 14),
                new AccessibilityNode("Aceitar", null, "request/accept", "Button", 4, 2, 15));

        OfferParseResult parsed = parser.parseNodes(simulatedTree);
        assertTrue(parsed.accepted());
        assertEquals(36.03, parsed.offer().offeredAmount(), 0.0001);
        assertEquals("UberX", parsed.offer().category());
        assertEquals(5.0, parsed.offer().pickupEtaMinutes(), 0.0001);
        assertEquals(0.6, parsed.offer().pickupDistanceKm(), 0.0001);
        assertEquals(45.0, parsed.offer().tripDurationMinutes(), 0.0001);
        assertEquals(20.3, parsed.offer().tripDistanceKm(), 0.0001);
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

    @Test public void detailedDiagnosticsExposeValidationPathForARejectedSimulatedCandidate() {
        OfferParseResult result = parser.parseNodes(List.of(
                AccessibilityNode.textOnly("UberX", 0),
                AccessibilityNode.textOnly("R$ 18,00", 1)), true);

        assertFalse(result.accepted());
        assertEquals("missing_accept_action_or_labeled_fare", result.rejectionReason());
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("isolateOfferCard")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("EXCLUDED_SCREENS")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("hasAcceptanceAction=")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("hasStructuredOfferCard=")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("money_candidate")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.startsWith("findOfferAmount")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.contains("hasSecondaryOfferData=")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.contains("hasOfferContext=false")));
        assertTrue(result.diagnosticTrace().stream().anyMatch(value -> value.equals(
                "parser_result=rejected; reason=missing_accept_action_or_labeled_fare")));
    }

    @Test public void detailedDiagnosticsKeepSameAcceptedResultForSimulatedOffer() {
        List<AccessibilityNode> simulatedTree = List.of(
                AccessibilityNode.textOnly("Oferta UberX R$ 22,00", 0),
                AccessibilityNode.textOnly("5 min (1 km) até o embarque", 1),
                AccessibilityNode.textOnly("20 min (8 km) de viagem", 2),
                AccessibilityNode.textOnly("Nota 4,95", 3),
                AccessibilityNode.textOnly("Aceitar", 4));

        OfferParseResult ordinary = parser.parseNodes(simulatedTree);
        OfferParseResult detailed = parser.parseNodes(simulatedTree, true);

        assertEquals(ordinary.accepted(), detailed.accepted());
        assertEquals(ordinary.rejectionReason(), detailed.rejectionReason());
        assertEquals(ordinary.offer().offeredAmount(), detailed.offer().offeredAmount());
        assertTrue(detailed.diagnosticTrace().stream().anyMatch(value -> value.startsWith("parser_result=accepted")));
    }
}
