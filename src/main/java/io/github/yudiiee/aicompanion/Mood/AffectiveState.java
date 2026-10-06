package io.github.yudiiee.aicompanion.Mood;

import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable snapshot of the bot's current affective (emotional) state.
 *
 * <p>Each mood dimension is stored as a normalised float in {@code [0.0, 1.0]}.  The
 * dominant {@link MoodLabel} is derived from the highest-scoring dimension.</p>
 */
public final class AffectiveState {

    private final Map<MoodLabel, Float> dimensions;

    /** Constructs an AffectiveState from a fully-populated dimension map (values clamped to [0,1]). */
    public AffectiveState(Map<MoodLabel, Float> dimensions) {
        EnumMap<MoodLabel, Float> copy = new EnumMap<>(MoodLabel.class);
        for (MoodLabel label : MoodLabel.values()) {
            float v = dimensions.getOrDefault(label, 0f);
            copy.put(label, Math.max(0f, Math.min(1f, v)));
        }
        this.dimensions = copy;
    }

    /** Returns the intensity of a given mood label. */
    public float get(MoodLabel label) {
        return dimensions.getOrDefault(label, 0f);
    }

    /**
     * Returns the dominant mood — the {@link MoodLabel} with the highest intensity.
     * Ties are broken by enum ordinal (lower ordinal wins).
     */
    public MoodLabel dominant() {
        MoodLabel best = MoodLabel.NEUTRAL;
        float bestVal = -1f;
        for (Map.Entry<MoodLabel, Float> entry : dimensions.entrySet()) {
            if (entry.getValue() > bestVal) {
                bestVal = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AffectiveState{");
        dimensions.forEach((k, v) -> sb.append(k).append('=').append(String.format("%.2f", v)).append(','));
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }
}
