package com.driverassistant.lifecycle;

import com.driverassistant.domain.TripOffer;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OfferLifecycleMachineTest {
    @Test public void identifiesNewOfferAndSuppressesRepeatedSnapshots() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        OfferLifecycleEvent first = machine.observe(Optional.of(offer(22.0)), 100);
        OfferLifecycleEvent duplicate = machine.observe(Optional.of(offer(22.0)), 200);
        assertEquals(OfferLifecycleState.OFFER_DETECTED, first.state());
        assertTrue(first.transitioned());
        assertFalse(duplicate.transitioned());
        assertEquals(first.sessionId(), duplicate.sessionId());
    }

    @Test public void mergesPartialUpdatesAndEmitsOnlyRelevantChanges() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        TripOffer initial = new TripOffer(22.0, "UberX", null, null, null, null, null, null, null);
        TripOffer partial = new TripOffer(22.0, "UberX", 4.9, 1.2, 5.0, 7.4, 18.0, null, null);
        machine.observe(Optional.of(initial), 100);
        OfferLifecycleEvent update = machine.observe(Optional.of(partial), 200);
        assertEquals(OfferLifecycleState.OFFER_UPDATED, update.state());
        assertTrue(update.transitioned());
        assertEquals(4.9, update.offer().passengerRating(), 0.0001);
        assertEquals(22.0, update.offer().offeredAmount(), 0.0001);
        assertFalse(machine.observe(Optional.of(partial), 300).transitioned());
    }

    @Test public void repeatedFieldlessCandidateStaysOneSessionUntilDataArrives() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        TripOffer fieldless = new TripOffer(null, null, null, null, null, null, null, null, null);
        OfferLifecycleEvent first = machine.observe(Optional.of(fieldless), 100);
        OfferLifecycleEvent repeated = machine.observe(Optional.of(fieldless), 200);
        TripOffer completed = new TripOffer(24.0, "UberX", null, null, null, null, null, null, null);
        OfferLifecycleEvent update = machine.observe(Optional.of(completed), 300);
        assertTrue(first.transitioned());
        assertFalse(repeated.transitioned());
        assertEquals(OfferLifecycleState.OFFER_UPDATED, update.state());
        assertEquals(first.sessionId(), update.sessionId());
    }

    @Test public void clearsFieldsThatStayAbsentBeyondTheTransientTolerance() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        TripOffer complete = offer(22.0);
        TripOffer withoutRating = new TripOffer(22.0, "UberX", null, 1.2, 5.0, 7.4, 18.0, null, null);
        machine.observe(Optional.of(complete), 100);
        machine.observe(Optional.of(withoutRating), 200);
        OfferLifecycleEvent stillTransient = machine.observe(Optional.of(withoutRating), 2_000);
        OfferLifecycleEvent cleared = machine.observe(Optional.of(withoutRating), 2_200);
        assertFalse(stillTransient.transitioned());
        assertEquals(OfferLifecycleState.OFFER_UPDATED, cleared.state());
        assertNull(cleared.offer().passengerRating());
    }

    @Test public void differentiatesNewOfferEvenWhenSomeValuesAreSimilar() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(1_000);
        OfferLifecycleEvent first = machine.observe(Optional.of(offer(22.0)), 100);
        OfferLifecycleEvent second = machine.observe(Optional.of(new TripOffer(22.0, "Comfort", 4.8,
                2.0, 5.0, 9.0, 18.0, null, null)), 200);
        assertTrue(second.transitioned());
        assertEquals(OfferLifecycleState.OFFER_DETECTED, second.state());
        assertTrue(second.sessionId() > first.sessionId());
    }

    @Test public void toleratesTransientMissingEventsThenConfirmsDisappearance() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        machine.observe(Optional.of(offer(22.0)), 100);
        OfferLifecycleEvent transientMissing = machine.observeMissing(500);
        assertFalse(transientMissing.transitioned());
        assertEquals(OfferLifecycleState.OFFER_DETECTED, transientMissing.state());
        assertFalse(machine.observeMissing(2_499).transitioned());
        OfferLifecycleEvent disappeared = machine.observeMissing(2_500);
        assertTrue(disappeared.transitioned());
        assertEquals(OfferLifecycleState.OFFER_DISAPPEARED, disappeared.state());
        assertNull(machine.activeOffer());
    }

    @Test public void laterSimilarOfferGetsANewSessionAfterDisappearance() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(200);
        OfferLifecycleEvent first = machine.observe(Optional.of(offer(22.0)), 100);
        machine.observeMissing(200);
        OfferLifecycleEvent disappeared = machine.observeMissing(400);
        OfferLifecycleEvent later = machine.observe(Optional.of(offer(22.0)), 500);
        assertEquals(OfferLifecycleState.OFFER_DISAPPEARED, disappeared.state());
        assertEquals(OfferLifecycleState.OFFER_DETECTED, later.state());
        assertTrue(later.sessionId() > first.sessionId());
    }

    @Test public void disablingMonitoringClearsActiveOfferWithoutInferringAcceptance() {
        OfferLifecycleMachine machine = new OfferLifecycleMachine(2_000);
        machine.observe(Optional.of(offer(22.0)), 100);
        OfferLifecycleEvent disabled = machine.disable();
        assertEquals(OfferLifecycleState.OFFER_DISAPPEARED, disabled.state());
        assertEquals("monitoring_disabled", disabled.reason());
        assertNull(machine.activeOffer());
    }

    private static TripOffer offer(double amount) {
        return new TripOffer(amount, "UberX", 4.8, 1.2, 5.0, 7.4, 18.0, null, null);
    }
}
