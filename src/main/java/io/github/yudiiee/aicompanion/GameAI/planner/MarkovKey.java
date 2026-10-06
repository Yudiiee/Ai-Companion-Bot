package io.github.yudiiee.aicompanion.GameAI.planner;

import java.util.Objects;

/**
 * Compact key for 2nd-order Markov chain.
 * Captures: goal context, and previous 2 actions.
 */
public class MarkovKey {
    public final short goalId;      // Which goal we're pursuing
    public final int contextHash;   // State context fingerprint
    public final byte prev2;        // Action 2 steps back
    public final byte prev1;        // Action 1 step back

    public MarkovKey(short goalId, int contextHash, byte prev2, byte prev1) {
        this.goalId = goalId;
        this.contextHash = contextHash;
        this.prev2 = prev2;
        this.prev1 = prev1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MarkovKey)) return false;
        MarkovKey that = (MarkovKey) o;
        return goalId == that.goalId &&
               contextHash == that.contextHash &&
               prev2 == that.prev2 &&
               prev1 == that.prev1;
    }

    @Override
    public int hashCode() {
        return Objects.hash(goalId, contextHash, prev2, prev1);
    }

    @Override
    public String toString() {
        return String.format("MK[g:%d,c:%x,p:%d->%d]", goalId, contextHash, prev2, prev1);
    }
}

