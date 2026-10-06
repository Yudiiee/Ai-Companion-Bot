package io.github.yudiiee.aicompanion.Persona;

/**
 * Immutable descriptor for a named bot persona.
 *
 * <p>A persona bundles:
 * <ul>
 *   <li>{@code id}           — unique lowercase key used as command argument (e.g. {@code "warrior"})</li>
 *   <li>{@code displayName}  — human-readable label shown in feedback messages</li>
 *   <li>{@code systemPrompt} — paragraph prepended to every LLM system prompt when this persona is active</li>
 *   <li>{@code moodBias}     — optional {@link io.github.yudiiee.aicompanion.Mood.MoodLabel} name that is nudged
 *                              upward at spawn (may be {@code null} for no bias)</li>
 * </ul>
 */
public record PersonaTemplate(
        String id,
        String displayName,
        String systemPrompt,
        String moodBias          // nullable; matches a MoodLabel name
) {
    public PersonaTemplate {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("PersonaTemplate id must not be blank");
        if (systemPrompt == null)       throw new IllegalArgumentException("PersonaTemplate systemPrompt must not be null");
    }
}
