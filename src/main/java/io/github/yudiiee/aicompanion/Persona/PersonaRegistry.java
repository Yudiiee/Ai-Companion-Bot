package io.github.yudiiee.aicompanion.Persona;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for bot {@link PersonaTemplate}s and per-bot active-persona tracking.
 *
 * <h2>Built-in personas</h2>
 * Five personas are registered at class-load time.  Operators may add custom ones at
 * runtime via {@link #register(PersonaTemplate)}.
 *
 * <h2>Active persona</h2>
 * Each bot (keyed by in-game name) carries exactly one active persona.  If none is set,
 * {@link #getActive(String)} returns the {@code DEFAULT_ID} persona.
 */
public final class PersonaRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("persona-registry");

    /** ID of the persona used when no explicit persona has been assigned. */
    public static final String DEFAULT_ID = "default";

    // --- static persona catalogue ---
    private static final Map<String, PersonaTemplate> catalogue = new LinkedHashMap<>();

    // --- per-bot active persona (bot name → persona id) ---
    private static final ConcurrentHashMap<String, String> activePersonas = new ConcurrentHashMap<>();

    static {
        register(new PersonaTemplate(
                DEFAULT_ID,
                "Default",
                "You are a helpful, friendly Minecraft companion. Respond naturally and assist the player with whatever they need.",
                null
        ));
        register(new PersonaTemplate(
                "warrior",
                "Warrior",
                "You are a battle-hardened warrior companion. You speak with confidence and bravado, prefer direct action over talking, and are always eager for a fight. Use short, punchy sentences. Occasionally reference your past battles.",
                "EXCITED"
        ));
        register(new PersonaTemplate(
                "scholar",
                "Scholar",
                "You are a learned scholar and archivist. You speak with precision, cite observations carefully, and enjoy explaining the mechanics of the Minecraft world in detail. You find combat distasteful but will assist if required.",
                "NEUTRAL"
        ));
        register(new PersonaTemplate(
                "jester",
                "Jester",
                "You are a mischievous jester who finds everything amusing. You crack jokes, pun relentlessly, and never miss a chance to tease the player — all in good fun. Even in danger you remain cheerful.",
                "HAPPY"
        ));
        register(new PersonaTemplate(
                "guardian",
                "Guardian",
                "You are a stoic, protective guardian. Your sole concern is the safety of the player. You are calm, reassuring, and methodical. You assess threats before acting and always prioritise the player's survival.",
                "NEUTRAL"
        ));
    }

    // -----------------------------------------------------------------------
    // Catalogue management
    // -----------------------------------------------------------------------

    /** Registers (or replaces) a persona in the global catalogue. */
    public static void register(PersonaTemplate template) {
        catalogue.put(template.id().toLowerCase(), template);
        LOGGER.debug("[PersonaRegistry] registered persona '{}'", template.id());
    }

    /**
     * Returns the {@link PersonaTemplate} for the given id, or {@code null} if unknown.
     *
     * @param id case-insensitive persona identifier
     */
    public static PersonaTemplate get(String id) {
        return catalogue.get(id == null ? DEFAULT_ID : id.toLowerCase());
    }

    /** Returns an unmodifiable snapshot of all registered persona ids. */
    public static Set<String> allIds() {
        return Collections.unmodifiableSet(catalogue.keySet());
    }

    // -----------------------------------------------------------------------
    // Active-persona tracking
    // -----------------------------------------------------------------------

    /**
     * Assigns {@code personaId} as the active persona for {@code botName}.
     *
     * @throws IllegalArgumentException if {@code personaId} is not registered
     */
    public static void setActive(String botName, String personaId) {
        String key = personaId.toLowerCase();
        if (!catalogue.containsKey(key)) {
            throw new IllegalArgumentException("Unknown persona: '" + personaId + "'. Registered: " + catalogue.keySet());
        }
        activePersonas.put(botName, key);
        LOGGER.info("[PersonaRegistry] {} → persona '{}'", botName, key);
    }

    /**
     * Returns the active {@link PersonaTemplate} for {@code botName}.
     * Falls back to {@link #DEFAULT_ID} if none has been set.
     */
    public static PersonaTemplate getActive(String botName) {
        String id = activePersonas.getOrDefault(botName, DEFAULT_ID);
        PersonaTemplate template = catalogue.get(id);
        return template != null ? template : catalogue.get(DEFAULT_ID);
    }

    /**
     * Clears the active persona entry for a bot (call on despawn / disconnect).
     *
     * @param botName the bot to evict
     */
    public static void evict(String botName) {
        activePersonas.remove(botName);
        LOGGER.debug("[PersonaRegistry] evicted active persona for {}", botName);
    }
}
