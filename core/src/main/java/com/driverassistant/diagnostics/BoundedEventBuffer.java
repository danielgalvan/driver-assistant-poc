package com.driverassistant.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Fixed-capacity newest-first event ring buffer. */
public final class BoundedEventBuffer {
    private final int capacity;
    private final ArrayDeque<String> events = new ArrayDeque<>();

    public BoundedEventBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void add(String event) {
        if (event == null || event.isBlank()) return;
        events.addFirst(event);
        while (events.size() > capacity) events.removeLast();
    }

    public synchronized List<String> snapshot() { return new ArrayList<>(events); }
    public synchronized int size() { return events.size(); }
    public int capacity() { return capacity; }

    public synchronized void loadNewestFirst(List<String> values) {
        events.clear();
        if (values == null) return;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (events.size() >= capacity) break;
            events.addLast(value);
        }
    }
}
