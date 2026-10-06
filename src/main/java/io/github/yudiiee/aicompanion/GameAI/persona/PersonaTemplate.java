package io.github.yudiiee.aicompanion.GameAI.persona;

import io.github.yudiiee.aicompanion.GameAI.mood.AffectiveState;

/**
 * Immutable description of a bot personality archetype.
 *
 * <p>A persona is a <em>static</em> personality baseline; mood is the
 * <em>dynamic</em> emotional layer that sits on top.  The final system prompt
 * produced by {@code PromptBuilder} is:
 * <pre>
 *   base identity text
 *     + PersonaTemplate.basePromptFragment
 *     + MoodEngine.getPromptFragment(botName)
 * </pre>
 *
 * <p>The {@link #defaultValence} / {@link #defaultArousal} values are used to
 * seed {@link io.github.yudiiee.aicompanion.GameAI.mood.MoodEngine} when a bot spawns (or
 * when the player runs {@code /bot persona <bot> <id>}), so a "cheerful"
 * persona always starts with a positive mood baseline rather than neutral.
 *
 * @param id                  Lowercase slug used as the config key and in
 *                            command tab-completion. Example: {@code "cheerful"}.
 * @param displayName         Human-readable label shown to players.
 * @param basePromptFragment  One or two sentences appended verbatim to the
 *                            LLM system prompt.  Should describe personality
 *                            <em>style</em>, not game-world facts.
 * @param defaultValence      Starting valence in {@code [-1.0, 1.0]} when this
 *                            persona is selected.
 * @param defaultArousal      Starting arousal in {@code [0.0, 1.0]} when this
 *                            persona is selected.
 */
public record PersonaTemplate(
        String id,
        String displayName,
        String basePromptFragment,
        float  defaultValence,
        float  defaultArousal
) {

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    public PersonaTemplate {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("PersonaTemplate id must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("PersonaTemplate displayName must not be blank");
        if (basePromptFragment == null || basePromptFragment.isBlank())
            throw new IllegalArgumentException("PersonaTemplate basePromptFragment must not be blank");
        // clamp mood seeds to valid ranges
        defaultValence = Math.max(-1.0f, Math.min(1.0f, defaultValence));
        defaultArousal = Math.max( 0.0f, Math.min(1.0f, defaultArousal));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns an {@link AffectiveState} seeded from this persona's defaults.
     * Passed to {@link io.github.yudiiee.aicompanion.GameAI.mood.MoodEngine#set} on spawn
     * or persona change.
     */
    public AffectiveState defaultMoodState() {
        return new AffectiveState(defaultValence, defaultArousal);
    }

    /**
     * Short one-liner for logging and command feedback.
     * Example: {@code "[cheerful] Cheerful — Upbeat, uses humour..."}
     */
    @Override
    public String toString() {
        return String.format("[%s] %s — %s", id, displayName, basePromptFragment);
    }
}
