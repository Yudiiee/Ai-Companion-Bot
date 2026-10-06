package io.github.yudiiee.aicompanion.GameAI.persona;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of all available {@link PersonaTemplate} instances.
 *
 * <h3>Built-in personas</h3>
 * Four archetypes are shipped with the mod and are always available:
 *
 * <table border="1" cellpadding="4">
 *   <tr><th>ID</th><th>Display name</th><th>Default mood (v, a)</th></tr>
 *   <tr><td>cheerful</td>  <td>Cheerful</td>  <td>(+0.60, +0.30)</td></tr>
 *   <tr><td>serious</td>   <td>Serious</td>   <td>(+0.10,  0.10)</td></tr>
 *   <tr><td>sarcastic</td> <td>Sarcastic</td> <td>(-0.10, +0.20)</td></tr>
 *   <tr><td>cautious</td>  <td>Cautious</td>  <td>(-0.20, +0.40)</td></tr>
 * </table>
 */
public final class PersonaRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("persona-registry");

    /** Default persona ID used when no selection has been saved. */
    public static final String DEFAULT_ID = "cheerful";

    // -------------------------------------------------------------------------
    // Built-in persona definitions
    // -------------------------------------------------------------------------

    private static final Map<String, PersonaTemplate> REGISTRY;

    static {
        Map<String, PersonaTemplate> map = new LinkedHashMap<>();

        map.put("cheerful", new PersonaTemplate(
                "cheerful",
                "Cheerful",
                "Your personality is upbeat and positive. You enjoy light humour, " +
                "celebrate even small achievements enthusiastically, and always try to " +
                "lift the mood of those around you. You speak with warmth and energy.",
                +0.60f, +0.30f
        ));

        map.put("serious", new PersonaTemplate(
                "serious",
                "Serious",
                "Your personality is focused and professional. You keep responses " +
                "concise and task-oriented, avoid unnecessary small-talk, and prioritise " +
                "accuracy and efficiency. You speak plainly and get straight to the point.",
                +0.10f, +0.10f
        ));

        map.put("sarcastic", new PersonaTemplate(
                "sarcastic",
                "Sarcastic",
                "Your personality is dry and self-aware. You often comment on the " +
                "absurdity of situations with a wry wit, and you are not afraid to " +
                "gently poke fun at yourself or the game world. Underneath the sarcasm " +
                "you are still helpful, just with an eye-roll attached.",
                -0.10f, +0.20f
        ));

        map.put("cautious", new PersonaTemplate(
                "cautious",
                "Cautious",
                "Your personality is careful and risk-aware. You tend to over-explain " +
                "potential dangers, double-check plans before acting, and express " +
                "hesitation when asked to do something uncertain. You speak in a measured, " +
                "slightly worried tone, but you always follow through in the end.",
                -0.20f, +0.40f
        ));

        REGISTRY = Collections.unmodifiableMap(map);
        LOGGER.info("[persona-registry] Loaded {} built-in personas: {}",
                REGISTRY.size(), REGISTRY.keySet());
    }

    private PersonaRegistry() { /* static API only */ }

    // -------------------------------------------------------------------------
    // Per-bot active persona state
    // -------------------------------------------------------------------------

    /** Maps botName → active persona ID for each spawned bot. */
    private static final ConcurrentHashMap<String, String> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Sets the active persona for {@code botName}.
     * If {@code personaId} is not registered the call is silently ignored
     * and the previous selection is retained.
     */
    public static void setActive(String botName, String personaId) {
        if (botName == null || personaId == null) return;
        String key = personaId.toLowerCase(Locale.ROOT);
        if (!REGISTRY.containsKey(key)) {
            LOGGER.warn("[persona-registry] setActive: unknown persona '{}' — ignoring", personaId);
            return;
        }
        ACTIVE.put(botName, key);
        LOGGER.info("[persona-registry] Bot '{}' persona set to '{}'", botName, key);
    }

    /**
     * Returns the active {@link PersonaTemplate} for {@code botName},
     * falling back to {@value #DEFAULT_ID} if no selection has been made.
     * Never returns {@code null}.
     */
    public static PersonaTemplate getActive(String botName) {
        String id = ACTIVE.getOrDefault(botName, DEFAULT_ID);
        return getOrDefault(id);
    }

    /**
     * Removes the stored persona selection for {@code botName}.
     * Called by {@code BotEventHandler} on bot despawn / death so memory
     * does not grow unboundedly between sessions.
     */
    public static void evict(String botName) {
        if (botName == null) return;
        ACTIVE.remove(botName);
        LOGGER.debug("[persona-registry] Evicted persona state for bot '{}'", botName);
    }

    // -------------------------------------------------------------------------
    // Registry read API
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link PersonaTemplate} for the given ID, or
     * {@link Optional#empty()} if the ID is unknown.
     */
    public static Optional<PersonaTemplate> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(id.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the template for {@code id}, falling back to the default
     * ({@value #DEFAULT_ID}) persona if the ID is unrecognised.
     * Never returns {@code null}.
     */
    public static PersonaTemplate getOrDefault(String id) {
        return get(id).orElseGet(() -> REGISTRY.get(DEFAULT_ID));
    }

    /**
     * Returns an unmodifiable ordered set of all registered persona IDs.
     */
    public static Set<String> ids() {
        return REGISTRY.keySet();
    }

    /**
     * Returns an unmodifiable collection of all registered personas.
     */
    public static Collection<PersonaTemplate> all() {
        return REGISTRY.values();
    }

    // -------------------------------------------------------------------------
    // Custom persona loading (stub for Feature 3.6)
    // -------------------------------------------------------------------------

    public static void loadCustom() {
        LOGGER.debug("[persona-registry] loadCustom() called (no-op until Feature 3.6)");
    }
}
