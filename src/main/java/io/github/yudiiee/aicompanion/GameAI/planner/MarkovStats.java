package io.github.yudiiee.aicompanion.GameAI.planner;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Stores transition counts for a single Markov key.
 * Thread-safe via atomic operations.
 */
public class MarkovStats {
    private static final int MAX_ACTIONS = 256; // Support up to 256 different action types

    private final AtomicIntegerArray counts;
    private volatile int total;

    public MarkovStats() {
        this.counts = new AtomicIntegerArray(MAX_ACTIONS);
        this.total = 0;
    }

    /**
     * Record that action 'actionId' was taken from this state.
     */
    public void recordTransition(byte actionId) {
        int idx = actionId & 0xFF; // Convert to unsigned
        counts.incrementAndGet(idx);
        synchronized (this) {
            total++;
        }
    }

    /**
     * Get count for a specific action.
     */
    public int getCount(byte actionId) {
        int idx = actionId & 0xFF;
        return counts.get(idx);
    }

    /**
     * Get total transitions from this state.
     */
    public synchronized int getTotal() {
        return total;
    }

    /**
     * Get probability of action with Laplace smoothing.
     * P(action) = (count + 1) / (total + vocabSize)
     */
    public double getProbability(byte actionId, int vocabSize) {
        int count = getCount(actionId);
        int tot = getTotal();
        return (count + 1.0) / (tot + vocabSize);
    }

    @Override
    public String toString() {
        return String.format("MarkovStats[total=%d]", total);
    }
}

