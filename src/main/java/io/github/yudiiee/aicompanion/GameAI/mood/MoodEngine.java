package io.github.yudiiee.aicompanion.GameAI.mood;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Singleton registry that owns the {@link AffectiveState} for every active bot.
 *
 * <h3>Threading model</h3>
 * All state is stored in a {@link ConcurrentHashMap} with atomic compute operations,
 * making reads and writes safe from any thread (event handlers, autonomous loop,
 * command handlers, etc.).
 *
 * <h3>Decay</h3>
 * A single background scheduler fires every {@value #DECAY_INTERVAL_SECONDS} seconds
 * and nudges every bot's state 2 % toward {@link AffectiveState#NEUTRAL}.
 * This means extreme states naturally fade over ~3–4 minutes of inactivity.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // On player join event:
 * MoodEngine.applyDelta(botName, +0.20f, +0.15f);
 *
 * // Inside PromptBuilder:
 * String fragment = MoodEngine.getPromptFragment(botName);
 * // → "You are currently feeling happy and enthusiastic."
 * }</pre>
 */
public final class MoodEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("mood-engine");

    /** How often the decay tick fires, in seconds. */
    private static final long DECAY_INTERVAL_SECONDS = 30L;

    /**
     * How strongly each tick pulls mood toward neutral.
     * {@code 0.02f} means 2 % per 30 s → halves extreme states in ~17 minutes.
     */
    private static final float DECAY_FACTOR = 0.02f;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Per-bot affective state, keyed by bot name. */
    private static final ConcurrentHashMap<String, AffectiveState> STATES =
            new ConcurrentHashMap<>();

    /** Single shared decay scheduler (daemon thread — does not block JVM shutdown). */
    private static final ScheduledExecutorService DECAY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mood-decay-tick");
                t.setDaemon(true);
                return t;
            });

    static {
        DECAY_SCHEDULER.scheduleAtFixedRate(
                MoodEngine::decayAll,
                DECAY_INTERVAL_SECONDS,
                DECAY_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        LOGGER.info("[mood-engine] Decay scheduler started (interval={}s, factor={})",
                DECAY_INTERVAL_SECONDS, DECAY_FACTOR);
    }

    private MoodEngine() { /* static API only */ }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the current {@link AffectiveState} for {@code botName}.
     * If no state exists yet, initialises with {@link AffectiveState#NEUTRAL}.
     */
    public static AffectiveState get(String botName) {
        return STATES.computeIfAbsent(botName, k -> AffectiveState.NEUTRAL);
    }

    /**
     * Applies a valence + arousal delta to the bot's current state.
     * The result is clamped to valid ranges automatically by {@link AffectiveState}.
     *
     * @param botName  the bot whose mood to adjust
     * @param dValence valence change  (+positive/−negative; typical range ±0.05–0.30)
     * @param dArousal arousal change  (+more excited/−calmer;  typical range ±0.05–0.20)
     */
    public static void applyDelta(String botName, float dValence, float dArousal) {
        STATES.compute(botName, (k, current) -> {
            AffectiveState base = (current != null) ? current : AffectiveState.NEUTRAL;
            AffectiveState next = base.withDelta(dValence, dArousal);
            LOGGER.debug("[mood-engine] {} : {} → {} (Δv={}, Δa={})",
                    botName, base, next, dValence, dArousal);
            return next;
        });
    }

    /**
     * Explicitly sets the bot's mood to the given state, overwriting any
     * previous value.  Intended for the {@code /bot mood set} command.
     */
    public static void set(String botName, AffectiveState state) {
        STATES.put(botName, state);
        LOGGER.info("[mood-engine] {} mood manually set to {}", botName, state);
    }

    /**
     * Removes all state for the given bot.  Call on bot disconnect / server stop.
     */
    public static void remove(String botName) {
        STATES.remove(botName);
    }

    /**
     * Alias for {@link #remove(String)}.  Called by {@code BotEventHandler.onBotDespawn}
     * so both mood and persona state are cleaned up with the same method name.
     */
    public static void evict(String botName) {
        remove(botName);
        LOGGER.debug("[mood-engine] evicted state for bot '{}'", botName);
    }

    /**
     * One manual decay tick — called from the RL loop's {@code finally} block
     * so mood nudges toward neutral on every decision cycle regardless of the
     * background scheduler.
     */
    public static void decayTick(String botName) {
        STATES.computeIfPresent(botName, (k, state) ->
            state.decayToward(AffectiveState.NEUTRAL, DECAY_FACTOR));
    }

    /**
     * Returns a short string ready for injection into a system prompt.
     * Example: {@code "You are currently feeling happy and enthusiastic."}
     */
    public static String getPromptFragment(String botName) {
        AffectiveState state = get(botName);
        MoodLabel label = MoodLabel.from(state);
        return "You are currently feeling " + label.toPromptFragment() + ".";
    }

    /**
     * Returns a human-readable snapshot for the {@code /bot mood} command.
     * Example: {@code "Mood: ELATED  (valence=0.72, arousal=0.81)"}
     */
    public static String getStatusSnapshot(String botName) {
        AffectiveState state = get(botName);
        MoodLabel label = MoodLabel.from(state);
        return String.format("Mood: %s  (valence=%.2f, arousal=%.2f)",
                label.name(), state.getValence(), state.getArousal());
    }

    // -------------------------------------------------------------------------
    // Decay
    // -------------------------------------------------------------------------

    /**
     * Called by the scheduler every {@value #DECAY_INTERVAL_SECONDS} seconds.
     * Nudges every bot's affective state toward neutral by {@link #DECAY_FACTOR}.
     */
    private static void decayAll() {
        if (STATES.isEmpty()) return;
        STATES.replaceAll((bot, state) -> {
            AffectiveState decayed = state.decayToward(AffectiveState.NEUTRAL, DECAY_FACTOR);
            LOGGER.trace("[mood-engine] decay {} : {} → {}", bot, state, decayed);
            return decayed;
        });
    }
}
