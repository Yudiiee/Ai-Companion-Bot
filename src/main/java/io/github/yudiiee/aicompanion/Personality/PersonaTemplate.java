package io.github.yudiiee.aicompanion.Personality;

/**
 * PersonaTemplate — Feature 3.1
 *
 * Immutable value object that carries the full specification for a bot
 * persona: a unique ID, a human-readable display name, and a system-prompt
 * string that is prepended to every LLM request while the persona is active.
 *
 * Instances are created once at registration time and never mutated.
 */
public final class PersonaTemplate {

    private final String id;           // e.g. "stoic_warrior"
    private final String displayName;  // e.g. "Stoic Warrior"
    private final String systemPrompt; // injected into every LLM call

    public PersonaTemplate(String id, String displayName, String systemPrompt) {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("PersonaTemplate id must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("PersonaTemplate displayName must not be blank");
        if (systemPrompt == null || systemPrompt.isBlank())
            throw new IllegalArgumentException("PersonaTemplate systemPrompt must not be blank");

        this.id = id.toLowerCase().replace(' ', '_');
        this.displayName = displayName;
        this.systemPrompt = systemPrompt;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public String getId()           { return id; }
    public String getDisplayName()  { return displayName; }
    public String getSystemPrompt() { return systemPrompt; }

    @Override
    public String toString() {
        return "PersonaTemplate{id='" + id + "', displayName='" + displayName + "'}";
    }
}
