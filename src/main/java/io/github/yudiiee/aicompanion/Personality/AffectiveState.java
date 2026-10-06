package io.github.yudiiee.aicompanion.Personality;

/**
 * AffectiveState — the set of discrete moods the bot can inhabit.
 * Used by MoodEngine (derivation), PromptBuilder (system-prompt colouring),
 * and the /bot mood command (manual override).
 */
public enum AffectiveState {

    /** Default — calm, observant, neither threatened nor elated. */
    NEUTRAL,

    /** Content — high health, full hunger, no threats nearby. */
    HAPPY,

    /** Threat detected but survivable — cautious, defensive. */
    FEARFUL,

    /** Winning a fight — confident, forward-pressing. */
    AGGRESSIVE,

    /** Critical health under attack — flight instinct active. */
    PANICKED,

    /** Nothing happening for a while — under-stimulated. */
    BORED;

    /**
     * Returns a short, lower-case adjectival string suitable for
     * embedding directly into an LLM system prompt.
     */
    public String toPromptWord() {
        return switch (this) {
            case NEUTRAL    -> "calm and observant";
            case HAPPY      -> "cheerful and relaxed";
            case FEARFUL    -> "cautious and tense";
            case AGGRESSIVE -> "confident and aggressive";
            case PANICKED   -> "desperate and panicked";
            case BORED      -> "bored and restless";
        };
    }
}
