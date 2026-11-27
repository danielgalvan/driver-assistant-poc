package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BoundedEventQueueTest {
    @Test public void preservesFifoOrderWhileThereIsCapacity() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(3);
        assertTrue(queue.offer("first", 1).accepted());
        assertTrue(queue.offer("second", 1).accepted());
        assertTrue(queue.offer("third", 1).accepted());

        assertEquals("first", queue.pollFirst());
        assertEquals("second", queue.pollFirst());
        assertEquals("third", queue.pollFirst());
        assertNull(queue.pollFirst());
    }

    @Test public void evictsOldestLowestPriorityItemForImportantWindowEvent() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        queue.offer("content-a", 1);
        queue.offer("window-state", 3);

        BoundedEventQueue.OfferResult<String> result = queue.offer("content-b", 1);

        assertTrue(result.accepted());
        assertEquals("content-a", result.dropped());
        assertFalse(result.incomingDropped());
        assertEquals("window-state", queue.pollFirst());
        assertEquals("content-b", queue.pollFirst());
    }

    @Test public void dropsLowerPriorityIncomingItemWithoutRemovingQueuedWindowChange() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(1);
        queue.offer("window-state", 3);

        BoundedEventQueue.OfferResult<String> result = queue.offer("map-noise", 0);

        assertFalse(result.accepted());
        assertTrue(result.incomingDropped());
        assertEquals("map-noise", result.dropped());
        assertEquals("window-state", queue.pollFirst());
    }

    @Test public void coalescesRapidSnapshotsFromTheSameWindowToTheNewestOne() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        assertTrue(queue.offer("first", 2, "uber-window", false).accepted());
        BoundedEventQueue.OfferResult<String> replacement = queue.offer("latest", 1, "uber-window", false);

        assertTrue(replacement.accepted());
        assertEquals("coalesced_same_window", replacement.reason());
        assertEquals("first", replacement.dropped());
        assertEquals(1, queue.size());
        assertEquals("latest", queue.pollFirst());
    }

    @Test public void keepsAProtectedOfferSnapshotWhenNewerMapNoiseArrives() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        queue.offer("offer-snapshot", 5, "uber-window", true);

        BoundedEventQueue.OfferResult<String> result = queue.offer("map-snapshot", 3, "uber-window", false);

        assertFalse(result.accepted());
        assertEquals("protected_snapshot_kept", result.reason());
        assertEquals("offer-snapshot", queue.pollFirst());
    }

    @Test public void structuralClueCannotReplaceAStrongerFareSnapshot() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        queue.offer("fare-and-route", 6, "uber-window", true);

        BoundedEventQueue.OfferResult<String> result = queue.offer("browse-pill-only", 5,
                "uber-window", true);

        assertFalse(result.accepted());
        assertEquals("protected_snapshot_kept", result.reason());
        assertEquals("fare-and-route", queue.pollFirst());
    }

    @Test public void evictsUnprotectedWorkBeforeDroppingAnotherProtectedWindow() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        queue.offer("offer-window-a", 5, "a", true);
        queue.offer("ordinary-window-b", 1, "b", false);

        BoundedEventQueue.OfferResult<String> result = queue.offer("offer-window-c", 5, "c", true);

        assertTrue(result.accepted());
        assertEquals("ordinary-window-b", result.dropped());
        assertEquals("offer-window-a", queue.pollFirst());
        assertEquals("offer-window-c", queue.pollFirst());
    }

    @Test public void refusesMapWorkWhenTheBoundedQueueContainsOnlyProtectedSnapshots() {
        BoundedEventQueue<String> queue = new BoundedEventQueue<>(2);
        queue.offer("offer-a", 5, "a", true);
        queue.offer("offer-b", 5, "b", true);

        BoundedEventQueue.OfferResult<String> result = queue.offer("map", 1, "c", false);

        assertFalse(result.accepted());
        assertEquals("queue_contains_only_protected_snapshots", result.reason());
        assertEquals(2, queue.size());
    }
}
