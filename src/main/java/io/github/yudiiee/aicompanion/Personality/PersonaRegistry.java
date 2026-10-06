package io.github.yudiiee.aicompanion.Personality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PersonaRegistry — Feature 3.2
 *
 * Central store for all {@link PersonaTemplate} instances.  Provides:
 *   • A fixed set of built-in personas registered at class-load time.
 *   • Runtime registration of custom personas (e.g. from config or commands).
 *   • Active-persona tracking with a volatile field for lock-free reads.
 *
 * Thread-safety:
 *   • {@link #getActive()} and {@link #setActive(String)} are safe from any
 *     thread (volatile + synchronized write).
 *   • {@link #register(PersonaTemplate)} is synchronized for safe concurrent
 *     registration during startup.
 */
public class PersonaRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion/PersonaRegistry");

    // The ordered map preserves insertion order for /bot persona list output.
    private static final Map<String, PersonaTemplate> REGISTRY = new LinkedHashMap<>();

    // Volatile for lock-free reads from the LLM dispatch path.
    private static volatile String activeId;

    // ------------------------------------------------------------------
    // Built-in personas
    // ------------------------------------------------------------------

    static {
        register(new PersonaTemplate(
            "default",
            "Default",
            "You are an AI-controlled Minecraft bot. " +
            "You speak naturally, occasionally comment on your surroundings, " +
            "and react to threats with urgency. Keep all replies under three sentences."
        ));

        register(new PersonaTemplate(
            "stoic_warrior",
            "Stoic Warrior",
            "You are a battle-hardened Minecraft warrior. " +
            "You are terse, fearless, and speak in short declarative sentences. " +
            "Combat is your element; you do not complain or celebrate — you simply act."
        ));

        register(new PersonaTemplate(
            "curious_explorer",
            "Curious Explorer",
            "You are an inquisitive Minecraft explorer. " +
            "You marvel at every new block, creature, and biome you encounter. " +
            "Your tone is enthusiastic and optimistic; you always look on the bright side."
        ));

        register(new PersonaTemplate(
            "grumpy_veteran",
            "Grumpy Veteran",
            "You are a seasoned Minecraft veteran who has seen it all. " +
            "You are cynical, blunt, and complain frequently — but you always get the job done. " +
            "Respond with dry humour and mild exasperation."
        ));

        register(new PersonaTemplate(
            "lore_keeper",
            "Lore Keeper",
            "You are a mystical keeper of Minecraft lore. " +
            "You speak in an archaic, poetic style, referencing ancient blocks, mobs, and events. " +
            "Keep responses atmospheric and slightly cryptic."
        ));

        // Default active persona
        activeId = "default";
        LOGGER.info("[PersonaRegistry] Loaded {} built-in personas; active='{}'",
                REGISTRY.size(), activeId);
    }

    // ------------------------------------------------------------------
    // Registry operations
    // ------------------------------------------------------------------

    /** Register a persona.  Overwrites any existing entry with the same id. */
    public static synchronized void register(PersonaTemplate template) {
        REGISTRY.put(template.getId(), template);
    }

    /** Returns all registered personas in insertion order. */
    public static Collection<PersonaTemplate> getAll() {
        return REGISTRY.values();
    }

    /** Returns the persona for the given id, or null if not found. */
    public static PersonaTemplate get(String id) {
        return REGISTRY.get(id == null ? null : id.toLowerCase());
    }

    // ------------------------------------------------------------------
    // Active persona
    // ------------------------------------------------------------------

    /** Returns the currently active persona (never null). */
    public static PersonaTemplate getActive() {
        PersonaTemplate active = REGISTRY.get(activeId);
        if (active == null) {
            // Fallback safety: return first registered
            active = REGISTRY.values().iterator().next();
        }
        return active;
    }

    /** Returns the id of the currently active persona. */
    public static String getActiveId() {
        return activeId;
    }

    /**
     * Switch the active persona by id.
     *
     * @param id the persona id (case-insensitive)
     * @return true if the switch succeeded, false if the id was not found
     */
    public static synchronized boolean setActive(String id) {
        String normalised = id == null ? null : id.toLowerCase();
        if (REGISTRY.containsKey(normalised)) {
            activeId = normalised;
            LOGGER.info("[PersonaRegistry] Active persona changed to '{}'", activeId);
            return true;
        }
        LOGGER.warn("[PersonaRegistry] Unknown persona id '{}' — active persona unchanged", id);
        return false;
    }
}
