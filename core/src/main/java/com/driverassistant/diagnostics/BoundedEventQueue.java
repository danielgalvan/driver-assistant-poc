package com.driverassistant.diagnostics;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Small FIFO queue that evicts the oldest least-important item under pressure. */
public final class BoundedEventQueue<T> {
    public record OfferResult<T>(boolean accepted, T dropped, boolean incomingDropped) { }

    private record Entry<T>(T value, int priority) { }

    private final int capacity;
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

    public BoundedEventQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized OfferResult<T> offer(T value, int priority) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        if (entries.size() < capacity) {
            entries.addLast(new Entry<>(value, priority));
            return new OfferResult<>(true, null, false);
        }

        int lowestPriority = Integer.MAX_VALUE;
        for (Entry<T> entry : entries) lowestPriority = Math.min(lowestPriority, entry.priority());
        if (priority < lowestPriority) return new OfferResult<>(false, value, true);

        Iterator<Entry<T>> iterator = entries.iterator();
        T dropped = null;
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (entry.priority() == lowestPriority) {
                dropped = entry.value();
                iterator.remove();
                break;
            }
        }
        entries.addLast(new Entry<>(value, priority));
        return new OfferResult<>(true, dropped, false);
    }

    public synchronized T pollFirst() {
        Entry<T> entry = entries.pollFirst();
        return entry == null ? null : entry.value();
    }

    public synchronized int size() { return entries.size(); }
    public synchronized boolean isEmpty() { return entries.isEmpty(); }

    public synchronized void clear() { entries.clear(); }
}
