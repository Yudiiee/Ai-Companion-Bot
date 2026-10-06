package io.github.yudiiee.aicompanion.GameAI.mood;

/**
 * Immutable two-dimensional affective state for a single bot.
 *
 * <ul>
 *   <li><b>valence</b> — how positive/negative the bot feels, clamped to {@code [-1.0, 1.0]}.
 *       Negative = sad/hostile; zero = neutral; positive = happy/friendly.</li>
 *   <li><b>arousal</b> — activation level, clamped to {@code [0.0, 1.0]}.
 *       Low = calm/sleepy; high = excited/agitated.</li>
 * </ul>
 *
 * <p>Instances are produced by {@link MoodEngine} and are safe to share across threads.
 */
public final class AffectiveState {

    /** Completely neutral, low-arousal baseline. */
    public static final AffectiveState NEUTRAL = new AffectiveState(0.0f, 0.2f);

    private final float valence;
    private final float arousal;

    /**
     * @param valence clamped to {@code [-1, 1]}
     * @param arousal clamped to {@code [0,  1]}
     */
    public AffectiveState(float valence, float arousal) {
        this.valence = clamp(valence, -1.0f, 1.0f);
        this.arousal = clamp(arousal,  0.0f, 1.0f);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Valence in {@code [-1, 1]}: positive = good mood, negative = bad mood. */
    public float getValence() { return valence; }

    /** Arousal in {@code [0, 1]}: 0 = calm, 1 = highly activated. */
    public float getArousal() { return arousal; }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a new {@code AffectiveState} with the given deltas applied and
     * the result clamped to valid ranges.
     *
     * @param dv valence delta (positive = happier, negative = sadder)
     * @param da arousal delta (positive = more excited, negative = calmer)
     */
    public AffectiveState withDelta(float dv, float da) {
        return new AffectiveState(valence + dv, arousal + da);
    }

    /**
     * Returns a new state nudged toward {@link #NEUTRAL} by {@code factor} (0–1).
     * Used by {@link MoodEngine}'s decay tick.
     *
     * @param factor how strongly to pull toward neutral each tick (e.g. {@code 0.02f})
     */
    public AffectiveState decayToward(AffectiveState target, float factor) {
        float newV = valence + (target.valence - valence) * factor;
        float newA = arousal + (target.arousal - arousal) * factor;
        return new AffectiveState(newV, newA);
    }

    /**
     * Compact one-line description for logging / prompt injection.
     * Example: {@code "valence=0.45 arousal=0.62 (HAPPY)"}
     */
    @Override
    public String toString() {
        return String.format("valence=%.2f arousal=%.2f (%s)",
                valence, arousal, MoodLabel.from(this).name());
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AffectiveState other)) return false;
        return Float.compare(other.valence, valence) == 0
                && Float.compare(other.arousal, arousal) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Float.hashCode(valence) + Float.hashCode(arousal);
    }
}
