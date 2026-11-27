package com.driverassistant.diagnostics;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Small queue that coalesces window snapshots and protects relevant snapshots under pressure. */
public final class BoundedEventQueue<T> {
    public record OfferResult<T>(boolean accepted, T dropped, boolean incomingDropped, String reason) { }

    private record Entry<T>(T value, int priority, String key, boolean protectedSnapshot) { }

    private final int capacity;
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

    public BoundedEventQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized OfferResult<T> offer(T value, int priority) {
        return offer(value, priority, null, false);
    }

    /**
     * Coalesces stale work for the same window. A protected snapshot (for example a visible offer
     * card or an offer-related accessibility node) cannot be replaced by ordinary map noise.
     */
    public synchronized OfferResult<T> offer(T value, int priority, String key, boolean protectedSnapshot) {
        if (value == null) throw new IllegalArgumentException("value must not be null");

        if (key != null) {
            Iterator<Entry<T>> sameWindow = entries.iterator();
            Entry<T> replaced = null;
            while (sameWindow.hasNext()) {
                Entry<T> existing = sameWindow.next();
                if (!key.equals(existing.key())) continue;
                if (existing.protectedSnapshot()
                        && (!protectedSnapshot || priority < existing.priority())) {
                    return new OfferResult<>(false, value, true, "protected_snapshot_kept");
                }
                sameWindow.remove();
                replaced = existing;
                break;
            }
            if (replaced != null) {
                entries.addLast(new Entry<>(value, priority, key, protectedSnapshot));
                return new OfferResult<>(true, replaced.value(), false, "coalesced_same_window");
            }
        }
        if (entries.size() < capacity) {
            entries.addLast(new Entry<>(value, priority, key, protectedSnapshot));
            return new OfferResult<>(true, null, false, "queued");
        }

        int lowestPriority = Integer.MAX_VALUE;
        boolean foundEvictable = false;
        for (Entry<T> entry : entries) {
            if (entry.protectedSnapshot()) continue;
            lowestPriority = Math.min(lowestPriority, entry.priority());
            foundEvictable = true;
        }
        if (!foundEvictable) {
            return new OfferResult<>(false, value, true, "queue_contains_only_protected_snapshots");
        }
        if (priority < lowestPriority && !protectedSnapshot) {
            return new OfferResult<>(false, value, true, "incoming_lower_priority");
        }

        Iterator<Entry<T>> iterator = entries.iterator();
        T dropped = null;
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (!entry.protectedSnapshot() && entry.priority() == lowestPriority) {
                dropped = entry.value();
                iterator.remove();
                break;
            }
        }
        if (dropped == null) return new OfferResult<>(false, value, true, "no_evictable_snapshot");
        entries.addLast(new Entry<>(value, priority, key, protectedSnapshot));
        return new OfferResult<>(true, dropped, false, "evicted_oldest_low_priority_unprotected");
    }

    public synchronized T pollFirst() {
        Entry<T> entry = entries.pollFirst();
        return entry == null ? null : entry.value();
    }

    public synchronized int size() { return entries.size(); }
    public synchronized boolean isEmpty() { return entries.isEmpty(); }

    public synchronized void clear() { entries.clear(); }
}
