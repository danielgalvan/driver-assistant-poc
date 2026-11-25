package com.driverassistant.diagnostics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class BoundedEventBufferTest {
    @Test public void retainsNewestEventsWithinCapacityInNewestFirstOrder() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(3);
        buffer.add("one");
        buffer.add("two");
        buffer.add("three");
        buffer.add("four");
        assertEquals(List.of("four", "three", "two"), buffer.snapshot());
        assertEquals(3, buffer.size());
    }

    @Test public void loadingPersistedEventsKeepsOnlyTheNewestCapacity() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(2);
        buffer.loadNewestFirst(List.of("newest", "older", "oldest"));
        assertEquals(List.of("newest", "older"), buffer.snapshot());
    }
}
