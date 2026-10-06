package io.github.yudiiee.aicompanion.GameAI.mood;

/**
 * Maps a 2-D {@link AffectiveState} to one of nine human-readable mood labels
 * derived from Russell's circumplex model of affect.
 *
 * <pre>
 * Arousal axis
 *  HIGH  │ AGITATED   EXCITED    ELATED
 *  MID   │ BORED      NEUTRAL    CONTENT
 *  LOW   │ DEPRESSED  CALM       SERENE
 *        └──────────────────────────────
 *            NEG        NEUTRAL    POS   ← Valence axis
 * </pre>
 *
 * <p>Thresholds: valence bands at {@code < -0.2} (negative), {@code > 0.2} (positive);
 * arousal bands at {@code < 0.35} (low), {@code > 0.65} (high).
 */
public enum MoodLabel {

    // Low arousal
    DEPRESSED,   // low arousal, negative valence
    CALM,        // low arousal, neutral valence
    SERENE,      // low arousal, positive valence

    // Mid arousal
    BORED,       // mid arousal, negative valence
    NEUTRAL,     // mid arousal, neutral valence
    CONTENT,     // mid arousal, positive valence

    // High arousal
    AGITATED,    // high arousal, negative valence
    EXCITED,     // high arousal, neutral valence
    ELATED;      // high arousal, positive valence

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    private static final float V_NEG_THRESHOLD = -0.20f;
    private static final float V_POS_THRESHOLD =  0.20f;
    private static final float A_LOW_THRESHOLD =  0.35f;
    private static final float A_HIGH_THRESHOLD = 0.65f;

    /**
     * Returns the best-matching {@link MoodLabel} for the given state.
     * Never returns {@code null}.
     */
    public static MoodLabel from(AffectiveState state) {
        int vBand = valenceBand(state.getValence());
        int aBand = arousalBand(state.getArousal());

        // aBand: 0=low, 1=mid, 2=high
        // vBand: 0=neg, 1=neutral, 2=pos
        return switch (aBand * 3 + vBand) {
            case 0 -> DEPRESSED;
            case 1 -> CALM;
            case 2 -> SERENE;
            case 3 -> BORED;
            case 4 -> NEUTRAL;
            case 5 -> CONTENT;
            case 6 -> AGITATED;
            case 7 -> EXCITED;
            case 8 -> ELATED;
            default -> NEUTRAL;
        };
    }

    /**
     * Short adjective string suitable for prompt injection.
     * Example: {@code "happy and energetic"}
     */
    public String toPromptFragment() {
        return switch (this) {
            case DEPRESSED  -> "sad and withdrawn";
            case CALM       -> "calm and composed";
            case SERENE     -> "peaceful and content";
            case BORED      -> "bored and uninterested";
            case NEUTRAL    -> "neutral and balanced";
            case CONTENT    -> "comfortable and satisfied";
            case AGITATED   -> "irritable and tense";
            case EXCITED    -> "energised and alert";
            case ELATED     -> "happy and enthusiastic";
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int valenceBand(float v) {
        if (v < V_NEG_THRESHOLD) return 0;   // negative
        if (v > V_POS_THRESHOLD) return 2;   // positive
        return 1;                             // neutral
    }

    private static int arousalBand(float a) {
        if (a < A_LOW_THRESHOLD)  return 0;  // low
        if (a > A_HIGH_THRESHOLD) return 2;  // high
        return 1;                            // mid
    }
}
