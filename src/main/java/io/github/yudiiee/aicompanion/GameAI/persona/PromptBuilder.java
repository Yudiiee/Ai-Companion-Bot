package io.github.yudiiee.aicompanion.GameAI.persona;

import io.github.yudiiee.aicompanion.GameAI.mood.AffectiveState;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised factory for LLM system prompts.
 *
 * <p>Previously, {@code ollamaClient} and {@code LLMServiceHandler} each contained
 * an identical, duplicated {@code generateSystemPrompt()} method.  This class
 * replaces both, composing the final prompt from three orthogonal layers:
 *
 * <ol>
 *   <li><b>Base identity</b> — the immutable Minecraft-player framing text that
 *       has always existed.  This is identical to the old hard-coded strings.</li>
 *   <li><b>Persona fragment</b> (optional) — a one-to-two sentence personality
 *       description from the active {@link PersonaTemplate}.  Injected when a
 *       persona has been selected for this bot; omitted otherwise so the old
 *       behaviour is exactly preserved.</li>
 *   <li><b>Mood fragment</b> (optional) — a short sentence describing the bot's
 *       current emotional state derived from {@link MoodLabel#toPromptFragment()}.
 *       Injected when a mood engine state is available; omitted otherwise.</li>
 * </ol>
 */
public final class PromptBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("prompt-builder");

    private PromptBuilder() { /* static API only */ }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the complete LLM system prompt for {@code botName}.
     *
     * @param botName   The in-game name of the bot.  Must not be null.
     * @param persona   The active {@link PersonaTemplate}, or {@code null} to
     *                  omit the persona layer (preserves original behaviour).
     * @param mood      The current {@link AffectiveState}, or {@code null} to
     *                  omit the mood layer (preserves original behaviour).
     * @return          The fully composed system-prompt string ready to pass to
     *                  the LLM as the {@code SYSTEM} role message.
     */
    public static String build(String botName, PersonaTemplate persona, AffectiveState mood) {
        if (botName == null || botName.isBlank()) {
            throw new IllegalArgumentException("PromptBuilder.build(): botName must not be blank");
        }

        StringBuilder sb = new StringBuilder(2048);

        // Layer 1 — base identity
        sb.append(baseIdentity(botName));

        // Layer 2 — persona (omit if null; HumanPersona already includes the active one)
        if (persona != null && persona != PersonaRegistry.getActive(botName)) {
            sb.append("\n\n");
            sb.append("Personality: ").append(persona.basePromptFragment());
            LOGGER.debug("[prompt-builder] Injecting persona '{}' for bot '{}'",
                    persona.id(), botName);
        }

        // Layer 3 — live mood (HumanPersona already includes the live mood)
        if (false && mood != null) {
            MoodLabel label = MoodLabel.from(mood);
            String fragment = label.toPromptFragment();
            sb.append("\n\nCurrent mood: ").append(fragment).append(".");
            LOGGER.debug("[prompt-builder] Injecting mood {} ({}) for bot '{}'",
                    label, mood, botName);
        }

        return sb.toString();
    }

    /**
     * Convenience overload — builds a prompt with no persona or mood overlay.
     */
    public static String build(String botName) {
        return build(botName, null, null);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String baseIdentity(String botName) {
        // Live situation + recent chat are appended by HumanPersona itself.
        return io.github.yudiiee.aicompanion.GameAI.human.HumanPersona.systemPrompt(botName, true);
    }
}
