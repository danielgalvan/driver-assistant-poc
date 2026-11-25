package com.driverassistant.parser;

import com.driverassistant.domain.TripOffer;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OfferParserTest {
    private final OfferParser parser = new OfferParser();

    @Test public void parsesOfferFieldsFromLabeledText() {
        Optional<TripOffer> parsed = parser.parse(List.of(
                "UberX", "R$ 22,00", "1,2 km até o embarque · 5 min",
                "7,4 km de viagem · 18 min", "Nota: 4,95"));

        assertTrue(parsed.isPresent());
        TripOffer offer = parsed.orElseThrow();
        assertEquals(22.0, offer.offeredAmount(), 0.0001);
        assertEquals("UberX", offer.category());
        assertEquals(1.2, offer.pickupDistanceKm(), 0.0001);
        assertEquals(5.0, offer.pickupEtaMinutes(), 0.0001);
        assertEquals(7.4, offer.tripDistanceKm(), 0.0001);
        assertEquals(18.0, offer.tripDurationMinutes(), 0.0001);
        assertEquals(4.95, offer.passengerRating(), 0.0001);
    }

    @Test public void keepsUnavailableFieldsNullAndDoesNotInventValues() {
        TripOffer offer = parser.parse(List.of("UberX", "R$ 18,00")).orElseThrow();
        assertNull(offer.pickupDistanceKm());
        assertNull(offer.pickupEtaMinutes());
        assertNull(offer.tripDistanceKm());
        assertNull(offer.tripDurationMinutes());
        assertNull(offer.passengerRating());
    }

    @Test public void rejectsAmountWithoutRideContext() {
        assertFalse(parser.parse(List.of("R$ 18,00", "Saldo da carteira")).isPresent());
        assertFalse(parser.parse(null).isPresent());
    }

    @Test public void parsesMeterDistanceAndHourDuration() {
        TripOffer offer = parser.parse(List.of("R$ 18,00", "UberX", "750 m até embarque", "1,5 h de viagem"))
                .orElseThrow();
        assertEquals(0.75, offer.pickupDistanceKm(), 0.0001);
        assertEquals(90.0, offer.tripDurationMinutes(), 0.0001);
    }
}
