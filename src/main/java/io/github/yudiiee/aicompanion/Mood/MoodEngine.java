package io.github.yudiiee.aicompanion.Mood;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-bot {@link AffectiveState} and applies mood-shaping events.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Each bot (identified by its in-game name) has its own mood map.</li>
 *   <li>Events push a delta onto one or more dimensions; all values are clamped
 *       to {@code [0.0, 1.0]} after every update.</li>
 *   <li>A passive decay tick (called from {@code BotEventHandler} or a scheduler)
 *       nudges every dimension back toward 0.5 (neutral) by {@code DECAY_RATE} per
 *       call, preventing runaway emotional states.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * The mutable dimension maps are stored in a {@link ConcurrentHashMap}; individual
 * dimension updates are synchronised on the per-bot map instance.
 */
public final class MoodEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("mood-engine");

    /** Rate at which mood dimensions drift back to 0.5 per decay tick. */
    public static final float DECAY_RATE = 0.02f;

    /** Neutral baseline every dimension decays toward. */
    public static final float NEUTRAL_BASELINE = 0.5f;

    /** Per-bot mutable dimension storage. */
    private static final ConcurrentHashMap<String, EnumMap<MoodLabel, Float>> botMoods =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a snapshot of the current {@link AffectiveState} for {@code botName}.
     * Initialises a neutral state on first access.
     */
    public static AffectiveState getState(String botName) {
        return new AffectiveState(getDimensions(botName));
    }

    /**
     * Applies a mood event by adding {@code delta} to the specified {@code label}
     * dimension.  All dimensions are clamped to {@code [0,1]} after the update.
     *
     * @param botName the bot to affect
     * @param label   the mood dimension to shift
     * @param delta   signed change (positive = stronger, negative = weaker)
     */
    public static void applyEvent(String botName, MoodLabel label, float delta) {
        EnumMap<MoodLabel, Float> dims = getDimensions(botName);
        synchronized (dims) {
            float current = dims.getOrDefault(label, NEUTRAL_BASELINE);
            float updated = Math.max(0f, Math.min(1f, current + delta));
            dims.put(label, updated);
        }
        LOGGER.debug("[MoodEngine] {} | {} {}{} → dominant: {}",
                botName, label, delta >= 0 ? "+" : "", delta, getState(botName).dominant());
    }

    /**
     * Directly sets a bot's dominant mood by pinning the target dimension to
     * {@code 0.85} and all others to {@code 0.15}.  Useful for operator overrides
     * via {@code /bot mood}.
     *
     * @param botName the bot to affect
     * @param label   the mood to set as dominant
     */
    public static void setMood(String botName, MoodLabel label) {
        EnumMap<MoodLabel, Float> dims = getDimensions(botName);
        synchronized (dims) {
            for (MoodLabel l : MoodLabel.values()) {
                dims.put(l, l == label ? 0.85f : 0.15f);
            }
        }
        LOGGER.info("[MoodEngine] {} mood forced → {}", botName, label);
    }

    /**
     * Passive decay tick — call once per game tick (or at a coarser interval).
     * Every dimension drifts toward {@link #NEUTRAL_BASELINE} by {@link #DECAY_RATE}.
     *
     * @param botName the bot to tick
     */
    public static void decayTick(String botName) {
        EnumMap<MoodLabel, Float> dims = getDimensions(botName);
        synchronized (dims) {
            for (MoodLabel l : MoodLabel.values()) {
                float v = dims.getOrDefault(l, NEUTRAL_BASELINE);
                float decayed = v + (NEUTRAL_BASELINE - v) * DECAY_RATE;
                dims.put(l, decayed);
            }
        }
    }

    /**
     * Removes all stored mood state for a bot (call on despawn / disconnect).
     *
     * @param botName the bot to evict
     */
    public static void evict(String botName) {
        botMoods.remove(botName);
        LOGGER.debug("[MoodEngine] evicted mood state for {}", botName);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Returns (or lazily creates) the mutable dimension map for a bot. */
    private static EnumMap<MoodLabel, Float> getDimensions(String botName) {
        return botMoods.computeIfAbsent(botName, k -> {
            EnumMap<MoodLabel, Float> fresh = new EnumMap<>(MoodLabel.class);
            for (MoodLabel l : MoodLabel.values()) {
                fresh.put(l, NEUTRAL_BASELINE);
            }
            return fresh;
        });
    }
}
