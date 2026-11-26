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
}
