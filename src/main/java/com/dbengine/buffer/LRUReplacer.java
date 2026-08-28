package com.dbengine.buffer;

import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Tracks usage of buffer pool frames to determine which page to evict
 * when memory is full. Uses a Least Recently Used (LRU) policy.
 */
public class LRUReplacer {

    // LinkedHashSet maintains insertion order. The first element is the least recently used.
    private final LinkedHashSet<Integer> unpinnedFrames;
    private final int capacity;

    public LRUReplacer(int capacity) {
        this.capacity = capacity;
        this.unpinnedFrames = new LinkedHashSet<>(capacity);
    }

    /**
     * Called when a page is pinned (actively being used by a thread).
     * It is removed from the LRU because it cannot be evicted.
     */
    public synchronized void pin(int frameId) {
        unpinnedFrames.remove(frameId);
    }

    /**
     * Called when a page's pin count reaches 0. It is now eligible for eviction.
     */
    public synchronized void unpin(int frameId) {
        if (unpinnedFrames.size() >= capacity) {
            return; // Safety check, though pool size usually prevents this
        }
        // Removing and re-adding ensures it becomes the MOST recently unpinned (moved to the end)
        unpinnedFrames.remove(frameId);
        unpinnedFrames.add(frameId);
    }

    /**
     * Finds the least recently used frame, removes it from tracking, and returns its ID.
     */
    public synchronized Optional<Integer> evict() {
        if (unpinnedFrames.isEmpty()) {
            return Optional.empty();
        }
        // The first element is the oldest (Least Recently Used)
        int victim = unpinnedFrames.iterator().next();
        unpinnedFrames.remove(victim);
        return Optional.of(victim);
    }

    public synchronized int size() {
        return unpinnedFrames.size();
    }
}
